# STREAMR 5.4.61-aurora — Aurora Glass on the latest STREAMR

Branch `streamr-aurora-integration-5.4.61`, based on the verified STREAMR 5.4.61 source.
STREAMR stays STREAMR: same name, package (`com.akshansh.aktv` / `.mobile`), logo, banner,
launcher icon, signing key and entrance video with the "BY Akshansh Sahu" credit.

## What "latest STREAMR 5.4.61" actually is (verified)

| Claim | Verified |
|---|---|
| Release `latest`, "STREAMR 5.4.61", code 180, CI build 205 | Yes: release body and `update.json` |
| Tag `latest` → `7e6ceec` | The tag object does point at `7e6ceec`, **but that commit is `main` = AK TV 4.9.45**, not STREAMR |
| Source that built 5.4.61 | **`599f879`** on `arena/a7aea110-v-4-9` (open PR #6): `versionCode = 180; versionName = "5.4.61"`; its Build APK run #205 built and published the assets (only the network-dependent `inspect` job failed) |

The release is re-published by CI while the tag stays on `main`, so the tag is not a reliable
pointer to the shipped source. This branch is based on `599f879`.

## History reviewed

- STREAMR (`599f879`) = `main` + 265 commits; `main` has nothing STREAMR lacks. Merged PRs
  #1, #3, #4, #5 are therefore already included.
- Branches with commits not in STREAMR, and the decision for each:
  - PR #2 `arena/a046ecd4`: the request-profile clamp is already in STREAMR (`coerceAtLeast(0)`,
    identical behaviour); the rest is concept art. Nothing to add.
  - `arena/93fb8adb`: switches the second live provider to GioTV. Not integrated: it changes live
    playback sources, which this redesign must not do, and STREAMR evolved its providers since.
  - `arena/2f54f462`: a separate web player with an ad layer, plus a Jio playlist rename that
    STREAMR 5.4.59 already handles. Not app UI; not integrated.
  - `arena/f0511f19`: an unrelated DAA lab (C program). Not integrated.
- `liquid-glass-redesign` (AK TV 4.10.0): its design system was ported; its AK branding was not.

## What was integrated from the liquid-glass work

Ported into `UiKit.kt`, re-tuned to STREAMR's own neon (magenta `#FF00D0` → violet `#7B5CFF` → cyan
`#00ECF7`):

- `GlassDrawable` + `GlassKit` factories: translucent body, specular lens, rim light, aurora focus
  fill, one-shot light sweep, animated state changes. No blur pass (cheap on 32-bit TV boxes).
- `AuroraView`: living background (1/8-resolution, ~24 fps, pauses behind playback and when
  animations are off), leaning toward the focused item's colour.
- `Motion`: springy focus lift with a coloured glow (smaller on low-RAM devices), staggered entrances.
- Glass activity transitions, glass dialogs, springy XML control lift (+ press-in on touch).

Not integrated: the AK TV logo, launcher icon, banner, AK palette and AK entrance.

## Redesign by screen

- **Home (TV)**: aurora behind the Flix glass container; glass tab pill (aurora on focus, lit glass
  for the open tab); glass chip strip (frosted white selected chip); tinted glass channel cards with
  a gradient monogram; glass action tiles; Aurora Glass focus rings on hero cards and posters;
  staggered shelf entrance.
- **Live player**: STREAMR's floating text-on-picture block is kept (deliberate upstream choice);
  controls are glass pills with an aurora focus and springy lift; glass logo box; frosted progress track.
- **Channel guide / programme guide / search**: glass panels and keys, glass programme cards.
- **Movie & series details**: aurora layer, Ken Burns backdrop, glass buttons, season pills and
  episode cards, staggered entrance. STREAMR mark kept in the toolbar.
- **Movie / series player**: glass control row; "Next episode" stays magenta (now magenta glass).
- **StarShare / maxTV account dialogs**: glass panels.
- **Mobile**: palette moved from rose/violet to the STREAMR aurora; living aurora behind mobile home;
  the header shows the real STREAMR wordmark (with the credit) instead of plain text; white type on
  accent pills; press-in feedback.
- **Entrance**: unchanged (the STREAMR motion-logo video and its fallback lockup).

## Fixed

- Channel guide, search results and the on-screen keyboard turned the focused row's text near-black
  (`ABYSS`) on a dark focused background, making the item under the remote unreadable. Focused rows
  are now white type on the aurora.
- UI previews painted channel names in dark navy and used retired card colours, so they did not show
  what the app draws. They now use the production glass styling.

## Unchanged by design

Every stream source, API, resolver, DRM path, provider order, catalogue, update mechanism and
setting. `check-playback-baseline.py` verifies the 17 guarded network/DRM/VOD policy files.

## Versioning and installation

`versionCode` stays **180**, `versionName` is `5.4.61-aurora`. Release APKs are signed with
`keystore/aktv-update.p12`, the same certificate as the published 5.4.61
(SHA-256 `0ac2e945…98700`, checked against the release asset), so they install over an existing
STREAMR. The in-app updater compares version codes only: it will not push back to stock 5.4.61, and
it still offers real upstream releases (code 181+).

## Verification

- `testTvArm64DebugUnitTest` and `testMobileArm64DebugUnitTest`: 610 tests each, 0 failures
  (6 skipped live-network tests), same counts as the untouched 5.4.61 baseline.
- `assembleTvArm64Release` / `assembleMobileArm64Release` build locally; CI builds all four.
- Previews in `design/previews/5.4.61-aurora/` are Robolectric renders of the production views
  with sample content, not playback captures.

## CI

`.github/workflows/streamr-aurora-apk.yml`: on pushes to this branch only (plus manual dispatch),
runs the baseline guard and unit tests, builds signed release APKs for TV and mobile
(arm64/arm32) with the StarShare secrets, and uploads the `STREAMR-Aurora-APK` artifact. It never
publishes a release.
