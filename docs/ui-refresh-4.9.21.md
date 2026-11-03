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


## Delivered build evidence

- Verified runtime/preview commit: `e945dda5fb4d6de9c2e2c7c08078677d7201f6c4`.
- [Successful build 39230231216](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39230231216), build job `117986144801`.
- [APK ZIP, arm32 + arm64](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39230231216/artifacts/12176304370).
- **292 offline tests passed per ABI**, no failures/errors; four opt-in cases skipped offline and all four passed separately.
- Sixteen working-4.9.18 contract hashes verified before building.
- Native Android layout previews checked at 960×540 (TV/live + movies) and 800×360 (phone). Navigation-label clipping was corrected; the movie hero leaves the complete first poster row and captions visible.
- `design/previews/` contains native-layout renders using labelled sample content; `design/entrance-preview.gif` is a motion illustration, not a physical-device recording.
- No physical TV smoothness success is claimed for Star Sports. Test the exact Hindi HD channel, then use **MENU → Picture quality** if a manual stable rendition is preferred.

## 2026-10-20 paired device check — Sony YAY (channel 872)

The user retried build **4.9.21 / code 38 / build 39230231216** on both the Hivendor Android TV and a OnePlus phone within about 80 seconds. Both reports show the same app build, channel ID 872, and native Media3/SurfaceView engine; neither uses WebView or Gecko for live playback.

- **Phone succeeded:** JJTV / DASH / ClearKey, CENC keys loaded, 12 manifests, 2 init loads and 30 media loads (~6.6 MB); H.264 1024×576 at 25 fps / ~1.7 Mbps with AAC; first video frame and audio advancement both reported true. The status bar shows 5G+.
- **TV did not start a picture:** it showed “waiting for the first picture”; two JJTV ClearKey attempts returned HTTP 403 / Media3 2004. A separate TG/DASH attempt configured as DRM NONE reported unsupported DRM and loaded manifest bytes but zero init/media segments and selected no tracks; another TG attempt reported Media3 3002 / ParserException. The TV report also showed codec-limit fallback enabled, but these errors occur before a TV video decoder was selected.
- The user confirms that the phone and TV use the same mobile hotspot; the same network had worked for both earlier that morning. The user also reports that the TV sometimes connects to JJTV. The phone's 5G+ icon therefore is not evidence of a different egress route. A general hotspot/ISP outage or permanent TV incompatibility is not a good explanation. Same egress still does not guarantee identical per-device HTTP requests or provider authorization. The earlier “webpage versus native player” explanation does not apply: both are native Media3.
- The prior redacted metadata check at 00:01 IST showed several hours of validity remaining for Sony YAY authorization. Expiry alone is not a fit for this later phone-success/TV-403 pair; a 403 still means that specific TV request was refused. Do not treat the test-runner's 451 as the TV result.
- **Next useful evidence:** copy the full **redacted playback report** from a failing TV attempt and, if it plays again, from a successful TV attempt on the same setup. Comparing those same-device reports should show whether the successful case used a different provider/profile or reached different playback stages. A successful phone report is useful context but is less discriminating now. Do not ask the user to press **Check DRM** during playback; it stops the stream and checks only the local ClearKey service. The TV decoder remains untested until that device receives tracks and media segments.

## Follow-up build 4.9.22

The paired result above is carried into a new build rather than a new UI. See
`docs/tv-jjtv-403-4.9.22.md` for the bounded changes (JJTV-first default for Sony YAY, one delayed
retry after a refused request, and using the provider's other request style after a refusal), the
unchanged 4.9.18 request/DRM contract, the captured working TV session on channel 165, and the
verification that is still outstanding. Version is now `4.9.23` / code `40`.

## 4.9.24 refresh source

The current refresh is version `4.9.24` / code `41`, with the exact 4.9.21 source commit
`e945dda5fb4d6de9c2e2c7c08078677d7201f6c4` as its Git parent. It carries forward the intervening
4.9.22/4.9.23 changes while adding the selected rail-led/ambient redesign, catalogue persistence,
VOD trailers, and playback failure-stage reporting. The 4.9.18 contract guard passes. The updated
arm32/arm64 APK build and unit-test artifact is pending CI; do not replace the known-good 4.9.21 TV
installation before the user has reviewed the new APK.
