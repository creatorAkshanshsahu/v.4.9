# Website Live TV Playback Fix (`creatorAkshanshsahu/screen-safari-studio`)

Production-verified fix for **`https://screen-safari-studio.lovable.app`** (`creatorAkshanshsahu/screen-safari-studio`, built cleanly against `main` @ `63a50a2` with **18/18 Vitest suites / 94 tests passing**, **`tsc --noEmit` = 0 errors**, and a clean **`vite build` (`cloudflare-module`)**).

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
   - `/embed/:id` standalone player gets the same session-token + redirect-learning policy for both Shaka and hls.js.

4. **`package.json`** — restores `@testing-library/dom@^10.4.2`, accidentally removed by `683ea35`, which broke all 18 Vitest suites.

5. **New regression tests (`src/test/playback-fixes.test.ts`)** — token append/preserve/no-overwrite/replace rules, host scoping, playlist-vs-fragment detection, expired-token pruning with verbatim URLs, `jtv39.m3u8` per-channel cookie preference, playlist-redirector mint ordering (`jio:` minted-first, `tg:` TG-first with minted next, mint-refusal fallback with diagnostics), and TG public-API fallback (rescue hit, RPC-wins-no-call, cross-id rejection).

---

## Apply & Deploy to `screen-safari-studio`

### Option A — One-Command Git Sync
```bash
./lovable-screen-safari-studio/sync-to-lovable.sh
cd screen-safari-studio
git add -A
git commit -m "fix(live): TG public channel API fallback for ids the RPC layer misses"
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
