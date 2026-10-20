# AK TV 4.9.24 — UI direction candidates (awaiting the user's pick)

Status: **concepts only.** No UI code has been written for the redesign yet. The five
directions below are rendered by `tools/render-ui-directions.py` (Pillow, the app's real
Sora typefaces and the selected AK mark) into `design/previews/`. Like the existing
previews they use explicitly labelled sample content — fictional poster titles and
sample captions — and involve no network access and no decoded playback. They are
design-direction concepts, not screenshots of the running app.

All directions keep the locked identity: midnight `#080D14`, aqua `#32E5CE`,
ice-white `#EFF6FA`, AK TV branding, and the TV vertical navigation rail
(Live / Always on / Movies / Series, icon + label) with horizontal pills on phones.

| # | Direction | TV home | Extra study | Idea in one line |
|---|---|---|---|---|
| 1 | Rail-led cinematic | `direction-1-rail-cinematic-tv.webp` | `direction-1-rail-cinematic-player.webp` | Blurred backdrop depth, glass hero, glowing rail plate; atmosphere first |
| 2 | Grid-forward editorial | `direction-2-grid-editorial-tv.webp` | `direction-2-grid-editorial-movies.webp` | Magazine grid: numbered sections, hairline rules, rank numerals behind posters |
| 3 | Glass | `direction-3-glass-tv.webp` | `direction-3-glass-player.webp` | Frosted panels and a floating dock over an aurora field; softest of the five |
| 4 | Bold typographic minimal | `direction-4-typographic-minimal-tv.webp` | — | No boxes: giant channel words, focus is type weight + one aqua bar + a thumbnail |
| 5 | Broadcast deck | `direction-5-broadcast-deck-tv.webp` | — | EPG-first control room: channel rows with now/next progress, focused row expands |

Shared spec elements already expressed in the concepts: 16:9 live cards with logo,
LIVE badge and now-playing caption; focus scale/ring with soft aqua glow instead of a
flat border; 2:3 poster cards with one-line title + year/rating; ranked numerals behind
the first Movies row (direction 2 study); one white primary pill + ghost secondary
pills; player overlay as an auto-hiding HUD (channel strip, now/next, programme
progress, Source / Quality / Retry quick actions) that never blocks the first frame.

## Low-end TV box notes (32-bit armeabi-v7a)

- Directions 1 and 3 lean on blurred backdrops. In the app these must be pre-blurred
  bitmaps or view-property crossfades, never per-frame blur passes; the renders are
  static illustrations of the end state.
- Direction 4 is the cheapest to render (flat colours, type only); direction 5 the
  cheapest that still shows schedule depth.
- Whichever direction wins, motion stays view-property animations and crossfades per
  the standing constraint.

## What the app actually has for programme data (spec item 7)

Confirmed before promising depth: `Epg.kt` parses a **real schedule feed**,
`GET {site}/api/public/guide/{id}` — `nowPlaying`, `upNext`, `upcoming` plus a full
`guide` array with start/stop unix seconds, provider display strings, description,
category and image. So a now/next panel with timeline, progress and description is
backed by genuine schedule data, with these honest limits:

- **V1 channels only** (`programme details (V1)`); the 24/7 (v2) catalogue has no guide,
  so those rows must degrade to "no programme details" rather than fake a timeline.
- The feed is cached 45 s per channel and the parsed `upcoming` list is capped at four
  entries — a full day grid would need repeated fetches or a longer cache.
- Today only the home hero consumes it (`scheduleHeroGuide` / `applyHeroGuide`); the
  redesign would extend the same parsed `Guide` object to rows/decks/overlays, which is
  a UI change, not a new data source.

## Playback note carried into this build

`RequestProfileFallback.preferredIndex` was audited against the reported "-1 index"
bug: the shipped 4.9.23 code (`ec9edbf`, byte-identical to this tree before the change)
already clamped the no-alternative path with `coerceAtLeast(0)`, so a negative index
could not reach the callers. The clamp is now explicit and documented, and two unit
tests pin the single-request-style (TG) case that previously had no coverage:
`preferredIndex` must return a usable index (0) when the provider advertises one style
and nothing matches the previous identity. No behaviour change; the 4.9.18 baseline
guard still verifies all 16 contract files.

## Decision workflow

1. User reviews the renders (gallery or files in `design/previews/`).
2. User picks one direction (or mixes elements, e.g. "rail from 1, grid from 2").
3. Implementation lands in the same combined build as the playback hardening,
   version 4.9.24+, then CI: baseline guard, offline unit tests for both ABIs,
   native TV/phone layout previews.
