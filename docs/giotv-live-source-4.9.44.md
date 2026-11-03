# 4.9.44 — second live provider moved from JJTV to GioTV

## What the user asked

Replace the channel streaming source: stop using `jjtvxweb.pages.dev` and use
**https://giotv-6op.pages.dev/** instead, so the channels work again.

## Why the old source stopped working

A read-only probe of both sites, run in CI on 2026-11-03
([run 41582900672](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/41582900672), inspect job),
found a hard upstream failure, not an app regression:

| Site | `/pind?id=872` | `/` | Catalogue |
|---|---|---|---|
| `jjtvxweb.pages.dev` (old) | **HTTP 451, 0 bytes** | 200, but a channel-grid landing page with no player | none |
| `giotv-6op.pages.dev` (new) | **HTTP 200, 25,701 bytes** | 200 | `/jstr4web.json`, 1,176 channels |

HTTP 451 is the decisive change. The old resolver was not being refused a specific request — the
old deployment no longer serves a native player at all. No retry, request style, credential refresh
or channel-specific ordering in this app can fix that.

## Why the replacement is a drop-in, not a rewrite

The new deployment advertises the **same player contract** the resolver already reads:

| Constant | Old value family | New value observed |
|---|---|---|
| `CHANNELS_URL` | same-site `jstr4web.json` | `https://giotv-6op.pages.dev/jstr4web.json` |
| `COOKIE_PRIMARY` | cross-origin cookie JSON | `https://allinonereborn2.online/jstrweb2/cookies.json` |
| `COOKIE_FALLBACK` | optional | **absent** (already supported) |
| `COOKIE_STAR_SPORTS` | cross-origin Star JSON | `https://allinonereborn2.online/jtv-fetch/jstarcookie/cookie.json` |

The catalogue also matches the documented shape:

- 1,176 entries; `id` is a **string**; `name`, `category`, `logo`, `url` on every entry.
- One `url` per channel. No `url1`/`url2`/`url3` on the sampled entries — the resolver already
  treats those as optional.
- `keys` array (2 pairs) on 69 entries; legacy `keyId`/`key` on 1,107. Both forms are already
  supported, and `keys` still takes precedence.
- Channel IDs are unchanged Jio V1 IDs: 165, 816, 872, 1106, 1108 and 362 are all present and map
  to the same `bpk-tv/...` paths on `jiotvmblive.cdn.jio.com` and `jiotvpllive.cdn.jio.com`.
  So 872 is still Sony YAY Hindi and still on the mobile CDN.

The Star cookie document returns 174 records with the same `channel_id` / `error_details.final_url`
shape the resolver's `starCookie` selector expects, and the primary document returns a live token
with ~14 h remaining at probe time.

## Changes

1. **`JjtvResolver.SITE`** — `https://jjtvxweb.pages.dev` → `https://giotv-6op.pages.dev`. This is the
   only functional line in the resolver; request construction, discovery, cookie selection, caching,
   DRM handling and limits are untouched.
2. **User-facing provider name** — a single `LiveProviders.displayName()` now supplies "GioTV"
   in the MENU source choice, the provider picker and the error text that used to say "JJTV".
   The internal `LiveProvider.JJTV` enum name is deliberately kept: it is a stored preference value
   and a diagnostic tag, and renaming it would churn persistence for no user-visible gain.
3. **Stale provider hints** — `TgServers.forgetPreviousLiveSite()` clears `source_provider_*`,
   `source_*` and `native_compat_*` once per install when the configured live site changes. Those
   hints name the old site and can never match a source from the new one; without this, an upgraded
   TV would keep preferring an unreachable provider for channels it had previously played. Favourites,
   recents, previews and the verified TG function id are preserved, and no credential was ever stored
   in preferences.
4. **Version** — `4.9.44`, code `61`.

## Baseline amendment

`docs/playback-baseline-4.9.18.json` pins the SHA-256 of the 16 request/source/DRM/VOD files, so the
site constant change is recorded there explicitly rather than silently overwritten. Three files are
amended with the reason and the exact unchanged scope:

- `JjtvResolver.kt` — site constant only.
- `TgServers.kt` — additive cleanup helper; resolve/invalidate/refresh unchanged.
- `LiveProviders.kt` — additive display name; ordering and fingerprint unchanged.

`python3 .github/scripts/check-playback-baseline.py` passes.

## What is verified and what is not

Verified: the new site serves its player page to a plain HTTPS client with no challenge, advertises
all three constants the resolver requires, and its catalogue contains the exact channel IDs with
`url`/`keys`/`keyId` in the shapes the resolver already parses. Offline unit tests cover the
catalogue fixture, the constant parsing, the key precedence and the preference migration.

Not verified: **actual playback on the TV.** No CI runner or sandbox can render a stream, and a
200 metadata response is not a rendered frame. The live contract test
`replacement live site still advertises the native player constants` checks the real page through
the app's own HTTP and parsing code, but a rendered frame still has to be confirmed on the device.

## How to test on the TV

1. Install the 4.9.44 APK and confirm the version on the home screen.
2. Open 872 (Sony YAY). It starts on the new source; the MENU source choice reads "Use GioTV only".
3. If it plays, the migration is confirmed. If it fails, open **Playback details → Copy redacted
   report** — the provider line will now name GioTV and the HTTP code will identify whether the new
   host, its cookie document or the Jio CDN refused the request.
