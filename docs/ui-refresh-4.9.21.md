# AK TV 4.9.21 — UI, identity and stable sports playback

## User correction and playback baseline

The user clarified that affected channels could play on the JJTV website while the APK failed, and now confirms **4.9.18 plays all channels on the TV box**. The remaining playback complaint is **Star Sports 1 Hindi HD becoming laggy again after “smoothing.”** Earlier observations of expired metadata do not, by themselves, explain the website/APK discrepancy, and a missing TV decoder is not established.

This rebuild therefore uses the **user-confirmed 4.9.18 app playback baseline** (`ec459be5dc413a04d632d9b7ec26df5b2dee53b1`), not the later experimental credential rejection/retry changes. The existing native TG/JJTV and Frost Movies/Series paths remain. No live web/backup player is restored.

`docs/playback-baseline-4.9.18.json` records SHA-256 checks for 16 request/source/DRM/device-policy/VOD files. CI verifies them before compiling. UI, presentation, actual-rendition control and its buffering policy are intentionally outside that immutable network-contract set.

## Visual direction and assets

The design questions were skipped, so the stated defaults were used: **AK TV branding, cinematic midnight/aqua TV-first UI, lightweight animations**. The user subsequently selected **logo option 2**.

- New AK/play monogram, adaptive launcher icon and TV launcher banner.
- Compact left navigation rail for Live TV, 24/7, Movies, Series and scoped Search.
- Clear section headings, a featured-content panel, compact channel cards and on-demand poster shelves.
- High-contrast remote focus states and real, clickable Watch/Details/Favorites hero actions.
- Responsive landscape sizing for TV and phones; movie detail content can scroll instead of overflowing short screens.
- Updated live-player information panel and on-demand controls.
- A **1.6-second entrance** with tap/any-key skip and disabled-animation support. No flash burst, audio or full-screen particle layers.

The selected source artwork is `design/ak-tv-logo.png`. Transparent mark and banner derivatives are produced by `tools/build-brand-assets.py`. Native UI previews use explicitly labelled sample content; they are not screenshots of verified stream playback.

## Why the previous smoothing could be ineffective

The old dropped-frame handler lowered a nominal size cap to 720p and then 540p. It did not select an actual representation, and the track policy could exceed video constraints if needed. Consequently:

- A “540p” message did not prove a 540p representation existed or was selected.
- Adaptive selection could still move among more demanding variants.
- It counted a few dropped-frame callbacks rather than a sustained time window.
- An old player's queued analytics callback was not explicitly tied to that player instance.

Those are code-level weaknesses, not proof of the box's precise bottleneck. A single HD sports feed can still be limited by CPU/decoder load, network buffering, display cadence or available renditions.

## Stable picture behavior

- **Star Sports 1 Hindi HD, exact V1 ID 1108, defaults to Stable picture on a detected TV.** It is never silently replaced with Hindi SD/another channel ID.
- Stable mode lets normal bandwidth selection start the picture, then holds that size or a cheaper real representation rather than forcing an initial quality increase. It uses a renderer-supported representation. It respects the existing selected codec instead of switching a working AVC track to HEVC merely because its bitrate is smaller.
- Sustained dropped-frame pressure causes a measured downward step, then a cooldown. Startup/paused/buffering periods are not automatically labelled decoder overload.
- An exact Media3 track override holds the selected rendition, preventing an automatic upward bounce while that track remains available.
- Long buffering in Stable mode can step down too; a quality lock should not trap playback at a bitrate the connection cannot sustain.
- Changes to a live track group retain the same/lower available choice. If the provider removes that rendition entirely, the UI reports the real remaining minimum rather than claiming an unavailable resolution.
- **MENU → Picture quality** offers Auto, Stable and the actual available video representations. Auto explicitly clears the learned cap/lock; manual selections do not change source credentials or DRM.
- Stable playback uses a modest extra buffering margin within the existing device memory budget. Playback speed, source identity, channel variant and secure-decoder rules are not changed.
- Each controller belongs to one player; late callbacks cannot lower a different channel after a zap.

No software-video decoder is added, no FPS is invented/resampled, and no impossible guarantee is made that a weak box can decode a provider's only available high-load representation smoothly.

## Avoid competing work during playback

- Removed eager Movies/Series catalogue warm-ups from launch. They load when opened.
- Hide the home rendering tree during full-screen live playback and stop its hero/row animations and scheduled preview work.
- Keep the existing one-player/preview cleanup behavior and guard background artwork prefetch while live playback is visible.
- Restore home visibility/focus on exit or lifecycle stop.

## Verification and remaining acceptance

Offline coverage includes available-rendition selection, no fake 540p track, sticky choices, same-codec preference, dropped-frame hysteresis/cooldown, entrance skip/detach behavior, and TV/phone layout bounds. Native Android layout previews are rendered without network access or playback. The established TG/JJTV/Frost tests remain on the 4.9.18 contracts.

Final CI results and APK artifact are recorded in PR #1. **Physical TV testing of the sustained Star Sports smoothness change remains required.** If stutter persists, Playback Details now includes the actual rendition, observed dropped-frame count and buffering count, so a decoder/load issue can be separated from a connection stall. A lower representation cannot be supplied if the upstream source does not offer one.
