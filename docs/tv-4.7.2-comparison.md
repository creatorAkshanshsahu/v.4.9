# TV playback: 4.7.2 comparison and current TG/JJTV trace

Investigated 2026-10-17 for the report that the phone is working again but the generic TV box is not. This does not erase the earlier intermittent same-phone APK failures.

## What was actually recovered

Repository: [creatorAkshanshsahu/v4.7.2](https://github.com/creatorAkshanshsahu/v4.7.2).

The source upload at [`f6487e535accd119226bb6dd830815a030786778`](https://github.com/creatorAkshanshsahu/v4.7.2/tree/f6487e535accd119226bb6dd830815a030786778) declares **4.7.2 / code 14**. Its later README also links to a 4.7.3 APK, but no separately versioned 4.7.3 source was found and that APK was not inspected. Findings below are specifically about **4.7.2**, not an invented 4.7.3 change log.

## Missing Gecko / permissions hypothesis

| Component | Recovered 4.7.2 | Current app |
| --- | --- | --- |
| Media3 | 1.3.1 | 1.3.1 |
| GeckoView | 123.0.20240213221259 | Same version, still bundled for the general browser |
| SDK configuration | min 28 / target 33 / compile 35 | Same |
| Manifest permissions / activity configuration | Recovered manifest | Identical at the start of this comparison; no new permission was needed for this change |

A missing Gecko dependency or manifest permission has **not** been established. Native Media3 does not run Gecko's JavaScript player merely because Gecko is packaged. Neither adding the same dependency again nor pretending the box's Android release string proves its framework/codec capabilities is a diagnosis.

## Two meaningful differences

### 1. 4.7.2 could silently switch to a browser engine

In the recovered `MainActivity.kt`:

- Native playback was the default.
- Native failure could invoke the TG Gecko player.
- A further fallback could open JJTV `/pind` in Gecko.
- The chosen engine was saved per channel as `eng_<channel key>`; native failure could save `tg`.
- Left/Menu could also cycle the native/TG/JJTV engines.

Consequently an old APK that “worked” might have been playing through a remembered web engine. **The user's actual old engine has not been established.** This remains an explanation to check, not proof that a browser solved the box or that a particular native setting was sufficient.

The standing instruction to remove the live web/backup player remains in force. This implementation does **not** restore it.

### 2. 4.7.2 admitted tracks above the decoder's reported limits

The old native builder used:

```kotlin
.setExceedRendererCapabilitiesIfNecessary(true)
```

Its own nearby comment says using `false` had left audio playing over a black picture. Before this comparison, the current native policy used `false` on all devices.

Media3 1.3.1's `DefaultTrackSelector.isSupported` accepts `FORMAT_HANDLED`, or, when this option is enabled, `FORMAT_EXCEEDS_CAPABILITIES`. It does **not** thereby accept `FORMAT_UNSUPPORTED_DRM`, `FORMAT_UNSUPPORTED_TYPE`, or `FORMAT_UNSUPPORTED_SUBTYPE`.

### Native changes in 4.9.18

- Restore that reported-limit fallback for the **TV device policy**. Fully supported tracks are still preferred; a real decoder must still exist.
- Keep normal touch-phone defaults unchanged.
- Recognize a no-touch D-pad device as TV-style even if generic firmware omits the usual TV/Leanback feature flags. Do not guess from model names or a spoofed release string.
- Add **MENU → TV compatibility: force TV mode (generic boxes)** for devices whose input/features still misidentify them. It switches native device policy, not player engines; the menu can restore automatic detection.
- Preserve secure-decoder requirements, provider-owned keys, source/request scoping, bounded retries and lower TV memory use. No DRM bypass, key substitution or forced Widevine security-level change.
- Keep the conservative TV synchronous codec adapter and buffers; do not blindly restore every old performance setting, such as a 96 MB buffer and forced asynchronous queueing on all devices.
- Separate “above reported limits” from genuinely unsupported codecs in Playback Details. API level and ABI information are included alongside the Android release string.

Tests exercise **Media3's actual support predicate** to verify that this fallback still rejects unsupported DRM and absent codec types. This is a code-grounded compatibility correction, **not proof of the cause or of successful playback on this box**.

## How TG actually plays JJTV

The current TG server-list code in `servers-kHxJvDPi.js` appends JJTV as an **iframe**:

```text
https://jjtvxweb.pages.dev/pind?id=<exact channel ID>
```

It uses `no-referrer`, enables the required iframe media/script features, and lets **JJTV's own Shaka/browser player** perform source lookup, ClearKey setup and playback. TG's original Shaka player does not simply ingest JJTV as an undisclosed third native server.

Fresh runner trace [39054596443](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39054596443), inspect job `117459883779`, observed the following for Star Sports 1 HD `1106`, Star Sports 1 Hindi HD `1108`, Hindi SD `362`, and Sony YAY Hindi `872`:

1. TG tries the Jio source.
2. Its relay redirects back to Jio; the browser's cross-origin redirect request has `Origin: null`.
3. TG opens JJTV for the **same exact ID**.
4. JJTV's media request carries its own origin and no Referer.

Every tested media response from this runner ended in **HTTP 451**; no rendered video was observed. The trace verifies the source/request sequence, **not that those channels are down on the user's network or that the new native TV policy works**. Existing native transport already implements the observed request distinctions; this comparison did not justify inventing another server or changing to a neighbouring channel's keys.

See also [the earlier full TG playback trace](tg-playback-trace.md).

## What to check on the box

1. Use the same channel variant when comparing versions: **1108 is Hindi HD; 362 is Hindi SD**. They have distinct IDs/configuration.
2. Try 4.9.18 normally first. If Playback Details says the TV policy is off on this generic box, use the TV-mode override in Menu and retry.
3. If there is still neither picture nor audio, open **Playback Details**. It distinguishes source HTTP/TLS, manifest/init/media downloads, Android DRM stages, selected tracks, decoder initialization and first-frame/audio events.
4. **Check DRM** is an optional local synthetic ClearKey test. It stops current playback. A pass does not prove a real channel's keys, segments or decoded video; a failure can help isolate a platform problem.
5. Record the actual API/ABI, source and exact error/stage. A marketed “Android 14” label, or the user's understanding that the firmware is based on Android 9, alone cannot establish those runtime values.

The box's precise native failure and whether 4.7.2 previously succeeded through native or web playback remain unverified. The APK should be evaluated on-device rather than described as already fixed.
