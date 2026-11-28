# STREAMR Live TV API & Embeddable Player (`maxTV` + `StarShare`)

Self-contained web gateway that turns **`maxTV` (`http://new.maxtv.be/c` Stalker MAC portal)** and **`StarShare` (`http://livebox.pro:8080`)** into:
1. **Embeddable `<iframe>` Live TV Players** (`/embed/:channelId`) with **`mpegts.js` + `hls.js`** + **Hindi-first Audio Track Switcher**
2. **CORS-enabled JSON Channel API** (`/api/channels`) for custom UIs on external websites (such as the **OG Website**)
3. **Dynamic M3U Playlist Endpoint** (`/api/playlist.m3u`) that converts `maxTV` into a standard `#EXTM3U` playlist
4. **1-Line Drop-In `<script>` Widget** (`/embed-sdk.js`) to embed a full searchable Live TV grid + player on any webpage

Why a lightweight gateway is needed for `maxTV`:
- Stalker portals require STB `handshake` + `Cookie: mac=...` + `MAG200` `User-Agent` and issue short-lived tokens per tune via `create_link`.
- Upstream streams are raw HTTP (`http://...:8080/...`) `MPEG-TS` or `HLS`, which browsers block inside `https://` sites due to Mixed Content and missing CORS headers.
- `server.mjs` handles Stalker session tokens, `create_link` per tune, 302 edge redirects with `MAG200` headers, and HTTPS + CORS streaming automatically without exposing MAC addresses or credentials to the browser.

---

## Quick Start (Local / VPS)

```bash
cd web-embed
node server.mjs
# Listening on http://0.0.0.0:8787
```

## 1-Click Cloud Deploy (Render / Railway / Docker)

- **Render**: Create a new **Web Service** pointing to this repo with **Root Directory** = `web-embed`, **Start Command** = `node server.mjs` (or use `web-embed/render.yaml`).
- **Railway / Fly.io / Koyeb**: Point to `web-embed/Dockerfile`.
- **Docker**:
  ```bash
  docker build -t streamr-embed ./web-embed
  docker run -d -p 8787:8787 --restart unless-stopped streamr-embed
  ```

---

## OG Website Integration Guide

Replace `https://YOUR-LIVE-HOST` below with your deployed gateway URL.

### Option A — Embed Any Single Channel via `<iframe>`
```html
<iframe
  src="https://YOUR-LIVE-HOST/embed/st%3A1001"
  width="100%"
  height="100%"
  frameborder="0"
  allow="autoplay; fullscreen; encrypted-media; picture-in-picture"
  allowfullscreen>
</iframe>
```

### Option B — Fetch Channels via REST JSON API (Custom OG Website UI)
```http
GET https://YOUR-LIVE-HOST/api/channels?source=maxtv&filter=hindi&q=star
```
Query parameters:
- `source`: `maxtv` (default), `starshare`, or `all`
- `filter`: `hindi` (default: Indian Hindi + Cricket/Sports + Dual-Audio), `en_hi` (India + English), or `all`
- `category`: optional genre name (e.g. `SPORTS | CRICKET`)
- `q`: optional search query

Response shape:
```json
{
  "ok": true,
  "source": "maxtv",
  "filter": "hindi",
  "totalMatching": 245,
  "categories": ["SPORTS | CRICKET", "ENTERTAINMENT", "HINDI MOVIES", "NEWS"],
  "channels": [
    {
      "id": "st:1001",
      "source": "maxtv",
      "name": "Star Sports 1 Hindi HD",
      "category": "SPORTS | CRICKET",
      "logo": "http://new.maxtv.be/...",
      "embedUrl": "https://YOUR-LIVE-HOST/embed/st%3A1001",
      "iframeCode": "<iframe src=\"https://YOUR-LIVE-HOST/embed/st%3A1001\" ...></iframe>",
      "streamUrl": "https://YOUR-LIVE-HOST/api/stream/st%3A1001.ts"
    }
  ]
}
```

### Option C — Drop-In Full Live TV Grid + Player (`1-line <script>`)
```html
<div id="og-live-tv"></div>
<script
  src="https://YOUR-LIVE-HOST/embed-sdk.js"
  data-target="#og-live-tv"
  data-source="maxtv"
  data-filter="hindi">
</script>
```

### Option D — M3U Playlist URL for `maxTV`
```text
https://YOUR-LIVE-HOST/api/playlist.m3u?source=maxtv&filter=hindi
```
