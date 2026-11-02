# AK TV interface refresh — 4.9.25

This refresh extends the selected cinematic rail-led layout with a new AK monogram/play-mark and an ink / electric-blue / cyan / violet palette. Live playback remains native Media3, and movie/series playback remains the native Frost/Media3 path.

## Entrance

The 1.6-second logo reveal uses a small lift-and-settle motion, a restrained color line, and a newly synthesized 1.28-second AK TV sound mark. It is not sampled from Netflix or another brand. Any key skips and any tap outside the visible sound toggle skips the reveal; the toggle remembers the user's choice, and the sting is suppressed when media volume is zero or the device is in silent mode.

## Players

- Live TV adds a `GUIDE` action and Android TV `GUIDE` remote-key shortcut. The in-player schedule panel shows on-now, progress, next and later listings; channel switching remains a separate drawer.
- The movie/series player keeps native Media3 transport, audio/subtitle selection, exact TMDB episode identity and native source controls, with a branded top treatment and new focusable glass-style actions.
- The palette and new mark are shared across home, launcher, detail and playback surfaces. Smaller player layouts reduce the live info card to fit a phone-width screen.

## Schedule fallback

The community JioTV XMLTV feed is the main schedule source; a bounded reader checks it at launch and on the app's background refresh cadence, then reloads the visible guide when the feed changes. If that feed has no current/upcoming row for a channel, the TG guide endpoint is tried as a secondary source. The feed provides metadata only; it is never a video source and never changes channel playback or DRM policy. Missing channel data is shown as unavailable, not guessed.
