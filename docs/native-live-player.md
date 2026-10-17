## 4.9.19: phone HTTP/manifest failures

The user confirmed current mobile failures before decoding: JJTV metadata HTTP 403, TG HTTP 403, and a manifest parsing error. [Investigation and changes](mobile-http-errors.md) cover the newly traced JJTV metadata Origin/Referer mismatch, canonical metadata URLs, per-stage HTTP response classification, guarded native format correction, and **Copy redacted report** with an exact build ID. This is not a confirmed codec/Gecko failure or a verified on-phone fix; the current same-channel website comparison remains untested.

---

## 4.9.18: recovered TV compatibility behavior

The [4.7.2 comparison](tv-4.7.2-comparison.md) found the same Gecko/Media3 dependencies and manifest, but different native track-capability selection and an old remembered browser fallback. The native TV policy now restores the reported codec-limit fallback, while still rejecting unsupported DRM and absent codecs. Touch-phone defaults are unchanged. Generic no-touch D-pad boxes are recognized, and Menu includes an explicit TV-mode override if firmware still misidentifies the device. Playback Details separates reported-limit exceedance from unsupported codecs and reports API level plus process bitness.

The live browser backup remains removed. The user's box playback and the exact native failure have not been verified by the assistant. Current TG still embeds JJTV as a no-referrer iframe; fresh runner traces again ended in HTTP 451 without frames, not evidence of an outage on the user's network.

Movies/Series are independently migrated to [native Frost VOD](tgfrost-native-movies.md), without importing live offsets, Jio authentication or channel DRM.

---

# Native live TV (4.9.17)

## 4.9.17 — match TG's observed per-request behavior

See [How TG handles the affected channels](tg-playback-trace.md) for the exact source chain, source-code links and the distinction between request evidence and decoded playback. TG uses primary Jio → advertised relay → a separate JJTV browser frame, not a hidden third numbered native service. The current trace covers TG entry pages for 1106, 1108, 362 and 872 with request timing; inspection queries their actual deployed server function.

Two concrete differences from that code/trace are corrected:

- **Active cookie on every Jio channel request.** TG/JJTV replace the active authentication token on manifests and segments. The old native policy replaced it only on the initial URL and always preserved an existing child token. An explicit TG/JJTV cookie now authorizes replacement of the known `__hdnea__` parameter within the exact advertised HTTPS Jio CDN/channel path, including a scope established by a successful authorized relay manifest redirect. Other channels/origins/ports, generic independently signed children, and relay-owned queries remain protected. Other query bytes are not thrown away.
- **Browser redirect Origin.** TG's direct/relay fetch uses the page origin; its cross-origin redirected request uses literal `null`. Native browser-style requests now track that state per HTTP call, including redirects back to the original host. A new direct segment call restores the page origin. Explicit native header profiles keep their existing behavior; no credentials are sent to unrelated origins.

Thirteen regression methods cover stale child authentication, source/channel/origin isolation, exact signed-byte preservation, relay token scope, real OkHttp redirect headers, multi-hop redirects, no-referrer JJTV and declared native-profile compatibility. No decoder, browser engine, provider priority, timer or DRM-security setting was changed for this comparison. The box's actual native playback still requires a device test.

## 4.9.16 — intermittent native authentication, not a presumed JJTV outage

The user now reports intermittent JJTV failure **on mobile as well as the box**, and explicitly confirms that the same failing channel plays on the JJTV website in the phone's browser. The box uses that phone's hotspot. This does not support diagnosing every failure as an old-TV decoder problem or an upstream outage.

Fresh inspection [39044234251](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39044234251) found the catalogue and token endpoints responding, valid supplied key formats for 816/872/1106/1108/362, a live general token, and a mixture of current/expired Star records. A cache-busted read did not change those sampled documents. A second real-browser trace [39048028686](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39048028686) confirmed Chrome/no-Referer for **direct JJTV** as well as the TG embed, so no speculative new request-header profile was added. That trace also observed a Sony request with an expired signature; it did not establish why that run selected it. All sampled media requests from CI returned HTTP 451; it did not show decoded video and is not a playback test of the user's network.

Concrete client defects corrected:

- **Do not strip authentication using the device wall clock.** The old transport stopped attaching supplied/learned tokens to unsigned segments once the local clock considered them expired. That could break an active stream or send unsigned requests on a misdated box. The native transport now forwards the source's supplied credentials to the same authorized origin and lets the CDN accept/reject them. It does not create or extend signatures.
- **Do not let a separately signed audio/video segment replace the session token.** The old network interceptor learned from every successful response. A track-specific token could then be sent to another track. Learning now happens only for successful manifests/redirect chains (including explicit HLS child playlists); independent media-segment signatures and unrelated query bytes remain untouched. Initial URLs with fragments are matched correctly after the HTTP layer removes the fragment.
- **Refresh rejected credentials promptly and once.** HTTP 401/403 from full-screen media triggers one fresh lookup of the current provider before trying another header style with the same rejected token. Media3 does not repeatedly load or blacklist tracks for that authentication failure first. The existing 25-second provider and 60-second overall budgets are not reset. Other network/server/DRM/geographic errors keep their previous handling. The gate resets for a manual/new tune or genuinely stable playback, not merely READY.
- **Keep the source list bounded during renewal.** Fresh provider entries replace its rejected entries; retained source indices/failure state are remapped without resetting the one-refresh recovery budget.
- **Select/cache credentials independently of a bad TV date.** The JJTV resolver uses HTTPS HTTP Date plus Age when available and advances that reference with monotonic time. Its short source/cookie/catalogue caches also use monotonic time. Among records for the same channel it prefers a longer-lived current token rather than the first nearly expired record. Unknown-expiry values remain supported.
- A near-expiry token from a new network response (not only the in-memory cache) gets one bounded re-read. Explicit fresh lookups/cache re-reads bypass public metadata caches with a nonce only when the endpoint has no existing query. Signed media URLs and metadata endpoints that own queries are never cache-busted. If the provider supplies no new credential, the app cannot manufacture one.
- A required Star-cookie endpoint failure retains its typed HTTP error instead of becoming only “no sources.” Regular streams retain the website's existing unsigned/original-URL attempt when no cookie is provided; no unrelated Star/general credentials are borrowed.

Tests cover wrong/corrected clocks, HTTP Date/Age, cache lifetime, near-expiry re-reads, exact-channel token choice, independently signed track isolation through the real OkHttp transport, actual Media3 authentication-error policy, bounded renewal and source reindexing. An end-to-end MockWebServer case rejects an old token, forces the production resolver to fetch fresh metadata and verifies the new token on both manifest and segment requests. It is a synthetic HTTP/metadata test, **not decrypted live playback**.

**Still required:** install 4.9.16 and compare one exact channel in the phone browser, APK on the phone, and APK on the box. The above defects are real and regression-tested; without the device's detailed failure report, it is not established which one caused the latest mobile failure or whether the box has an additional native DRM/decoder fault. MENU → Playback details retains the original HTTP/DRM/error stages if it still fails. On a phone, tap the visible loading/error message for details, or tap the visible source line to choose the native provider. Do not claim that all channels or the particular box have been verified.

## 4.9.15 — give JJTV a real startup opportunity on the box

The supplied photos show working **JJTV / browser request** playback on the phone, while the box's timeout shows **Server 2** and **decoder: not initialized**. The phone example is Star Sports 1 Hindi HD; the box title appears to be the non-HD Hindi variant. Their channel IDs/keys are not interchangeable and are not remapped. The box reports the generic model AndroidTV and release 14; the user says the firmware is actually Android 9. The release string is not treated as proof of its underlying API or DRM implementation.

A concrete scheduling defect was found: a global last-working-TG hint made unrelated untried channels start on TG; metadata lookup plus two 15-second no-picture attempts and an unnecessary same-source decoder retry could consume the 60-second total budget before JJTV was reached or its metadata completed. The old source label could also remain on screen while the next provider was resolving. The photos do not prove that JJTV was never attempted, nor that the box's DRM is broken, but this code path could starve it.

- Untried V1 channels now start with **JJTV**, matching the native route visibly working on the phone. A provider that actually worked for that exact channel still takes precedence. An unrelated channel's TG success no longer changes this order. V2 remains TG-only.
- Each provider gets at most **25 seconds including lookup and native startup**, within the existing 60-second whole-cycle deadline. Source/codec retries cannot renew that provider window. On expiry, that provider's pending lookup is invalidated and its current sources are retired, allowing the other provider and then the one bounded fresh pass. A rendered frame clears both startup timers.
- A no-picture event does **not** trigger a software-decoder retry while no decoder has initialized and selected video is still waiting for keys. Explicit codec failures, or selected video with accepted keys, can still use the existing native compatibility retry. Crypto/DRM exceptions are not reclassified as decoder errors merely because they are wrapped in a generic playback error.
- Decoder retry state and stable compatibility preferences are scoped to the provider as well as channel. A failed TG attempt must not force an untried JJTV stream into software mode. Old stable preferences migrate only to the provider that actually rendered that channel.
- **MENU → Play JJTV now (native)** directly fetches JJTV for the same V1 channel without waiting for TG. TG and Automatic choices remain available. This changes the first provider, not the player engine; automatic fallback remains. Explicit choices are not saved as successful until a frame is reported.
- Provider labels update immediately during lookup, distinguish TG explicitly, and cannot be overwritten by a late programme-guide callback. Error text sits above the channel card; the card is hidden on final failure to avoid the overlap visible in the photo.
- Retain the 4.9.14 diagnostic report/self-test. Metadata HTTP errors retain numeric status without leaking exception messages. **MENU → Playback details**, INFO, or OK after failure opens the report; Check DRM remains an explicit local test, not a playback fix.

New deterministic cases replay slow TG metadata / TG server 1 and 2 timeouts, verify that JJTV gets a turn before the global deadline, prevent decoder-mode carryover, check manual selection/V2 separation, and ensure waiting for DRM/downloads cannot trigger an ineffective software-codec retry. The existing resolver/header/key tests remain in place. No source keys, requests, cipher modes or security requirements have been weakened to compensate for a spoofed version string.

**Device acceptance:** install 4.9.15, try the exact **Star Sports 1 Hindi HD** variant shown working on the phone, and use MENU → Play JJTV now if necessary. Confirm the source line says JJTV / browser request, then check picture/audio and sustained playback. Also test the SD variant and other affected channels separately. The scheduling defect is testable without the box; successful playback on this particular firmware remains unverified until the device test. If direct JJTV still fails, its native report—not a stale TG label—will identify the next failing stage.

## 4.9.14 — identify the Android 9 TV failure instead of guessing

New device evidence: the same app plays all reported channels on mobile, but only some TG channels on the user's **Android 9 TV**; the failing channels have **neither picture nor audio**. The exact TV model, installed build number and platform error are not yet known. Android 9 by itself does not establish a particular DRM, codec or TLS defect, and the app's live path does not call WebView/Gecko. Changing a browser or lowering video quality cannot repair a native DRM session that never opens.

This is a **diagnostic build, not a claim that TV playback is fixed**. It leaves source resolution, key/token selection, decoder policy and DRM security requirements unchanged. It fixes an observability gap: repeated source attempts used to replace the original failure with a generic timeout.

- A bounded, in-memory report follows actual manifest/init/media downloads, HTTP/TLS errors, device-versus-HTTP-Date clock difference, selected/unsupported tracks, encryption scheme, platform DRM creation/session/key-request/key-response stages, Media3's keys-loaded event, both decoder names, first-frame and audio-position events. A successful key response is not labelled successful playback.
- `ObservedMediaDrm` delegates the same bytes, sessions, crypto configuration, secure-decoder requirements and reference counting to Media3's framework provider. Its callbacks only observe; recoverable DRM events do not independently trigger retries. Missing or broken platform services are recorded rather than silently reduced to a generic unsupported result.
- Original error codes from recent source attempts survive later provider errors and the overall startup deadline. The report shows app version, Android/API level, device model and ABI, so results can be tied to the installed build.
- **MENU / INFO** opens native playback details while loading or playing. **OK after a final error** opens the same report. Hold OK still reloads fresh metadata. This intentionally changes short MENU from reload to diagnostics.
- **Check DRM** in that dialog explicitly stops playback and runs a local ClearKey API self-test on a worker thread. It checks advertised support, creation, session opening, the same Common PSSH/JWK recipe used by the player, key-response acceptance and MediaCrypto construction. Only public synthetic test bytes are used; it performs no network request and never uses a real channel's keys. Sessions and DRM/crypto objects are released. A call that has not returned after ten seconds is reported, not allowed to block the UI or spawn more probes. Retry is blocked until that call returns; restart the app/TV if the system call remains stuck.
- A self-test **PASS does not decrypt/render a sample, test codecs, validate a channel licence or prove hardware playback**. Failure identifies which local API stage failed, independent of the website/network; a pass means the actual stream report is needed to investigate downloads, keys, encryption mode and decoding.
- No URLs, headers, cookies, key IDs, keys, raw exception messages or diagnostic files are persisted/uploaded. Reports contain whitelisted scalar labels, class names and numeric platform error codes. A frame/audio callback is only a player-reported event, not proof of visible/audible output.

Twenty-four new tests cover stage attribution, retained/bounded failures, redaction, exact DRM delegation and probe cleanup/success/failure phases under Robolectric API 28. These simulate contracts; they do not validate the affected TV's plugin.

**Next device test:** install 4.9.14, select a failing JJTV channel, open MENU (or OK after the error), photograph the stream report, then choose Check DRM and photograph its result. The first report separates manifest/network failure, missing keys, unsupported tracks and decoder startup; the second separates platform ClearKey API failure from stream-specific failure. Compare with the same channel on the working phone if needed. Do not infer a WebView cause or restore a live web player from the Android version alone.

## 4.9.13 — TV-only black/loading report

The user reports that JJTV streams play in the installed APK on a phone, while their TV connects but remains loading/black. This is device-specific evidence, not evidence of another missing server. **Live TV already uses native Media3 and a SurfaceView, not WebView or GeckoView.** This update leaves the working TG/JJTV resolution, request styles, tokens and DRM configuration unchanged. The TV model, Android version and decoder error have not yet been supplied; no particular hardware fault has been confirmed.

- `NativePlaybackPolicy` removes the forced asynchronous codec adapter. TVs use synchronous adapters; normal phones retain Media3's platform defaults. Decoder initialization fallback stays enabled.
- TVs prefer supported AVC/AAC formats, a 720p/30 fps/3 Mbps ceiling and a 24–32 MiB buffer target rather than the former 96 MiB target. Compatibility mode prefers 480p/low bitrate and the platform's software decoders, **if available**. These are preferences: a supported variant may exceed the quality constraints if no constrained variant exists, but tracks outside the renderer's reported capabilities are no longer forced. There is no bundled new codec engine.
- A decoder error or missing/frozen picture permits one same-source compatibility retry before normal source/provider recovery. Software preference retains the original secure/tunnelling requirements and keeps hardware decoders available. It does not bypass DRM or change licence security. HTTP and DRM errors continue through source recovery instead of being disguised as decoder errors.
- Full-screen preparation waits for a valid SurfaceView; the gate is single-shot and ignores released/replaced players. Surface callbacks are detached on release.
- `PlaybackHealth` uses a monotonic 15-second first-picture deadline (12 seconds for previews). READY, audio progress and about-to-render metadata do not substitute for the first rendered-frame callback or extend that deadline. Rebuffering or stopped video also has a 12-second watchdog. Explicit Radio channels may be audio-only.
- The whole full-screen startup/recovery cycle has a 60-second budget including metadata lookups and decoder/source retries. A first frame clears that budget; a later failure starts a new bounded recovery cycle. Only 30 seconds of real video progress (or progressing audio for Radio) resets the source-recovery counter. Compatibility preference is remembered per channel only after that stable interval.
- TV hero previews default to off unless the user explicitly enables them. Enabled previews use the same safer track/codec/buffer policy and non-resetting first-frame watchdog. Movies/series playback is unchanged.
- A final error stops loading, invalidates outstanding work and shows the device model, Android version, decoder mode and last initialized decoder name, without URLs, keys or tokens.

Build [39036283985](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39036283985) passed both ABI release builds and 140 offline test methods, including 23 new TV-policy/health/surface cases. The two separate live metadata diagnostics also passed, but do not decode video. The new tests cover health deadlines/progress, whole-cycle budgets, stale surface gates, actual Media3 track-selector parameters and secure/tunnelling-preserving decoder ordering. They do not exercise a physical TV codec. **The changed APK still needs verification on the affected TV**; successful compilation and these tests are not proof of rendered video on that hardware.

## 4.9.12 — real browser request comparison

A read-only Chrome trace of TG watch pages 1106 and 872 observed the actual automatic path: direct Jio manifest, the allinonereborn relay (a redirect to Jio), then the JJTV `/pind` frame. No extra unknown third media service was observed. This trace still received HTTP 451 and **did not render video** in the CI environment.

The trace exposed a concrete request mismatch: in TG's embedded JJTV player, Chrome sends its browser user-agent and **no Referer header**. The script's `User-Agent: plaYtv/7.1.5` and JioTV `Referer` assignments do not become those browser headers. The native app previously applied those assignments literally.

JJTV sources now have two explicit native request styles: browser-style Chrome/no-Referer first, and the already-supported declared native headers second. Both use the same source URL, source-owned keys and selected channel cookie. These are not new servers or separate player engines. The legacy style remains available to avoid discarding already-working channels; the successful style is remembered after a rendered frame. `omitReferrer` suppresses the header rather than sending an empty value, while signed queries and byte-range headers remain intact.

The browser trace is `.github/scripts/trace-tg-browser.cjs`. It runs outside the APK, blocks non-player ads/resources and reports only sanitized network/DRM counts. Request-style tests also inspect actual MockWebServer requests. This corrects an observed header difference, but without the device's error report or successful live video it is not proof that the remaining Star/Sony failure has been resolved.

## 4.9.11 — Star Sports / Sony YAY follow-up

The user confirmed that other channels now play, but Star Sports and Sony YAY still fail. The current TG response's upstream entry labelled **Server 3** is the allinonereborn relay already included in the native source list; TG's UI renumbers it Server 2. Inspection also confirmed that Sony YAY's two advertised key entries are identical duplicates (one distinct key ID), while Star Sports 1 HD has two distinct valid key IDs. These are not evidence of a missing third playback engine.

A concrete player compatibility difference was identified: Shaka's explicit `drm.clearKeys` configuration **overrides each audio/video variant's manifest DRM information** and supplies initialization key IDs. Media3's ClearKey manager, on the other hand, cannot acquire a session from Widevine-only scheme data just because a local licence callback is present. A missing `default_KID` can leave the default DASH parser with no Common/ClearKey initialization data.

`ClearKeyDashManifestParser` now supplies a Common PSSH containing the configured source's validated key IDs to audio/video representations, matching that explicit ClearKey mode. The licence still comes only from that source's supplied keys. Other DRM modes and sources without configured keys are unchanged; the manifest's encryption scheme, media metadata and segment locations are retained. Both full-screen playback and previews use this path.

Six Robolectric tests use the actual Media3 DASH parser to reproduce the incompatible baseline and check Widevine-only, missing-DRM, audio/video key initialization and preservation of the encryption scheme and track metadata. They do **not** decrypt a real channel. Real manifests still returned HTTP 451 from the CI runner, so this is a tested compatibility fix, not a confirmed diagnosis of the TV's error or a claim that these channels have been seen playing.

Reference implementations inspected: [Shaka 4.7.11 `configureClearKey_` / `init_`](https://github.com/shaka-project/shaka-player/blob/v4.7.11/lib/media/drm_engine.js) and [Media3 ClearKey scheme-data selection](https://github.com/androidx/media/blob/1.3.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/drm/DefaultDrmSessionManager.java).

## What changed after the reported playback failure

The 4.9.8 APK compiled, but the user reported that channels did not play. A successful build was not proof of working streams. Inspection of the current websites found:

- **TG discovery:** the deployed Rolldown server chunk uses a backtick-quoted function ID. The old single/double-quote-only matcher missed it, allowing resolution to fall back to the public API's incomplete single-source metadata.
- **TG server order:** the website tries its primary `urls` before the entries in `backups`, then renumbers the visible servers. An upstream entry labelled “Server 3” can now be the website's “Server 2”.
- **TG relay requests:** its player explicitly excludes the `allinonereborn` relay from its Jio query-token filter. Injecting the primary token into that relay does not match the website.
- **Current JJTV configuration:** the user explicitly authorized `https://jjtvxweb.pages.dev/` and `/pind?id=816` as additional native sources. Its player advertises a live channel JSON document, both legacy `keyId`/`key` fields and a newer `keys` array, and separate general and channel-specific PL/Star cookie endpoints. The sampled TG configuration omitted key data that the JJTV channel document supplied.

These rules are implemented as native metadata resolution, not by opening either website in a player. No live web player or separate backup player has been restored. GeckoView remains only for the existing movies/series functionality.

## Native playback and automatic selection

1. Keep exact catalogue IDs. V1/Jio and V2 IDs are different namespaces. **Never pass a V2 channel ID to JJTV.** The supplied JJTV example, 816, is Cartoon Network Hindi; Sony YAY Hindi remains 872.
2. For a V1 channel with no successful history, start with JJTV. A channel's own successful provider takes precedence; an explicit MENU source choice takes precedence for that tuning session. An unrelated channel's provider no longer changes this order. V2 always uses TG.
3. Resolve current metadata from that provider. An HTTP 401/403 permits one immediate, bounded fresh lookup for that provider; otherwise try its native sources automatically after native playback/DRM errors or startup/buffering watchdog timeouts.
4. If that list fails or the provider's 25-second lookup/startup window expires, load the other authorized provider's native configuration for the same V1 channel. Do not mix one provider's keys or credentials into the other provider's stream object.
5. After all sources fail, perform one forced refresh of the configuration and credentials, then try one more bounded pass. Report an error rather than retrying indefinitely or opening an iframe.
6. Remember a non-secret source fingerprint and provider only after a rendered frame, not merely `STATE_READY`. Remembering a working provider is channel-specific, not a global preference for unrelated channels. Previews also try subsequent native sources and respect channel/lifecycle generation guards.

Both full-screen playback and muted previews use the same Media3/ExoPlayer media-source builder and HTTP policy. Hindi-named channels prefer Hindi audio during full-screen playback. The initial buffer threshold is 750 ms, but network, server and decoder startup still determine actual time to first picture; “instant” playback is not guaranteed.

## TG metadata

- Use the last verified player function immediately when TG is selected. If necessary, inspect current watch/embed HTML, same-origin module preloads, lazy imports and Vite dependency maps. Support single, double and **static backtick** literals; do not evaluate interpolated strings or execute JavaScript.
- Stop crawling unrelated UI dependencies after the server chunk supplies candidates. Validate the selected function's response and channel ID. Prefer full player configuration over sparse detail metadata.
- Support JSON, Seroval/shared references, NDJSON and version-1 TanStack frames. Preserve primary URL order and each server's own DRM, headers and cookie.
- TG's public API remains a last metadata lookup, not a different playback engine. A returned URL with `drm: null` does not prove that encrypted media is playable.
- V2 uses `/api/public/v2/channels/{id}` only.

## JJTV metadata

`JjtvResolver` reads the current `/pind?id=…` configuration and discovers the advertised `CHANNELS_URL`, `COOKIE_PRIMARY`, `COOKIE_FALLBACK` and `COOKIE_STAR_SPORTS` locations. Filenames, stream URLs, keys and tokens are not bundled into the APK. Static request-header assignments are read for the retained native request style; the preferred embedded-browser style uses Chrome/no-Referer as observed in the real browser trace.

- Select the **exact** channel ID from the live JSON document. Do not remap to a similarly named channel or another language.
- Prefer a non-empty `keys` array over legacy single-key fields. Retain every valid distinct key ID; repeated IDs use the last supplied value, as in the website's map. Normalize the provider's hex/UUID/JWK representation for Media3's local ClearKey callback.
- Try `url`, `url1`, `url2`, `url3` in their advertised order, including the page's `bpk-tv//` path correction.
- For `jiotvpllive.cdn.jio.com`, use **only that channel's** cookie from the PL/Star document. Both successful records and failed probes can contain a usable signed redirect in `error_details.final_url` or `final_url`. When multiple probes exist for the same channel, prefer an advertised non-expired token to an expired first record. Never replace a missing Star token with the general cookie or a neighbouring channel's token.
- Other advertised URLs use the general cookie and, if configured, its distinct fallback. An empty fallback endpoint is not fetched as an HTML page. A regular source with no cookie can still use its own URL.
- Reuse channel/key and cookie responses for at most 30 seconds, shortened before a known token expiry. Cached credentials are not made younger by zapping to another channel. Cache player endpoint configuration for at most five minutes; changed endpoint/header configuration invalidates dependent data. A forced refresh bypasses these caches.
- Do not execute JJTV scripts, load adverts/trackers, call its country-detection service, use its worldwide-player route, or open an embed.

## HTTP, DRM and lifecycle boundaries

- Each playback session has its own token policy. Keep signed URL bytes, unrelated parameters and independently signed child URLs intact. A successful manifest redirect or HLS child playlist can teach that session its CDN's token; independently signed child media segments cannot overwrite it.
- Keep the selected website's non-secret origin consistent through redirects, while source-specific headers/cookies remain scoped to their exact origin and port. The TG relay exception is not silently applied to JJTV's policy.
- Keys and signed credentials remain in memory, including while an active DRM/playback session needs them. They are not written to preferences, bundled playlists, logs or diagnostic fixtures. Only public function hints, provider names and non-secret fingerprints are persisted.
- ClearKey is supplied to Media3 through a local callback with multi-session support. Explicitly advertised Widevine HTTPS licences remain supported. Missing keys are not invented, and a different server's keys are not borrowed.
- Channel changes, provider lookups, previews, manual reloads and activity teardown cancel or invalidate older jobs and callbacks. Old-channel audio/frames must not take ownership of a new selection.
- Error messages identify the provider and include HTTP or Media3 error codes where available, without displaying signed URLs or keys.

### Remote controls

| Key | Action |
| --- | --- |
| Up / Down | Previous / next channel |
| Right | Channel guide |
| OK | Show information; switch resolved native source when information is visible and multiple sources exist |
| Left | Next resolved source; reload if only one exists |
| Hold OK | Fetch fresh player configuration and credentials |
| Menu | Choose JJTV / TG / Automatic native source, or open Playback details |
| Info; OK after a final error | Open native playback diagnostics and the optional local ClearKey self-test |
| Back | Close native playback |

## Verification and limits

Offline tests cover first-picture/stall deadlines, whole-cycle retry budgets, surface preparation gates, native TV track/decoder policy, static-literal discovery, protocol parsing, multi-key and legacy ClearKey formats, exact Hindi/Star IDs, source order, PL-specific cookie selection, cache expiry and redeployment, header/redirect isolation, V1/V2 separation, provider preference, interruption and bounded recovery. Their URLs and key/token values are synthetic.

With JDK 17, Gradle 8.9 and Android SDK 35:

```sh
gradle testArm32DebugUnitTest testArm64DebugUnitTest
gradle assembleArm32Release assembleArm64Release
```

The workflow also runs a separate, non-gating live diagnostic because upstream/network availability is not deterministic:

```sh
gradle testArm64DebugUnitTest --tests 'com.aktv.app.TgLiveContractTest' -PliveTg=true
```

This uses the **production** resolvers, protocol parsers and stream transport. The successful integration check after adding JJTV resolved TG metadata for Sony YAY Hindi plus 23 Star Sports IDs, and JJTV metadata for those 24 IDs plus Cartoon Network Hindi (816). It confirmed supplied key data for the three requested examples. Both sites' manifest requests from the CI runner still returned **HTTP 451**, so these checks did **not** verify decoded video or audio. Some provider responses also contained expired tokens for Star variants; those are reported separately, and the app cannot manufacture a new signed token when the provider supplies none. Earlier live runs also encountered a transient metadata timeout; the live check's own status must be read separately from the APK build status.

`.github/scripts/inspect-tg-player.py` and `inspect-jjtv-player.py` inspect current public player contracts; `report-live-player.py` exposes redacted test summaries and individual live-test outcomes in GitHub Checks. Build success, a non-empty source list and available key data are not device-playback proof.

### Device acceptance checklist — still required

For the TV-specific report, first try the same JJTV channel that works on the phone. Confirm the installed version is **4.9.17** and record TV model, Android version, ABI, whether audio is audible, time to the first picture, and (if it fails) a photo of the new error/decoder line. Confirm that no-picture attempts enter compatibility mode and ultimately show an error instead of spinning indefinitely. Check sustained video for at least 30 seconds, rapid channel changes while the surface is being created, background/resume, and an explicitly enabled preview. Keep the known-working phone as a regression check.

- Confirm video, the expected audio language and sustained playback for Star Sports HD/SD, Hindi, Select, Tamil, Telugu and Kannada; Sony YAY Hindi; and Cartoon Network Hindi 816.
- Test a normal TG DASH channel, an HLS channel, a JJTV native source and a V2 channel. Verify that V2 never uses the Jio/JJTV namespace.
- Confirm automatic source/provider changes on failure, and that retuning starts with a source/provider that actually rendered a frame. No web player should appear.
- Exercise previews, rapid zapping, manual reload, guide navigation, background/resume, low-memory arm32 boxes and arm64 devices.
- Check expiry/network-loss recovery and the final error message without endless loops.

The app follows supported configuration, source, token, header and deployment-ID changes. It does not download executable player code or replace Media3 automatically. A different API schema, authentication mechanism, DRM system or codec can still require an APK update. Upstream outages, geographic restrictions and missing credentials cannot be repaired by the native client.

Release APKs currently use the repository's debug signing configuration. A signing-key mismatch with an older test APK can prevent an in-place install; uninstalling it would clear its saved settings and favourites. Do not treat a build artifact as an automatically installed update.
