# Intermittent live-channel access and renewal — 4.9.20

Investigated 2026-10-19. The goal is better native recovery, **not a claim of uninterrupted availability from third-party providers**.

## Subsequent user correction

The user clarified that the JJTV website was playing during the APK failures and confirmed that **4.9.18 now plays all channels on the TV**, with a remaining Star Sports 1 Hindi HD smoothness issue. The metadata expiry observations below remain observations of those requests/times, not a complete explanation of the browser/APK discrepancy. **4.9.21 returns to the user-confirmed 4.9.18 network contracts** rather than carrying these experimental rejection/retry changes into the UI rebuild; see [current release notes](ui-refresh-4.9.21.md).

## New device evidence

The user supplied working and failing **4.9.18 phone** reports:

- Channel **165**, identified by the user as the working Zee HD stream: JJTV / DASH / ClearKey, `KEYS_LOADED`, CENC, HEVC 1920×1080 using `c2.qti.hevc.decoder`, AAC stereo using `c2.android.aac.decoder`, video-frame and audio-advancement events.
- Channel **872**, Sony YAY Hindi, on October 18: JJTV / DASH / ClearKey, CENC, H.264 1024×576 using `c2.qti.avc.decoder`, AAC stereo, loaded keys, rendered-frame/audio-advancement events and a visible picture behind the dialog.
- Channel **872**, on October 19: JJTV and TG attempts report HTTP 403 / Media3 2004; the displayed subsequent TG attempt has no completed manifest/init/media load or selected decoder.
- The local synthetic ClearKey test passes on the pictured phone. That is **not** a real-channel or HEVC decoding test; the separate working-channel reports provide the relevant playback evidence.
- The application is running as a 32-bit process on a device advertising arm64 support. Those working frames do not support blaming this phone's APK bitness for the 403.

These earlier reports establish a working codec/DRM path on the **phone**, not on the TV. The user later supplied a near-simultaneous paired test on build **4.9.21 / code 38 / build 39230231216**, channel 872:

- The OnePlus phone (Android 16 / API 36; 32-bit app process) played JJTV/DASH/ClearKey with CENC keys loaded, H.264 1024×576, AAC, 30 media loads, first-frame and audio-advance events. Its status bar showed 5G+.
- The Hivendor TV report (API 28; `armeabi-v7a`; 32-bit process) showed no first picture. JJTV attempts returned HTTP 403 / Media3 2004; TG attempts separately showed a 3002 parser error and a DRM NONE / unsupported-DRM no-track result, with zero init/media loads on that attempt. Both devices show the same AK TV build and native Media3/SurfaceView live engine.

This is direct evidence that the channel and a real ClearKey playback route worked on the phone while the TV's request failed. The user confirms both devices were using the same mobile hotspot, that the same network had worked for both earlier that morning, and that the TV sometimes connects to JJTV. Thus the 5G+ icon does not indicate different egress here; a general ISP/hotspot block or permanent TV incompatibility is not a good explanation. The remaining issue is intermittent and per-client/session: the phone's native request succeeds while the TV's request is refused, even on the same network. The screenshot's TV 403 is a request refusal, not a decoder error. The TV's actual codec capability remains untested because no tracks were selected. Earlier language attributing this pair to webpage-versus-native playback was incorrect: both clients used native Media3. The most useful next evidence is the redacted playback reports from a failing TV attempt and, if it plays again, a successful TV attempt on the same setup; these can show whether the successful case used a different provider/profile or reached different playback stages.

## What the live provider supplied

Read-only comparison: [run 39208988485](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39208988485), inspection job `117917935049`, **2026-10-19 06:17:15 UTC / 11:47:15 IST**.

| Exact channel ID | Credential service | Selected credential at inspection |
| --- | --- | --- |
| 165 — user's working Zee HD control | PL / per-channel | Valid for approximately 6m25s more |
| 872 — Sony YAY Hindi | General cookie | Expired approximately 5h17m earlier |
| 1106 — Star Sports 1 HD | PL / exact channel | Expired approximately 4h55m earlier |
| 1108 — Star Sports 1 Hindi HD | PL / exact channel | Expired approximately 4h55m earlier |
| 362 — Star Sports 1 Hindi SD | PL / exact channel | Expired approximately 10h57m earlier |

- Provider HTTPS `Date` values were within a few seconds of the runner clock and reported `Age: 0`; this is not an inference from a possibly incorrect TV wall clock.
- The general cookie endpoint supplied one known-expiry credential and it was expired. The PL endpoint contained **24 expired credentials among 69 distinct known-expiry tokens**.
- Fresh/cache-busted requests returned **unchanged** cookie documents in that check.
- The catalogue URLs' own embedded credentials were also expired by several days. No fresh alternative was established from those original URLs.
- The native metadata checks in build job `117917934860` independently reported expired supplied credentials for the same Sony/Star cases. A TG relay's own URL/query is still distinct from a direct signed Jio request; a relay may generate a new redirect and is not treated as statically expired merely because TG repeats an old cookie field on it.
- Runner media access returns 451 and does not verify decoded playback. The expired metadata is a separate, directly observed finding that fits the user's intermittent 403/recovery reports; it does not prove that every past/future 403 has the same cause.

The provider must publish or return a legitimate renewed signature. The app cannot generate that signature, extend its expiry, borrow another channel's token, or guarantee playback while every supplied route is unavailable. Caching yesterday's URL or adding a decoder would not solve that condition.

## Later verification: usable credentials appeared again

In [build 39211522336](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39211522336), native check job `117925775253`, a later native lookup returned future-expiring supplied credentials for Sony/Star (roughly 5h39m remaining) and channel 165 (roughly 34m). This is evidence that the supplied authorization data changed; **it is not evidence that the app generated or extended a signature, nor a claim of phone/TV playback success**.

The separate inspection runner at 06:43:33 UTC also saw a different failure mode: cookie lookups returned HTTP 403, or HTTP 200 with **HTML rather than the expected JSON**. Those responses cannot establish that a credential document is empty. Inspection now distinguishes unavailable/invalid metadata from a valid empty document and prefers the valid canonical request sample. Header/status alone is not proof of usable source data.

Both APK variants passed **331 offline tests each** (335 methods, four opt-in cases skipped offline). The two TG/JJTV and two Frost native metadata/transport checks then passed separately in that build. This does not turn runner HTTP checks into physical playback verification.

## Changes in 4.9.20 / versionCode 37

### Recognise unusable supplied authorization

- Compare known direct-Jio `st`/`exp` fields against an observed **HTTPS Date/Age reference**, advancing on monotonic elapsed time.
- Never reject a credential solely because the device's wall clock is wrong. Unknown token formats and requests with additional provider auth headers remain for the server to judge.
- Use conservative margins: clearly expired by more than 30 seconds, or not valid for more than 10 seconds yet.
- Prefer a currently usable supplied token over a future-start token with a longer expiry.
- Preserve unexpired alternatives. If all applicable sources have unusable credentials after the bounded metadata refresh, report **provider credentials expired / not yet valid** rather than pretending the decoder failed.
- Do not modify provider-owned signatures or infer an expiry for relay endpoints that can issue a new signed redirect.

### Avoid repeating the same refused request

- Keep session-local hashes of the **effective direct-Jio request**, including the supplied authorization and header profile—not a server label.
- A recently refused identical request is suppressed briefly. A changed legitimate URL/cookie/header combination can be tried immediately; a different channel or explicit manual retry starts its own context.
- Dynamic relay URLs are not suppressed merely because the URL is unchanged.
- An auth refresh no longer retires **every untried source/profile** from that provider. If renewal fails, other already-advertised alternatives remain eligible within the existing deadlines.

### Bounded automatic recovery

- After eligible access/network/credential failures, gently check for fresh available sources after **30, 60 and 120 seconds**, only while the user remains on that channel.
- Keep the existing per-cycle/provider startup bounds; there is no endless spinner or tight retry loop.
- Cancel delayed checks on channel/session change, Back/close, backgrounding or an explicit DRM test. A late callback cannot resume a different channel.
- Honour `Retry-After`. Long rate-limit delays are not shortened. A provider explicitly indicating a browser challenge is not repeatedly retried or worked around.
- Decoder/DRM/geographical failures alone do not initiate credential polling. A separate provider's decoder failure does not erase an eligible renewal for another provider.
- After three scheduled checks, stop automatic work and leave manual source/retry controls available. A real stable playback period resets the recovery budget for a later interruption.

The 4.9.19 request-policy and response-classification corrections remain. Frost Movies/Series, source-owned DRM and native playback remain intact. **No software-video decoder, fabricated source, substituted key, or web/backup player is added.**

## Verification and limits

Deterministic tests cover trusted versus device clock, expiry/start windows, unchanged-request suppression, changed credentials/headers, relay exceptions, independent channel/provider contexts, retry bounds, Retry-After, challenge handling, and delayed-work lifecycle guards. Both APK ABIs must pass the offline suite before delivery.

The live checks are separate and non-gating: they must report provider-supplied stale credentials as unavailable instead of disguising that state as a successful playback test. Final build/test/artifact evidence is recorded in PR #1.

The expected improvement is **clearer status, fewer redundant requests and automatic recovery when usable provider data appears**. It is not a promise that Sony YAY/Star will play immediately while the provider is still returning expired credentials, nor a physical TV-box playback verification.
