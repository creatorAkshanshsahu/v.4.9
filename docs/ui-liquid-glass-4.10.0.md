# 4.10.0 — "Aurora Glass": liquid-glass UI, new AK TV logo, a livelier app

Branch: `liquid-glass-redesign` (based on `main` @ 4.9.45). UI and branding only: no stream source,
API, channel list, resolver, DRM or playback policy was changed.

## New identity

- **Logo**: a liquid-glass squircle washed in a rose → orchid → iris aurora, carrying a bold white
  **AK** ligature. The A's counter is a ▶ play triangle, so the aurora glows through the letter.
- **Single source of truth**: `tools/brand_mark.py` emits the vector drawables (`aktv_logo`,
  adaptive `aktv_icon_fg/bg/mono`) *and* renders the TV banner + `design/` PNGs from the same
  coordinates (`python3 tools/brand_mark.py all`, needs Pillow for `raster`).
- **Launcher**: adaptive icon with a themed (Android 13+) monochrome layer; new 320×180 TV banner.
- Removed 8 unused PNGs (~800 KB of APK weight) and 5 unused drawables from the old identities.

| Token | Value | Use |
|---|---|---|
| `INK` (`ABYSS`) | `#060812` | app background |
| `ROSE` (`SIGNAL`) | `#FF4F7B` | brand primary, warm end of the aurora |
| `ORCHID` (`ION`) | `#B65CFF` | aurora middle |
| `IRIS` (`INDIGO`) | `#6366FF` | cool end of the aurora |
| `LIVE` | `#FF3D5E` | live dot / badge |
| glass | white @ 8–24 % | translucent surfaces, rims, specular |

Old token names are aliases, so non-UI code is untouched.

## Liquid glass (no blur pass)

`UiKit.kt` adds one material, used everywhere:

- **`GlassDrawable`**: translucent body, specular top lens, gradient rim light, optional aurora fill on
  focus, a one-shot light sweep when focus arrives. State changes animate (200–260 ms), so focus melts
  between states. No `RenderEffect`/blur, so it stays smooth on 32-bit TV boxes (minSdk 28).
- **`Glass.panel / card / pill / primary`** factories: the rail, info card, guide, buttons, chips,
  keys and tiles all share the same look.
- XML glass for selector-driven views: `player_action`, `live_pill`, `guide_panel`, `dialog_glass`,
  `search_field`, `focus_ring`.

## A more alive app

- **Living aurora** (`AuroraView`) behind home, search and details: four colour fields drift on slow
  orbits. It is rasterised at 1/8 resolution and upscaled (one full-screen texture per frame, ~24 fps),
  pauses behind playback / when hidden, and is static when system animations are off.
- The aurora **leans toward the focused channel's colour**, and toward warm/cool for Movies/Series.
- **Springy focus**: an overshoot lift with a glow in the item's own colour (`Motion.focus`), lighter on
  low-RAM devices.
- **Staggered entrances** for shelves on every tab switch / catalogue load, and for the details page.
- **Breathing LIVE dot** (`PulseDotDrawable`) in the hero and the live player.
- **Ken Burns settle** on movie/series backdrops; glass fade/scale **activity transitions**.
- **New entrance**: aurora bloom → glass tile springs in → light sweep → "AK TV" tracks in, with an
  aurora loading bar. Same 1.8 s timing, skip, sound toggle and reduced-motion behaviour as before.
- Time-of-day greeting on the rail profile.

## Layout

- The navigation rail is a **floating glass panel**; hero artwork and the aurora run full-bleed
  underneath it.
- Fixed (pre-existing): on compact/phone screens the channel monogram overlapped the channel name.

## Verification

- `gradle testArm64DebugUnitTest`: 377 tests, 0 failures (6 skipped live-network tests). Identity
  and layout tests were rewritten for the new palette and full-bleed hero, not deleted.
- `assembleArm64Debug` / `assembleArm32Debug` build; previews in `design/previews/4.10.0/` are
  rendered from the production views by Robolectric (sample content, not playback captures).
- CI: `.github/workflows/redesign-apk.yml` builds both debug APKs on every push to
  `liquid-glass-redesign` (never `main`) and uploads them as the `AK-TV-Redesign-APK` artifact.
