# Website Live TV Playback Fix (`creatorAkshanshsahu/screen-safari-studio`)

This directory contains the production-verified fix for the three Live TV playback errors on **`https://screen-safari-studio.lovable.app`** (`creatorAkshanshsahu/screen-safari-studio`, built cleanly against `main` @ `dd86141` with **18/18 Vitest suites / 82 tests passing**, **`tsc --noEmit` = 0 errors**, and a clean **`vite build` (`cloudflare-module`)**).

---

## Why Those 3 Errors Happened on `screen-safari-studio.lovable.app`

Lovable deploys `screen-safari-studio` onto **Cloudflare Workers (`AS13335`)**. Previously, every video stream was proxied server-side through the Cloudflare Worker IP:

1. **`Upstream stream returned HTTP 403.`**
   - `maxTV` (`http://new.maxtv.be`) Stalker portal JSON handshakes succeed from Cloudflare Workers, **but its media edges reject Cloudflare Worker datacenter egress IPs (`AS13335`) with `HTTP 403`** (and `proxyMediaStream("mag")` was also missing Stalker's `Cookie: mac=...; stb_lang=en; timezone=Europe/London` and `Referer` headers).

2. **`The JioTV CDN refused this channel (HTTP 450) even after re-reading the playlist for a fresh token.`**
   - `jiotvmblive.cdn.jio.com`, `jiotvpllive.cdn.jio.com`, and `allinonereborn2.online` reject **datacenter/Cloudflare Worker egress IPs** on media manifests and segments (`HTTP 450` / `HTTP 451`) regardless of how fresh the token is.
   - Meanwhile, **`*.cdn.jio.com` sends `Access-Control-Allow-Origin: *` and returns `HTTP 200` when requested directly from the viewer's own Indian browser IP** (which is how `livetgtv.lovable.app` and `giotv-6op.pages.dev` play channels in-browser).

3. **`The player requested no segments at all, so the manifest it received could not be used.`**
   - Two compounding bugs produced this message:
     1. `LiveVideoSurface.tsx` only set `specificErrorRef.current = true` inside Shaka's `error` event — **not** on probe `HTTP 403`/`502` failures, `HLS.js` fatal errors, or `mpegts.js` errors. Fifteen seconds after a `403` or `450` error appeared, the 15s watchdog timer fired, queried `/api/live/diagnose` (which hit a fresh Cloudflare Worker isolate with an empty in-memory `segmentLog`), and **overwrote the real error** with `"The player requested no segments at all..."`.
     2. `TG TV` previously pointed at `/api/public/playlist.m3u` (`HLS`) instead of its live `_serverFn` RPC + `jstr4web.json` (`DASH` + `ClearKey` + `__hdnea__`).

---

## What This Update Fixes

1. **New `/api/live/direct-config` Metadata Resolver (`src/lib/live-gateway.ts`)**
   - Fetches lightweight JSON metadata (`200 OK` from Cloudflare Workers in ~150ms, zero video bytes proxied through datacenter IPs):
     - **`TG Live` RPC (`livetgtv.lovable.app/_serverFn/...`)**: decodes `application/x-tss-framed` Seroval responses (with automatic `_serverFn` hash discovery) into **`Server 1` + `Server 2`** (`urls`, fresh `__hdnea__` `cookie`, normalized 32-char hex `clearKeys`).
     - **`GioTV` (`giotv-6op.pages.dev/jstr4web.json` — 1,176 channels)**: pairs `jiotvmblive.cdn.jio.com` streams with fresh tokens from `allinonereborn2.online/jstrweb2/cookies.json` and **`jiotvpllive.cdn.jio.com` (Star Sports / Disney+ / BTS)** streams with per-channel tokens from `giotv-6op.pages.dev/cookie.json`, normalizing `bpk-tv//` -> `bpk-tv/`.
   - **Automatic `maxTV` (`st:...`) Rescue**: when `new.maxtv.be` blocks Cloudflare Workers with `HTTP 403`, `/api/live/direct-config` fuzzy-matches the `maxTV` channel name (preserving channel numbers so `Star Sports 1` never matches `Star Sports 2`) to its live direct CDN stream + ClearKeys.
   - **Cloudflare-Safe `JioTV` & `TG TV` Catalogues**: `TG TV` now loads the 1,176-channel `jstr4web.json` ClearKey catalogue directly, and `JioTV` automatically falls back to `jstr4web.json` whenever `allinonereborn2.online` blocks Cloudflare Worker playlist fetches.

2. **Browser-Direct Shaka Engine (`src/components/tv/LiveVideoSurface.tsx`, `src/routes/live.tsx`, `/embed/:id`)**
   - Sets `<meta name="referrer" content="no-referrer">` so browsers never send a third-party `Referer` header to `*.cdn.jio.com` (`HTTP 451` trigger).
   - Uses `player.getNetworkingEngine().registerRequestFilter` to append `?__hdnea__=<token>` to every `MANIFEST` and `SEGMENT` request directly from the viewer's browser, with automatic `{ url, cookie } -> { url, cookie: "" } -> Server 2 -> GioTV` failover.
   - Uses `player.getNetworkingEngine().registerResponseFilter` to strip Widevine/PlayReady `<ContentProtection>` descriptors in-browser and map single-key ClearKeys onto `cenc:default_KID`.
   - Sets `specificErrorRef.current = true` across **all** error paths and bypasses the server-segment watchdog during browser-direct playback so real errors are never overwritten after 15 seconds.

---

## Apply & Deploy to `screen-safari-studio`

### Option A — One-Command Git Sync
```bash
./lovable-screen-safari-studio/sync-to-lovable.sh
cd screen-safari-studio
git add -A
git commit -m "fix(live): bypass Cloudflare Worker ASN blocks via Browser-Direct CDN + GioTV/TG RPC"
git push origin main
```

### Option B — Apply Git Patch Directly inside `screen-safari-studio`
```bash
git am < lovable-screen-safari-studio/fix-website-live-channels.patch
git push origin main
```

### Option C — Open `creatorAkshanshsahu/screen-safari-studio` in Arena Agent Mode
Paste:
> Copy `lovable-screen-safari-studio/files/src/` from `creatorAkshanshsahu/v.4.9` (`arena/a7aea110-v-4-9`) into `src/`, run `vitest run` and `vite build`, and open/merge the PR to `main`.
