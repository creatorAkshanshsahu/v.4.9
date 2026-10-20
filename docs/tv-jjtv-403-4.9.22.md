# AK TV 4.9.22 / 4.9.23 — phone-plays / TV-refused investigation

## What the user reported

- Channel 872 (Sony YAY) plays on the phone with picture and audio on AK TV 4.9.21.
- The TV is on the **same mobile hotspot**, had played on that network earlier, and the user reports
  the TV **sometimes connects to JJTV**.
- When 872 fails on the TV, its JJTV attempts stop at HTTP 403 / Media3 2004 before any track is
  selected; the TG attempts fail separately (no supported DRM, one Media3 3002 parser error).

A 403 means the server refused that particular request. On the same network this is not an
ISP/hotspot outage, and it is not proof that the TV lacks a decoder — the failing attempts never
reached track selection.

## TV success captured on another channel (2026-10-20 11:13 IST, build 4.9.21)

A working TV session is now documented, on **channel 165** (Zee Cinema HD) with the same Hivendor
Android TV, `armeabi-v7a`, 32-bit process, build 4.9.21 / code 38 / build 39230231216:

| Field | Value |
|---|---|
| Provider / container / DRM | JJTV / DASH / configured ClearKey |
| Mode | standard; headers: **browser** |
| DRM support / stage | true / **KEYS_LOADED** |
| Loads | manifest 12–13, init 2, media 33; ~5.75 MB |
| Clock skew vs HTTP Date | 0 s |
| Selected tracks | video 1, audio 1; unsupported DRM 0, codec 0; above reported limits 2 |
| Scheme | cenc |
| Video / decoder | **HEVC 1280×720 / OMX.google.hevc.decoder** |
| Audio / decoder | AAC 2 ch / OMX.google.aac.decoder |
| Picture | surface ready, **first video frame true**, **audio advanced true** |
| Render | 720p · 25 fps · 2.5 Mbps · HEVC, picture policy "Auto quality" |
| Health | dropped frames 50, buffering events 1 |

What this establishes:

1. **The TV is not broken.** It opens JJTV, receives ClearKey keys, selects tracks and renders both
   picture and audio. TV playback is not blocked by the network, the hotspot, or the player engine.
2. **The failure is specific to the 872 request/session**, matching the earlier phone-success /
   TV-403 pair. Same build, same app, same account environment, different channel.
3. **The TV decodes through `OMX.google.*` software decoders** and reports two tracks above its
   reported limits (`allowReportedCapabilityExceedance`). That is why the "TV reported codec-limit
   fallback" line is true, and it is the likely source of the 50 dropped frames — a smoothness
   issue, separate from the HTTP 403. No channel is downgraded or removed to hide it.
4. **The browser request style works on the TV** for this channel, so "the TV always prefers the
   native header style" is not a tenable explanation for the 403 either.

## Changes shipped

Both are bounded and keep the verified 4.9.18 request/source/DRM contract. No web or backup player
is restored, no credential is invented, and no DRM requirement is relaxed.

### 4.9.22 (build 39313250730)

1. **JJTV-first default for Sony YAY (V1 ID 872 only).**
   `ChannelProviderOrder` starts this one channel on JJTV even when the TV remembers an older TG
   success. TG remains the automatic second provider and an explicit "Play TG / Play JJTV" choice
   always wins.
2. **One delayed retry after a refused request.**
   After a 403 that already spent the single credential refresh, wait about four seconds, re-read
   that provider's fresh configuration once and play again before falling back. Once per playback
   session, inside the existing 60 s budget; other HTTP statuses fall back immediately. The flag
   resets after 30 s of stable playback.
3. **Dropped, not shipped:** a "token expires during TV startup" pre-flight refresh. Live data shows
   ~19,100 s (~5 h 20 m) remaining on the 872 JJTV credentials, so that hypothesis was removed
   rather than shipped as a misleading fix.

### 4.9.23 (this build)

4. **Try the provider's other request style after a refusal.**
   `RequestProfileFallback` selects the *other* documented JJTV request style (browser vs declared
   native) for the same channel when the previous one was refused, in both the credential-refresh
   path and the delayed retry. Previously both paths pinned playback back to the style that had just
   failed, so the unused style was never attempted. When only one style is advertised, behaviour is
   unchanged.

Version is `4.9.23` / code `40`.

## What this still does not claim

- The root cause of the TV 403 on 872 is **not proven**. The retry and the style fallback remove
  ways the app could give up while a working route still existed; they cannot make the provider
  accept the TV.
- If 872 still returns two JJTV 403s and then the TG "unsupported DRM" failure on 4.9.23, the
  refusal is stable for that client, and the decisive comparison is the redacted report from the
  failing 872 attempt against the 165 report above.

## Verification

- `python3 .github/scripts/check-playback-baseline.py` — all 16 preserved request/DRM/VOD files
  unchanged from the 4.9.18 baseline.
- `ChannelProviderOrderTest`, `RequestProfileFallbackTest` cover the channel default, explicit
  override, other channels, V2, and both request-style transitions.
- CI `Build APK` compiles both ABIs and runs the offline unit tests.
- Physical verification of 872 on the TV is still outstanding.

## How to test on the TV

1. Install the 4.9.23 arm32 APK, confirm the version on the home screen.
2. Open 872 and leave it alone. A retry message appears only if the provider refuses.
3. If it plays, note whether the retry message appeared — that is the new path working.
4. If it fails, open **Playback details → Copy redacted report** and share it.

## Build evidence for 4.9.23

- CI build [39392205009](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39392205009),
  job `build` succeeded for both ABIs; APK artifact
  [12250805492](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39392205009/artifacts/12250805492).
- Baseline guard, offline unit tests, TV/phone layout previews and the read-only live checks ran in
  the same build.
