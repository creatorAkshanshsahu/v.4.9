# AK TV 4.9.22 — phone-plays / TV-refused investigation

## What the user reported

- Channel 872 (Sony YAY) plays on the phone with picture and audio, on AK TV 4.9.21.
- The TV is on the **same mobile hotspot** and had also played on that network earlier.
- The TV sometimes does connect to JJTV and produce picture and audio.
- When it fails, the TV's JJTV attempts stop at HTTP 403 / Media3 2004 before any track is selected; TG attempts fail separately (no supported DRM/tracks, one Media3 3002 parser error).

A 403 means the server refused that particular request. On the same network it is not an
ISP/hotspot outage, and it is not proof that the TV lacks a decoder — the TV never reached track
selection in the failing attempts.

## What 4.9.22 changes

These are targeted, bounded changes. They do **not** alter the verified 4.9.18 request, source or
DRM contract, and they do not add a web or backup player.

1. **Per-channel default provider (V1 ID 872 only).**
   `ChannelProviderOrder` tries JJTV first for Sony YAY even when the TV remembers an older TG
   success for that channel. TG stays as the automatic second provider, and an explicit
   "Play TG / Play JJTV" menu choice always wins. All other channels keep their remembered
   provider. Rationale: the only confirmed working route for 872 is JJTV, so an old TV-side TG
   preference should not be spent first.
2. **Pre-flight credential headroom.**
   `PlaybackFreshness` checks the earliest readable `__hdnea__` expiry in the resolved sources. If
   it falls inside the startup margin — 30 s on a phone, 60 s on TV-class devices — the provider
   configuration is re-read once per provider and playback session before the player starts.
   A TV box needs longer between "credentials resolved" and "first manifest request" than a phone,
   so a credential that is still valid when resolved can already be stale when Media3 asks for the
   manifest; that produces exactly a 403 with no tracks. Opaque credentials without a readable
   expiry are left alone, and no credential is ever created, extended, guessed or reused across
   providers.
3. **Report line.** The redacted report now includes a
   `Signed-credential headroom: …` line stating the remaining seconds at startup (and after a
   pre-flight refresh, if one happened). This contains no token, URL, cookie, key or key ID.

Version is `4.9.22` / code `39` so screenshots can be attributed to this build.

## What this does not claim

- The root cause of the TV 403 is **not proven**. The change removes two plausible device-specific
  handicaps (stale provider preference, credentials expiring during slow startup); it cannot
  guarantee the provider will accept the TV's request.
- If the TV still returns 403 with a large positive headroom, the refusal is not expiry-related and
  the next evidence must be the two redacted reports (failing TV attempt and a working TV attempt)
  showing provider, request profile, HTTP status and DRM stage.

## Verification

- `python3 .github/scripts/check-playback-baseline.py` — all 16 preserved request/DRM/VOD files
  unchanged from the 4.9.18 baseline.
- New offline unit tests: `ChannelProviderOrderTest`, `PlaybackFreshnessTest`.
- Device verification on the TV is still outstanding; the CI build is the first compile/test gate.

## How to test on the TV

1. Install the 4.9.22 arm32 APK; confirm the version label on the home screen.
2. Open channel 872 and let it auto-connect. Do not touch MENU first.
3. If it plays, note that it worked and the credential-headroom line.
4. If it fails, open **Playback details → Copy redacted report** and share it, along with the same
   report from a phone success if convenient.
