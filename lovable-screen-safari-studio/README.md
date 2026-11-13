# Lovable Website Integration (`creatorAkshanshsahu/screen-safari-studio`)

This directory contains the complete, build-verified integration for your Lovable STREAMR website (`https://github.com/creatorAkshanshsahu/screen-safari-studio`), built natively for its **TanStack Start + Vite 8 + Nitro (Cloudflare Workers)** runtime and following every rule in `AGENTS.md`.

---

## What Changed in `screen-safari-studio`

| File | Purpose |
| --- | --- |
| `src/lib/live-gateway.ts` | 100% Cloudflare-Workers-safe (`fetch` + `ReadableStream` + `crypto.subtle`, zero `node:*` imports) `maxTV` Stalker (`http://new.maxtv.be/c` + `00:1A:79:BA:DF:D7`) & optional `StarShare` gateway. Serves `/api/live/channels`, `/api/live/playlist.m3u`, `/api/live/stream/:id`, `/api/live/segment`, `/embed/:id`, and `/embed-sdk.js` directly from your `*.lovable.app` domain over HTTPS with CORS + `frame-ancestors *`. |
| `src/server.ts` | Routes `/api/live/*`, `/embed/:id`, and `/embed-sdk.js` through `maybeHandleLiveRequest(request, env)` before TanStack SSR—without touching generated `src/routeTree.gen.ts`. |
| `src/components/tv/LiveVideoSurface.tsx` | Browser live player powered by `mpegts.js` + `hls.js` with container auto-detection (`X-Stream-Container`), automatic **Hindi (`hin` / `hi`)** audio-track preference, and 1-click token refresh. |
| `src/routes/live.tsx` | Replaces the static gradient placeholder with real `maxTV` / `StarShare` live playback, `Indian Hindi` / `India + Eng` / `All` filters, category chips, live channel search, audio selector, and an **OG Embed & API** drawer with 1-click copy snippets. |
| `src/components/tv/ChannelRail.tsx` | Populates the Home screen **Live TV · maxTV** rail from `/api/live/channels` while preserving `tv-focus` directional remote navigation. |
| `public/vendor/mpegts.js` & `public/vendor/hls.min.js` | Self-hosted playback engines so neither `/live` nor `/embed/:id` depends on external CDNs. |
| `src/test/live-gateway.test.ts` | Vitest suite verifying JSON API CORS, M3U generation, and `<iframe>` embed headers. |

---

## Syncing to Lovable (`creatorAkshanshsahu/screen-safari-studio`)

*(Lovable two-way syncs automatically whenever commits land on `main` of `creatorAkshanshsahu/screen-safari-studio`.)*

### Option 1 — One-Command Git Patch / Copy

```bash
chmod +x lovable-screen-safari-studio/sync-to-lovable.sh
./lovable-screen-safari-studio/sync-to-lovable.sh /path/to/screen-safari-studio
cd /path/to/screen-safari-studio
git add -A
git commit -m "feat(live): integrate maxTV & StarShare Live TV + OG embed API"
git push origin main
```

Or apply the git patch directly inside `screen-safari-studio`:

```bash
git am < /path/to/0001-feat-live-integrate-maxTV-StarShare-Live-TV-player-g.patch
git push origin main
```

### Option 2 — Optional Lovable Secrets (for StarShare or Custom maxTV MAC)

Per Lovable's `AGENTS.md`, no credentials are hardcoded in the repository:
- **`maxTV`** works out-of-the-box with `http://new.maxtv.be/c` (`00:1A:79:BA:DF:D7`), or can be overridden via Lovable Secrets `MAXTV_PORTAL` and `MAXTV_MAC`.
- **`StarShare`** activates automatically when you add `STARSHARE_USERNAME` and `STARSHARE_PASSWORD` (and optional `STARSHARE_PORTAL`, default `http://livebox.pro:8080`) in **Lovable → Cloud → Secrets**.

---

## OG Website Embed & API Endpoints (Once Deployed on `https://<your-app>.lovable.app`)

1. **Single-Channel `<iframe>` Embed**
   ```html
   <iframe
     src="https://<your-app>.lovable.app/embed/<CHANNEL_ID>"
     width="100%"
     height="540"
     style="border:0;border-radius:12px;aspect-ratio:16/9;background:#000"
     allow="autoplay; fullscreen; encrypted-media; picture-in-picture"
     allowfullscreen
   ></iframe>
   ```

2. **Full Live TV Grid + Player Widget (1 `<script>` tag)**
   ```html
   <div id="og-live-tv"></div>
   <script
     src="https://<your-app>.lovable.app/embed-sdk.js"
     data-target="#og-live-tv"
     data-source="maxtv"
     data-filter="hindi"
   ></script>
   ```

3. **Channels JSON API (CORS `*`)**
   ```text
   https://<your-app>.lovable.app/api/live/channels?source=maxtv&filter=hindi
   ```

4. **Dynamic M3U Playlist**
   ```text
   https://<your-app>.lovable.app/api/live/playlist.m3u?source=maxtv&filter=hindi
   ```
