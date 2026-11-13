# STREAMR WEB

A player site, an embed route, and an ad layer — the web counterpart of the app, for content you are
licensed to distribute.

```
web/
  index.html        channel grid
  watch.html        full player + related channels + embed snippet
  embed.html        minimal player for partner iframes
  assets/config.js  the only file you edit to go live
  assets/player.js  HLS / DASH / progressive, Widevine passthrough
  assets/ads.js     VAST 2/3 linear pre-roll + house bumper + companion slot
  assets/watch.js   tune flow: resolve → pre-roll → stream
  sources.sample.json  the JSON contract for STREAMR.sourcesUrl
  _headers          response headers (Netlify / Cloudflare Pages)
  serve.py          local preview with the same headers
```

Run it locally:

```bash
cd web && python3 serve.py            # http://localhost:8080
EMBED_FRAME_ANCESTORS="'self' https://partner.example" python3 serve.py   # narrower framing
```

The three built-in channels are Blender Foundation test films (CC BY) so the player, the pre-roll and the
embed all work before you have any content of your own. They are the only sources this build knows.

## Going live

1. **Point at your catalogue.** Set `STREAMR.sourcesUrl` in `assets/config.js` to an endpoint you control
   that returns `{"channels":[ … ]}` — shape in `sources.sample.json`. Serve it from your own backend, with
   short-lived playback tokens if the source needs them. The browser never talks to a portal directly.
2. **Set the ad tag.** `STREAMR.ads.adTagUrl` takes a VAST 2.0/3.0 linear tag. Your ad server **must** send
   `Access-Control-Allow-Origin` for your site, otherwise the XML cannot be read and the house bumper plays
   instead. Non-linear/companion-only tags are skipped on purpose: the pre-roll rides the same `<video>`, so
   a played ad can never be behind a playable stream.
3. **Decide who may embed.** `_headers` → `frame-ancestors` on `/embed.html`. Only the response header
   counts; `STREAMR.embed.allowedParents` is documentation for the same list. `index.html` is
   `frame-ancestors 'none'`, so nobody can wrap the site itself.
4. **Deploy** to any static host (Cloudflare Pages, Netlify, Vercel, S3+CDN). No build step, no Node needed
   at runtime, no secrets in the repo — anything private belongs in the endpoint behind `sourcesUrl`.

## What the ad layer does and does not do

It counts **one impression per tune**, fires the `<Impression>` plus start/quartile/complete/skip and
click-tracking URIs, opens the click-through, and skips a skippable creative after `skipAfter` seconds. It
never blocks the channel: a missing tag, a 404, a CORS failure or an unplayable creative falls through to
the house bumper and then the stream. It does not bid, fill, or report to an exchange — that is the tag's
job.

## Money, honestly

Ad networks with real CPMs audit traffic sources; a player showing channels you have no distribution rights
to gets the account terminated and paid revenue clawed back, and hosting providers pull the site on the
first complaint. The player above is built so that the licensing question is the *only* thing left open:
give it a catalogue you hold rights to and everything else already works.

## Embedding someone else's player, or having yours embedded

A page can only appear in an iframe if the **host** adds it, and only if the response headers allow it. You
control your own headers (`_headers`); you cannot insert your player into a site you do not run. If a
partner wants your player, give them:

```html
<iframe src="https://your.site/embed.html?id=news-1"
        width="960" height="540" frameborder="0"
        allow="autoplay; fullscreen; encrypted-media"
        referrerpolicy="origin"></iframe>
```

Their page loads your player, your ads run inside it, and the impression is yours. The reverse — your site
wrapping their stream — needs their header to allow you, which is their call and their licence.
