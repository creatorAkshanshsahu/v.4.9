# AK TV Refused/Expired Playback Fix (`creatorAkshanshsahu/screen-safari-studio`)

Fix for the two Premium PlugX failures on the live site (screenshots 2026-11-14): **`tg:` channels whose host refuses the relay (HTTP 530)** and **channels whose link answers with an error page instead of a playlist**. Built and verified against `main` @ `a7b4022` (**38/38 Vitest suites / 227 tests passing**, **`tsc --noEmit` = 0 errors**, clean **`vite build`**). **Apply this patch, not the older one below** — the older patch/docs target `main` @ `63a50a2`, which predates the site's AK TV-only rewrite (PRs #38–41).

## What the two screenshots showed

1. **2423 DISCOVERY — "refused this server's address (HTTP 530). Playing it directly from your browser instead."** The relay was IP-gated by the reseller host, handed the browser its recipe, and the browser fallback *also* failed (reseller redirectors hotlink-gate browsers, and the recipe's `Referer`/cookie cannot be sent cross-origin). The sentence was then displayed as if browser-direct were still to come — a stale promise. Worse, the `/api/live/direct-config` JioTV rescue (same channel via Jio's CDN, which *does* play in browsers) never fired: the reseller suffix in `"DISCOVERY By Premium PlugX"` made Jio-name matching score 0.
2. **2752 CNBC Awaaz — "answered with something that is not an HLS playlist."** Upstream answered HTTP 200 with a non-playlist body (an HTML expired-link page, or raw MPEG-TS) at a `.m3u8` address; the relay classified by extension and served the garbage to hls.js as a playlist. The fault sentence also named no remedy (the only branch without one).

## What changed (`fix-aktv-refused-expired.patch`, base `a7b4022`)

1. **`src/lib/live-gateway.ts`**
   - Refusal errors are now terminal-accurate: *"The stream host refused this server's address (HTTP 530), and the browser-direct fallback failed too. Try the same channel from another source, or press Retry."* (same for the Jio-450 shortcut). The player only displays these after the seed, `direct-config`, and `directUrl` fallbacks have all failed.
   - New `sniffUpstreamHead()`: a 200 body starting with HTML is refused as an (expired?) reseller link instead of being served as HLS/DASH — with the browser recipe still attached in case the page was addressed to the relay's IP only. A body starting with the MPEG-TS sync byte falls through to the byte stream, so the probe names the MPEG-TS engine and mislabeled `.m3u8` streams play instead of dying in hls.js.
   - `normalizeChannelMatchTokens()` strips a trailing reseller tag (`" By …"`) before Jio-name matching, so `"CNBC Awaaz By Premium PlugX"` matches Jio's `"CNBC Awaaz"` (exact) and `"DISCOVERY …"` can match `"Discovery HD"` (quality-word-insensitive) — unlocking the proven JioTV browser-direct rescue for reseller entries whose names exist in Jio's catalogue. Honest names (`"Baby TV"`) are untouched.
2. **`src/lib/playback-faults.ts`** — the not-a-playlist fault now ends with the source's own remedy (`Press Retry to reconnect.` for AK TV).
3. **Tests** — 7 new: reseller-name strip + Jio match, HTML-expiry refusal (502 + recipe, not HLS), TS-behind-`.m3u8` probe/play as MPEG-TS, 530 terminal wording, first-byte sniff pins, fault-remedy pins.

## Apply inside `screen-safari-studio` (from `main` @ `a7b4022` or later)

```bash
git apply --check fix-aktv-refused-expired.patch && git apply fix-aktv-refused-expired.patch
yarn vitest run && yarn tsc --noEmit && yarn build
git add -A && git commit -m "fix(aktv): honest refused/expired errors, TS-mislabel playback, reseller-name Jio rescue" && git push origin main
```

---

# (Older) Website Live TV Playback Fix — base `63a50a2`, DO NOT APPLY on current `main`

> Everything below — including `files/`, `fix-website-live-channels.patch`, and `sync-to-lovable.sh` — targets `main` @ `63a50a2` and predates PRs #38–41. Kept for reference only.

Production-verified fix for **`https://screen-safari-studio.lovable.app`** (`creatorAkshanshsahu/screen-safari-studio`, built cleanly against `main` @ `63a50a2` with **18/18 Vitest suites / 96 tests passing**, **`tsc --noEmit` = 0 errors**, and a clean **`vite build` (`cloudflare-module`)**).

> `main` already contains the Browser-Direct CDN release (PR #12). This update fixes JioTV channels not playing in the browser on top of it.

---

## The Real Issue: App vs Website Token Handling

The Android app and the website resolve the **same** TG/JJTV URLs + `__hdnea__` cookies + ClearKeys — but the app plays while the website stalled with `Direct CDN stream is taking too long to deliver video frames`. Auditing the app (`StreamRequestPolicy.kt`, `StreamHttp.kt`, `JjtvProtocol.kt`) against the website found two behavioral gaps, both fixed here:

1. **The website clobbered per-request tokens; the app never does.**
   The website's Shaka request filter rebuilt *every* manifest/segment URL as `base + "?" + coarseCookie`, stripping redirect-minted tokens and sibling query params. The app's `withToken(replace=false)` *appends* the token, preserves all other params, and returns a URL that already carries a token **untouched** (only the advertised root manifest gets a swap). Result on the website: the manifest (200 OK) loaded, but segments went out with a stale/clobbered token and never produced frames.

2. **The website pinned one token for the whole session; the app learns renewed auth.**
   Jio replaces authentication on manifest redirects. The app's `observed()` captures the final wire URL's token after every successful manifest and uses it for the following segments. The website had no equivalent, so any channel whose manifest 302-mints a fresh token played the manifest and then starved on segments.

3. **HLS follow-up requests went completely unsigned.**
   Both the surface and the `/embed/:id` player signed only the root `.m3u8` URL. Variant/segment playlists resolved by hls.js dropped the query and were fetched with no token at all — guaranteed failure for every HLS direct candidate.

(Also verified equivalent, so *not* the issue: `bpk-tv//` normalization, single-key → `default_KID` mapping, query-form (not header) token transport, and no-`Referer` requests — the app works both with and without `Origin`/`Referer` on these CDN hosts.)

---

## What Changed

1. **New `src/lib/direct-token.ts`** — dependency-free browser port of `StreamRequestPolicy`: `withHdneaToken()` (append/preserve/replace semantics), `extractHdneaParam()`, `sameUrlHost()` (tokens never leave their own host), `isHlsPlaylistUrl()`.

2. **`src/components/tv/LiveVideoSurface.tsx`**
   - DASH: per-attempt session token — root manifest loaded with replace semantics (bare attempt plays the URL verbatim), manifest/segment requests signed with append semantics scoped to the candidate's host, and renewed tokens learned from every manifest's post-redirect `response.uri` (segments never overwrite session auth, exactly like `observed()`).
   - HLS: `xhrSetup` signs every variant/segment request the same way and learns renewed playlist auth from `xhr.responseURL`.
   - (Kept from the previous update: frame-verified failover across token/server with 6–8s budgets, automatic fresh-token pass, one automatic watchdog backup-switch, `onWaiting` no longer overwriting concrete errors.)

3. **`src/lib/live-gateway.ts`**
   - `/api/live/direct-config` hands the browser **verbatim** catalogue URLs (query intact) + cookie separately, like the app's `JjtvProtocol.streams`; TG leg budget-raced at 5s; expired tokens pruned; freshest-first ordering.
   - **Playlist per-channel cookies**: when the worker can read `jtv39.m3u8`, the entry's own `#EXTHTTP` cookie (BTS entries carry a token minted for exactly their channel path, e.g. `acl=/bpk-tv/History_HD_BTS/WDVLive/*`) is preferred over the coarse `cookies.json` token via freshness comparison — the same cookie the Android app plays with. Budget-raced at 3s so a blocked playlist fetch never delays direct-config.
   - **Source-aware ordering + JioTV labels**: like the app, the JioTV tab (`jio:`) now tries its Jio CDN candidates first (`Trying JioTV Direct · Fresh Token…`) and only falls back to `TG Live · Server 1/2` when Jio fails; the TG TV tab and maxTV rescue keep TG first. Freshness still wins overall (a working token always beats an expired one).
   - **Playlist redirector mint**: the worker now opens the channel's own `jtv2.mpd?id=N` address headers-only (no media downloaded) and follows the 302 to the freshly-minted signed CDN URL — exactly the app's channel-open probe. `jio:` tries `JioTV Playlist · Redirector` first, then catalogue Jio, then TG; `tg:` keeps TG first with the minted URL next. `/api/live/direct-config` reports redacted `playlistMint` diagnostics (`tried/ok/hops/lastStatus/tokenSecondsLeft`, never token values) so a browser URL shows whether the mint succeeded.
   - **TG public channel API fallback**: when `_serverFn` RPC + discovery yield nothing for an id, the worker now tries `GET /api/public/channels/<id>` — the app's last native TG lookup — so channels outside the RPC database still resolve instead of going dark. The TG extractor also reads `sources[]`/`servers[]`/`streams[]` and single url-ish fields (public-API shapes), and a document answering for another id is ignored, never borrowed.
   - **Direct-first rescue for maxTV + cross-group failover**: the player now tries browser-direct candidates before the worker proxy for every source except StarShare (the proxy probe succeeds on portal JSON and then dies slowly on 403'd media, so rescue went last or never). Attempts are interleaved first-of-each-source-group so a dead Jio cookie can't burn the 40s budget before TG gets its turn — Jio is still always tried first. The bare-URL retry is skipped when the cookie IS the URL's minted token (identical request).
   - `/embed/:id` standalone player gets the same session-token + redirect-learning policy for both Shaka and hls.js.

4. **`package.json`** — restores `@testing-library/dom@^10.4.2`, accidentally removed by `683ea35`, which broke all 18 Vitest suites.

5. **New regression tests (`src/test/playback-fixes.test.ts`)** — token append/preserve/no-overwrite/replace rules, host scoping, playlist-vs-fragment detection, expired-token pruning with verbatim URLs, `jtv39.m3u8` per-channel cookie preference, playlist-redirector mint ordering (`jio:` minted-first, `tg:` TG-first with minted next, mint-refusal fallback with diagnostics), TG public-API fallback (rescue hit, RPC-wins-no-call, cross-id rejection), and cross-group attempt interleaving.

---

## Apply & Deploy to `screen-safari-studio`

### Option A — One-Command Git Sync
```bash
./lovable-screen-safari-studio/sync-to-lovable.sh
cd screen-safari-studio
git add -A
git commit -m "fix(live): direct-first rescue for maxTV + cross-group failover so TG is reached"
git push origin main
```

### Option B — Apply Git Patch Directly inside `screen-safari-studio`
```bash
git am < lovable-screen-safari-studio/fix-website-live-channels.patch
git push origin main
```

### Option C — Open `creatorAkshanshsahu/screen-safari-studio` in Arena Agent Mode
Paste:
> Copy `lovable-screen-safari-studio/files/src/` + `lovable-screen-safari-studio/files/package.json` from `creatorAkshanshsahu/v.4.9` (`arena/a7aea110-v-4-9`) into this repo, run `vitest run` and `vite build`, and open/merge the PR to `main`.
