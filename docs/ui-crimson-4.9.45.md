# 4.9.45 — glossy crimson identity, new AK mark, Sony Live shelf

## What the user asked for

1. A whole new UI and logo, in **red / crimson / glossy red**.
2. Redesign of the **movie/series player** and the **live channel player**.
3. A **new section in Live TV** for the channels at
   `https://allinonereborn2.online/sony/`, playable from inside the app.

## The Sony page: what it is, and what could honestly be built

The page is a grid of tiles. Every tile links to
`suniliv.pages.dev/ptest1.html?id=<slug>` (`sony-hd`, `sony-max`, `sony-ten-3`, `sab-hd`, …).

That player is **not** a native source:

- It renders through Media Source Extensions; the `<video>` element is bound to a `blob:` URL, so
  there is no `.mpd` or `.m3u8` in the page to read.
- Reading the manifest out of it would mean **executing a third-party player**, which this app
  deliberately never does. It runs Shaka plus a large ad/identity-sync payload.
- The slugs name ordinary Sony channels that the app's own providers already carry under standard
  Jio channel IDs, with the same logos and names.

So the section is built the way the rest of Live TV works: **native channels on the existing
TG/GioTV ClearKey path**. Nothing is scraped, no web player is opened, no credential is borrowed, and
no channel is remapped by name.

### Selected by name, not by guessed IDs

The first attempt hard-coded 22 Jio channel IDs inferred from a different provider's list. A live
check against the exact feed the Live tab loads proved it wrong:

| Checked | Result |
|---|---|
| IDs that resolved to the intended Sony channel | **1 of 22** (`872` → Sony Yay) |
| IDs that resolved to a different broadcaster | **14** (Star Sports, Star Gold, Maa HD/Movies, Rozana Spokesman 24x7) |
| IDs the feed does not carry at all | **7** |

A wrong ID is not cosmetic here: the shelf would have shown Star Sports tiles under a Sony heading,
and tapping one would have played the wrong channel.

`LiveCollections.SONY` therefore carries **no IDs at all**. It selects from the loaded catalogue by
the provider's own channel name, in the Sony page's tile order (longest matching token wins, so HD
leads its plain tile and `Sony MAX2` is not confused with `Sony Max`). Verified against the live feed
on 2026-11-03: **24 Sony channels selected out of 1255**, in page order, from

`SET HD`, `Sony SAB HD/SD`, `Sony Marathi SD`, `Sony Pal`, **`Sony aath`**, six Sony YAY feeds,
`Sony Max HD`, `Sony MAX1`, `Sony Wah`, `Sony MAX2`, `Sony Pix HD`, `Sony Ten 1/2/3 HD` and
`Sony BBC Earth HD` (English, Tamil, Telugu).

Two earlier caveats are now **resolved by evidence**, not assumed:

- *Sony AATH is missing upstream* — false. The feed carries `697 · Sony aath`, and it is on the shelf.
- *Sony Ten 3 HD is 892* — correct. The guessed `1111` is `Maa Movies`.

`SonyLIV` sports streams are deliberately **excluded**: they are app-only, not on the Sony page, and
not broadcast feeds.

Any channel that is not advertised is simply not shown. A bad list degrades to "no row", never a row
of dead tiles.

## What changed

### Colour

The aqua/indigo palette is replaced by one crimson family:

| Token | Value | Use |
|---|---|---|
| `ABYSS` / `abyss` | `#07040A` | app background |
| `SURFACE` / `SURFACE_HI` | `#17101B` / `#241528` | cards, panels |
| `CRIMSON` | `#E11D2E` | primary accent |
| `CRIMSON_BRIGHT` | `#FF3448` | focus rims, LIVE badge, progress |
| `CRIMSON_DEEP` | `#8C0F1C` | gradient depth |
| `WINE` | `#4A0812` | quiet surfaces |
| `GLOSS_HI` | `#FFEBEE` | specular highlight |

Gloss is a **three-stop lacquer gradient** (bright → primary → deep) with a thin near-white rim,
applied to focus fills, the LIVE badge, player action buttons, poster cards, the guide panel and the
hero glows. Old token names (`signal`, `ion`, `live`, …) are kept as aliases so layouts, players and
tests keep working against one source of truth.

### Logo

A new **interlocking AK**: the K's stem bites into the A's right leg so the mark reads as one shape,
with a lacquered crimson gradient and a specular edge.

`tools/brand_mark.py` is the single source of truth. It emits **both**:

- `res/drawable/aktv_mark_cinematic.xml` (vector, what the TV draws), and
- the PNG previews and the launcher / banner bitmaps via `tools/build-brand-assets.py`.

Having one geometry for both is the fix for the old pipeline, where a blurred raster was converted
into vector paths and the result drifted from the artwork. The previous mark was also cyan, so every
brand surface would have clashed with the new crimson.

`aktv_logo_cinematic.xml` (the hero plate and wordmark) is recoloured from the same palette, and the
launcher, round icon, adaptive foreground and Android TV banner are regenerated.

### Players

- **Live**: programme progress is a crimson bar over a crimson track (it was tinted mint), the info
  card is warm wine with a crimson rim, and the guide/scrim washes are re-derived from ink rather
  than navy.
- **Movies / series**: the time bar is crimson, action buttons use the lacquer gradient when
  focused, and the top/bottom scrims are warm ink so the controls sit on the crimson identity
  instead of a navy one.

### Tints

Channel tiles previously took a random hue: `fromName` spanned the whole colour wheel and
`normalize` pushed sampled logo colours to full saturation. A screen of tiles therefore read as a
rainbow. Both now stay inside the red band — sampled hues are pulled 72% toward crimson, and
generated hues span crimson → rose → warm wine. `AccentIdentityTest` locks this: every tint, for any
input hue, must land in the red family.

## Verification

- `LiveCollectionsTest` — unique IDs, editorial order preserved, unknown IDs dropped (not
  substituted), empty catalogue yields no row, V2 IDs can never enter a V1 shelf.
- `AccentIdentityTest` — primary is saturated crimson; tints for red/green/blue/cyan/amber/violet
  inputs all land in the red family; name tints are stable and warm; hero partners stay dark.
- `BrandPreviewTest`, `HomePreviewTest`, `PlayerPreviewTest` render the real views to
  `design/previews/4.9.45/` in CI, so the design can be reviewed without flashing a TV.
- Playback contracts are untouched: `check-playback-baseline.py` still verifies all 16 policy files,
  and this release changes no network, DRM or source-resolution code.

## Verification

| Check | Result |
|---|---|
| Offline unit tests, arm64 | **376 run, 370 passed, 6 skipped, 0 failed** |
| Offline unit tests, arm32 | **376 run, 370 passed, 6 skipped, 0 failed** |
| Sony shelf against the live feed | **24 selected / 24 named correctly** (`sony shelf selects advertised Sony channels by name`: passed) |
| APK validation | ZIP, both ABIs, SHA256 — passed |

The live resolver case *"discovers current servers and inspects actual manifests"* flips between
passed and failed across runs because individual provider tokens expire upstream; it is reported,
never asserted, and the shelf check is independent of it.

## What is still unverified

**A rendered frame on the physical TV.** No runner decodes video. The Sony channels use the same
native path as every other channel in the app, and the feed side is verified, but the final proof is
one Sony channel playing on the device. If one fails, *Playback details → Copy redacted report*
names the provider and HTTP code.

## Branch history note

This branch shares no commit history with `main`, so a GitHub pull request from it reports
`CONFLICTING` even though every changed file applies cleanly, and pull-request-triggered workflows
never start. Two consequences:

- Verification runs through push-triggered workflows: `Verify` (offline tests, both ABIs, APK
  validation) and `UI preview` (real rendered screens).
- Landing this work means re-applying the commit range onto `main`, not a plain merge. The changes
  are ordinary file edits, so that is mechanical.

## Previews

`design/previews/4.9.45/` holds screens rendered by the real Android views in CI, not concept art:

| File | What it shows |
|---|---|
| `home-tv.webp` | Live TV: crimson nav, hero, Live Channels with harmonised tile tints |
| `home-phone.webp` | the phone layout at the same state |
| `home-movies.webp` | the Movies tab |
| `live-player.webp` | live channel player chrome |
| `vod-player.webp` | movie/series player chrome |
| `entrance.webp` | the brand reveal |
| `brand-mark.webp` | the new mark on the real themed background |
