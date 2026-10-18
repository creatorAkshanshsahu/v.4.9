# TV playback: 4.7.2 source, verified 4.7.3 APK, and TG/JJTV routing

Investigated 2026-10-17; extended 2026-10-18 after the user reiterated that Star Sports and other JJTV-backed channels worked on the same box in the 4.7 builds, even without a TG “Server 3.” The user now reports JJTV working again on mobile in unchanged 4.9.18, while the box still retries and ends in HTTP 403. This recovery is not credited to 4.9.19, and it does not erase earlier intermittent mobile failures.

## What was actually recovered

Repository: [creatorAkshanshsahu/v4.7.2](https://github.com/creatorAkshanshsahu/v4.7.2).

The source upload at [`f6487e535accd119226bb6dd830815a030786778`](https://github.com/creatorAkshanshsahu/v4.7.2/tree/f6487e535accd119226bb6dd830815a030786778) declares **4.7.2 / code 14**. Later main-branch commits change README/build workflow, not the app source. Its GitHub Actions rebuilds therefore cannot by themselves establish 4.7.3 behavior.

The README's public **AK-TV-v4.7.3.apk** was subsequently downloaded and statically inspected in [run 39099750437](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39099750437), job `117593402180`. The APK was **not installed or executed**:

- Package: `com.akshansh.aktv`
- Manifest version: **4.7.3 / code 15**
- Native ABI: **armeabi-v7a (32-bit)**
- APK bytes: 89,963,921
- SHA-256: `dcb3e0ec1de1b459395c628dc26885addc59112c6a34fe9809acf08564d93f58`
- [Redacted static routing report](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39099750437/artifacts/12126460596)

This verifies the published binary's identity and routing code, **not which engine/profile the user's box actually selected, whether that binary is the precise installed variant, or whether it still plays against today's upstream services**. The old APK was not committed to this repository; inspection output omits arbitrary string/key/array literals.

## Missing Gecko / permissions hypothesis

| Component | Recovered 4.7.2 | Current app |
| --- | --- | --- |
| Media3 | 1.3.1 | 1.3.1 |
| GeckoView | 123.0.20240213221259 | Same version, still bundled for the general browser |
| SDK configuration | min 28 / target 33 / compile 35 | Same |
| Manifest permissions / activity configuration | Recovered manifest | Identical at the start of this comparison; no new permission was needed for this change |

A missing Gecko dependency or manifest permission has **not** been established. Native Media3 does not run Gecko's JavaScript player merely because Gecko is packaged. Neither adding the same dependency again nor pretending the box's Android release string proves its framework/codec capabilities is a diagnosis.

## Meaningful differences — including the native request path

### 1. 4.7.2 could silently switch to a browser engine

In the recovered `MainActivity.kt`:

- Native playback was the default.
- Native failure could invoke the TG Gecko player.
- A further fallback could open JJTV `/pind` in Gecko.
- The chosen engine was saved per channel as `eng_<channel key>`; native failure could save `tg`.
- Left/Menu could also cycle the native/TG/JJTV engines.

Consequently an old APK that “worked” might have been playing through a remembered web engine. **The user's actual old engine has not been established.** This remains an explanation to check, not proof that a browser solved the box or that a particular native setting was sufficient.

The 4.7.3 DEX independently confirms this was **not an always-web implementation**: `play()` calls `wantsTg()`; `wantsTg()` reads `eng_<channel key>` with default `native` and selects the embed only for `tg`/`alt`. Otherwise `play()` calls `resolveAndStart()`. `startEmbed()` explicitly detaches/releases ExoPlayer before opening the embedded page. Native error handling retains calls to route/source switching and `fallbackToTg()`.

Therefore it would be wrong to say “4.7.3 must have worked only because of Gecko.” Native playback was also possible, with a request stack that has since changed.

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

### 3. The old native HTTP/cookie path was different too

This was an incomplete part of the earlier comparison; restoring the codec-limit flag alone did **not** reproduce the old native network path.

In the recovered 4.7.2 source:

- `TgServers.fetch()` collects supplied stream URLs, cookies and keys from TG's response. It sorts keyed/tokened sources first and does not require a “Server 3” label.
- The native source search also included the then-bundled playlist and, if no URLs were found, TG/JJTV page scraping. This is separate from displaying a web player.
- `buildRoutes()` tries and remembers per-channel HTTP profiles (`p_<channel key>`), up to 16 routes. For Jio sources, its initial order was the two Jio-oriented profiles, then the site-token and player profiles.
- `Streams.factory()` uses **Media3 `DefaultHttpDataSource` / Android HTTP**, not the current OkHttp data source. Some profiles put the supplied URL token in a Cookie header and on segment requests, with different declared User-Agent/Referer/Origin combinations.
- `jarFor()` configures a Java `CookieManager`/`CookieHandler` for selected profiles. That is a different session/cookie arrangement from the current isolated native transport.
- Native HTTP refusals, including 403, can advance through those routes; after exhaustion, the code can hand playback to the TG embed.

The **4.7.3 binary** confirms retained calls to `DefaultHttpDataSource.Factory`, `ResolvingDataSource.Factory`, the `StreamProfile` cookie/Referer/User-Agent/token-on-segments getters, and `CookieHandler.setDefault` / `CookieStore.removeAll`. It also confirms native routing and TG-embed fallback calls. The exact previously successful profile on the box remains unknown.

These are concrete **network-path differences relevant to HTTP 403**, not evidence that the box lacks a decoder. They do not establish which difference caused the current failure. Nor should the whole old implementation be copied blindly: its global cookie state, merged key sets and broad query replacement must not undo current per-source/per-channel credential isolation or damage provider relay queries.

## Why a missing “Server 3” did not prevent old playback

The user's observation is consistent with the recovered code:

1. Old native resolution consumed TG-supplied URLs/keys, independent of a server's display number. A TG response could itself supply a usable downstream route.
2. Alternatively, after native route failure the app could open TG's embed; TG could then hand off to JJTV inside that page without exposing a numbered third native server.
3. The old app also had a direct JJTV embedded-page option, distinct from the above routes.

Thus **“fetched from TG” describes the entry point, not necessarily the eventual network stack or playback engine**. The absence of Server 3 neither rules out JJTV nor proves which path succeeded. The user's historical success should be taken seriously; the current failure should not be dismissed as a missing codec or attributed to Gecko without runtime evidence.

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

## What remains to isolate on the box

1. Use the same channel variant when comparing versions: **1108 is Hindi HD; 362 is Hindi SD**. They have distinct IDs/configuration.
2. For the reported HTTP 403, first identify whether the failure is a JJTV metadata lookup or the media manifest. The 4.9.19 **Copy redacted report** includes that distinction, plus exact build/run ID and process bitness. A decoder/TV-mode change cannot repair an HTTP refusal.
3. If there is still neither picture nor audio, open **Playback Details**. It distinguishes source HTTP/TLS, manifest/init/media downloads, Android DRM stages, selected tracks, decoder initialization and first-frame/audio events.
4. **Check DRM** is for a later DRM-stage failure, not a remedy for the displayed 403. It stops playback and tests the local synthetic ClearKey path; a pass does not prove a real channel.
5. Record the actual API/ABI, source and exact error/stage. A marketed “Android 14” label, or the user's understanding that the firmware is based on Android 9, alone cannot establish those runtime values.

The box's exact failing lookup/manifest stage and the previously successful 4.7 native/web route remain unverified. A controlled comparison must keep channel, network, current service availability and APK ABI in view. Do not uninstall/downgrade a working installation just to perform that comparison without preserving its data. This follow-up changes investigation/reporting only; it does not restore the removed web backup or publish a new playback fix.
