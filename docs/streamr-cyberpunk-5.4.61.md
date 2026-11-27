# STREAMR 5.4.61-cyber — cyberpunk redesign + playback fixes

Branch `streamr-aurora-integration-5.4.61`. Name, package, signing key and versionCode (180) are
unchanged, so the APKs install over STREAMR. The creator credit "BY AKSHANSH SAHU" is in the new
wordmark and typed by the new entrance.

## Playback fixes

### maxTV switched to a different server after ~10 minutes
Two independent causes:
1. **Wrong refresh path.** Portal (maxTV/Fusion) and StarShare tunes set `currentNativeProvider` to the
   `JIOM3U` placeholder. When the portal's play token expired, the stream answered HTTP 401/403 and the
   site-provider credential refresh ran `LiveMetadataRequests.fetch(JIOM3U, <portal channel number>)` —
   asking the **JioTV playlist** for the channel. That either played a different stream or fell through
   to a Fusion/StarShare twin. Portal/StarShare errors now go to `SubscriptionRelink`: the same
   subscription is asked for a fresh link (`create_link`), only after the channel has played, at most 4
   times in a row with back-off, and a 30-second stable stretch restores the budget — so a channel can
   run for hours across any number of token expiries. Start-up failures keep the normal fallback chain.
2. **No keep-alive.** A MAG box sends `watchdog/get_events` every couple of minutes while it plays;
   portals expire the session (and the link) without it. `StalkerPortal.watchdog()` now runs every
   ~110 s on the session that issued the playing link.

Also: Fusion links were sent with maxTV's origin; they now use Fusion's.

### StarShare video lagged while audio was smooth
A 12-second data hiccup tripped the `VIDEO_STOPPED` watchdog, which rebuilt the player in the
"compatible" profile — **software decoder, capped at 480p / 1.5 Mbps** — for HD MPEG-TS. The TV box
could not software-decode the stream in real time, so video dropped frames while audio played on.
Single-rendition portal/StarShare streams now reconnect instead, never take the software-first
profile, and neither save nor obey a stale compatibility flag (Media3's decoder fallback still covers a
hardware decoder that genuinely fails to start).

The live and movie players now show a **DROP n** dropped-frames counter in the HUD telemetry, so any
remaining stutter on a particular channel can be told apart from network buffering.

Tests: `SubscriptionRelinkTest`, a watchdog/session test in `StalkerPortalTest`.

## Cyberpunk redesign

- **Theme**: STREAMR neon magenta + cyan with **acid yellow** (`#FCEE0A`) as the signal colour; angular
  **cut-corner** glass (top-left/bottom-right) everywhere; **acid HUD brackets** lock onto focused items;
  the Rajdhani tech face for headings; scanlines; glitch accents.
- **Logo** (new, `tools/streamr_cyber_brand.py`): a chamfered neon plate with an angular "S" whose middle
  bar is cut by an acid play triangle, RGB-split glitch, acid corner stripes; new wordmark with a slash
  cut and "// BY AKSHANSH SAHU"; new TV banner with a synthwave floor; launcher icon on an ink ground.
- **Entrance** (new): a code-drawn boot sequence instead of the video — terminal lines type
  ("OPERATOR ....... AKSHANSH SAHU"), a synthwave grid rolls in, the mark and wordmark glitch into place,
  a segmented SYNC bar fills, HUD brackets frame the screen, and it exits through a scan collapse. Same
  skip / sound toggle (bundled sting) / reduced-motion / cold-start rules. The 2.5 MB intro video is gone.
- **Living background**: the aurora now has a synthwave perspective grid rolling toward the viewer and a
  slow scan sweep (still 1/8-resolution + ~40 lines per frame; paused behind playback).
- **Live player**: cyber HUD — acid pulsing LIVE tag, channel title in Rajdhani, telemetry readout
  (resolution · fps · codec · server · dropped frames), cut-corner HUD slab with an acid stripe and corner
  ticks, segmented neon programme bar, scanlines, glitch on every zap, angular controls with an acid lock.
- **Movie / series player**: "// NOW PLAYING · MOVIE/SERIES", Rajdhani title, telemetry, acid/cyan time
  bar, HUD status panel, scanlines, cut-corner glass controls.
- **Mobile**: angular glass (tight corners, cyan hairlines, acid-edged accent pill), Rajdhani player
  title, scanlines over the video, press feedback.
- **Home**: the new mark beside a Rajdhani STREAMR title, angular container / tab bar / chips / cards,
  Rajdhani section, row and hero titles, glitch on tab switch, scanlines on the hero art.

## Unchanged

Stream sources, APIs, catalogues, DRM, the update mechanism and settings. The playback baseline guard
verifies the 17 protected network/DRM/VOD policy files.

## Verification

615 TV + 615 mobile unit tests pass (6 skipped live-network). Release APKs build and are signed with the
same certificate as the published 5.4.61. Previews: `design/previews/5.4.61-cyber/` (Robolectric renders
of production views with sample content).
