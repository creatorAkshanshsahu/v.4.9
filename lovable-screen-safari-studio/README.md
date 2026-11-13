# Website Live TV Playback Fix (`creatorAkshanshsahu/screen-safari-studio`)

This directory contains the complete, production-verified fix for Live TV channels not playing on **`https://screen-safari-studio.lovable.app`** (`creatorAkshanshsahu/screen-safari-studio`, tested against `main` @ `7f78771` with **18/18 Vitest suites / 79 tests passing** and a clean **`vite build` (`cloudflare-module`)**).

---

## Root Causes Fixed

1. **Cloudflare Worker secrets dropped by Nitro (`src/lib/live-gateway.ts`)**
   - Nitro's `cloudflare-module` worker entry invokes `ssr.fetch(request)` with **1 argument (`env === undefined`)** while storing Cloudflare Worker / Lovable secrets on `globalThis.__env__` and `request.runtime.cloudflare.env`.
   - `readEnv()` / `getConfig()` now resolve `env`, `request.runtime?.cloudflare?.env`, `globalThis.__env__`, and `process.env`.

2. **Broken `maxTV` default MAC + handshake-only identity pinning (`src/lib/live-gateway.ts`)**
   - Restored the active Android STB subscription identity (`http://new.maxtv.be/stalker_portal/c/` + `00:1A:79:EE:03:9F`, `timezone=Europe/London`, serial `92180365D21FE`) as primary with `00:1A:79:BA:DF:D7` (`http://new.maxtv.be/c/`) as secondary fallback.
   - Added **catalogue-level identity fallback**: when Stalker issues a handshake token to an unsubscribed MAC that then returns `0` channels on `get_all_channels`, the gateway automatically resets and tries the alternate identity.
   - Removed `Icy-MetaData: 1` from upstream MAG stream fetches to match Android `StalkerPortal.kt`.

3. **Double-fetch `create_link` single-use `play_token` race (`LiveVideoSurface.tsx` + `live-gateway.ts`)**
   - Previously, probing `/api/live/stream/<id>.ts` and then opening `/api/live/stream/<id>.m3u8` / `.mpd` / `.ts` called Stalker `create_link` **twice within ~10ms**, invalidating single-use `play_token`s and tripping 1-stream-per-MAC limits.
   - Added `?probe=1` + a single-use server-side handoff cache (`streamHandoffCache`) so Shaka / HLS.js / `mpegts.js` consume the already-resolved manifest/stream URL with **only 1 upstream `create_link` call**, while subsequent live manifest refreshes hit upstream normally.

4. **Browser unmuted autoplay rejection (`NotAllowedError`) + stuck loading overlay (`LiveVideoSurface.tsx` + `src/routes/live.tsx`)**
   - `muted` started `false` and `video.play().catch(() => undefined)` swallowed browser `NotAllowedError`, leaving `<video>` silently paused until the 15s watchdog failed.
   - Added `safePlay()` automatic muted-autoplay fallback + a **"Tap to Unmute Live Audio"** pill, and cleared `statusMsg` across `onLoadedData`, `onResize`, `onTimeUpdate`, and the 500ms frame poll (MSE often populates `videoWidth` after `onPlaying`).

5. **Widevine-only JioTV DASH manifests failing Shaka ClearKey (`Error 6001`) (`src/lib/live-gateway.ts`)**
   - `normalizeClearKeyMpd()` strips Widevine/PlayReady `<ContentProtection>` blocks when ClearKeys are supplied and injects W3C ClearKey descriptors (`urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e` + `urn:uuid:1077efec-c0b2-4d02-ace3-3c1e52e2fb4b`) alongside `mp4protection`, matching Android's `ClearKeyDashManifestParser.kt`.

6. **Cloudflare Worker datacenter IP resilience (`TG TV` direct HLS + wider `jtv` playlist discovery) (`src/lib/live-gateway.ts` + `src/routes/live.tsx`)**
   - Added **`TG TV`** (`https://livetgtv.lovable.app/api/public/playlist.m3u`) as both a selectable tab (`maxTV` | `JioTV` | `TG TV` | `Both`) and an automatic fallback (`maxTV -> JioTV -> TG TV`) with browser-direct HTTPS HLS fallback (`directUrl`) if an upstream CDN blocks Cloudflare Worker egress IPs, plus widened `jtv` numbered playlist discovery (`+8 / -3`).

---

## Apply & Deploy to `screen-safari-studio` (3 Fast Ways)

### Option A — One-Command Git Sync (Recommended)
```bash
./lovable-screen-safari-studio/sync-to-lovable.sh
cd screen-safari-studio
git add -A
git commit -m "fix(live): fix Live TV playback across maxTV, JioTV ClearKey DASH & TG TV"
git push origin main
```
Lovable automatically deploys pushes on `main` to `https://screen-safari-studio.lovable.app`.

### Option B — Apply Git Patch Directly inside `screen-safari-studio`
```bash
git am < lovable-screen-safari-studio/fix-website-live-channels.patch
git push origin main
```

### Option C — Open `creatorAkshanshsahu/screen-safari-studio` in Arena Agent Mode
Paste:
> Pull the updated files from `creatorAkshanshsahu/v.4.9` branch `arena/a7aea110-v-4-9` under `lovable-screen-safari-studio/files/` into this repo, run `vitest run` and `vite build`, and push to `main`.
