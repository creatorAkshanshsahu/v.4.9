# AK TV 4.9.22 — phone-plays / TV-refused investigation

## What the user reported

- Channel 872 (Sony YAY) plays on the phone with picture and audio, on AK TV 4.9.21.
- The TV is on the **same mobile hotspot** and had also played on that network earlier.
- The TV sometimes does connect to JJTV and produce picture and audio.
- When it fails, the TV's JJTV attempts stop at HTTP 403 / Media3 2004 before any track is selected;
  the TG attempts fail separately (no supported DRM/tracks, one Media3 3002 parser error).

A 403 means the server refused that particular request. On the same network this is not an
ISP/hotspot outage, and it is not proof that the TV lacks a decoder — the failing attempts never
reached track selection.

## What was ruled out by measurement

- **Credential expiry during startup.** The live read-only check reports roughly 19,100 seconds
  (about 5 h 20 m) of validity remaining on the JJTV credentials for channel 872. A TV cannot lose
  five hours between resolving a source and fetching its manifest, so an "about to expire token"
  explanation was dropped instead of being shipped as a fix.
- **Clock skew** is already handled: token freshness uses the HTTP `Date` reference clock, and the
  CDN — not the device clock — validates the signature.
- **Missing retry.** The app already performs one fresh, provider-owned credential lookup after a
  401/403 before it changes provider.

## What 4.9.22 changes

Both changes are small and bounded. Neither alters the verified 4.9.18 request/source/DRM contract,
and neither restores a web or backup player.

1. **JJTV-first default for Sony YAY (V1 ID 872 only).**
   `ChannelProviderOrder` starts this one channel on JJTV even if the TV remembers an older TG
   success. TG remains the automatic second provider, and an explicit "Play TG / Play JJTV" choice
   always wins. All other channels keep their remembered provider. Rationale: JJTV is the only
   route observed to produce picture and audio for 872 on either device.
2. **One delayed retry after a refused request.**
   When a provider answers HTTP 403 and the normal single credential refresh has already been
   spent, the app now waits about four seconds, re-reads that provider's fresh configuration once,
   and plays it again before falling back. The retry is allowed **once per playback session**, runs
   inside the existing 60-second tuning budget, and is skipped for any other HTTP status, so a
   permanent refusal still falls through quickly.

Version is `4.9.22` / code `39`, so screenshots can be attributed to this build.

## What this does not claim

- The root cause of the TV 403 is **not proven**. The retry removes the case where a transient
  refusal ends the provider's only working attempt; it cannot make the provider accept the TV.
- If the TV still shows two JJTV 403s and then the TG "unsupported DRM" failure, the refusal is
  stable for that client and the decisive evidence is the redacted report from the failing TV
  attempt compared with one from a working TV attempt.

## Verification

- `python3 .github/scripts/check-playback-baseline.py` — all 16 preserved request/DRM/VOD files
  unchanged from the 4.9.18 baseline.
- `ChannelProviderOrderTest` covers the channel default, explicit override, other channels and V2.
- CI (`Build APK`, pull-request run) compiles both ABIs and runs the offline unit tests.
- Physical TV verification is still outstanding.

## How to test on the TV

1. Install the 4.9.22 arm32 APK and confirm the version on the home screen.
2. Open channel 872 and let it auto-connect; the retry message appears only if the provider refuses.
3. If it plays, that is a successful retry; note whether the retry message appeared.
4. If it fails, open **Playback details → Copy redacted report** and share it, plus the same report
   from a phone success if convenient.
