# Native live TV (4.9.9)

## Correction after the 4.9.8 playback failure

The previous APK compiled but the user reported that channels did not play. Live inspection found a concrete discovery bug: the deployed Rolldown server chunk uses a **backtick-quoted function ID**, which the old single/double-quote-only matcher ignored. That could leave the app using the public API's incomplete single-source metadata instead of the website's complete server configuration.

This update:

- Recognizes static backtick strings in function calls, metadata and lazy imports without evaluating JavaScript. Interpolated strings are not treated as endpoints.
- Stops crawling unrelated UI dependencies once the actual server chunk supplies candidate functions.
- Tries primary URL alternatives first, followed by TG's advertised servers, with the same server numbering as the website. Credentials remain independent.
- Preserves the website's `allinonereborn` relay exception: do not inject the primary Jio token into that relay's own query. A successful redirect may still supply independent CDN credentials.
- Advances to the next native server on failure **before** doing a global refresh, including on HTTP 401/403. This avoids restarting a rejected server while another advertised server has not been tried. The UI identifies automatic selection.

This is not a claim that all channels now play. The live native resolver check discovered the deployed function and returned metadata for Sony YAY Hindi and all 23 listed Star Sports IDs without falling back to the public API. Both the website-style and native manifest requests for channels 872/1106 returned **HTTP 451 from the CI runner**. Those responses did not allow media decoding to be tested. The sampled configuration also supplied no ClearKey keys; the app must not invent keys or borrow them from another server.

## Playback path

Both live TV and 24/7 channels now play only in Media3/ExoPlayer. The live-TV web-player switch, automatic web fallback, separate backup service, guessed HTTP profiles, and bundled expiring playlist have been removed. GeckoView remains for the existing movies/series screens; it is not a live-TV fallback.

1. Use TG's catalogue IDs without fuzzy name matching. V1 and V2 IDs have separate cache namespaces.
2. For V1, use the last verified TG player function immediately. If it disappears or fails to return sources, inspect the current watch/embed page, module preloads, lazy imports and Vite dependency maps (single, double and static backtick literals). Discover and validate the deployed GET player functions instead of embedding a build-specific hash in the APK.
3. Prefer the player configuration over an incomplete channel-detail response. Fall back to TG's public channel API for native stream metadata only. V2 uses `/api/public/v2/channels/{id}` exclusively, never the V1 resolver.
4. Parse JSON, Seroval (including shared references), NDJSON and version-1 binary TanStack frames. Retain source order, type, headers and each source's own ClearKey data. Never borrow a sibling server's keys or token. An explicitly supplied Widevine HTTPS licence is also supported by the native player.
5. Start native playback with a 750 ms initial buffer threshold. Prefer Hindi audio tracks on channels named Hindi, including Sony YAY Hindi. All channels otherwise use the same resolver; there is no static Star Sports URL list or language remapping.

## Requests and refreshes

- Each source has its own HTTP policy. Cookies/credentials from one relay are not forwarded to a different origin. Previews and full-screen playback do not reset a global cookie jar.
- Carry `__hdnea__` to child media requests on the same origin, preserving unrelated parameters, signed URL bytes and any independently signed child URL. A fresh signed manifest redirect teaches that playback session the final CDN's token. Never replace a relay's entire query or apply one token to every host.
- Resolve URLs/keys at playback time. Metadata is held in memory for at most 30 seconds, shortened to ten seconds before a known token expiry. Only the verified function ID and a non-secret server fingerprint are saved to preferences.
- Recheck player chunks approximately every five minutes while the app is open. Recheck channel lists while browsing, including retrying an initially unavailable list.
- Playback failures, including HTTP 401/403/410, advance through TG's current native sources first. When the list is exhausted, resolve one fresh configuration and try again. A single-source channel therefore refreshes immediately. After that bounded retry, show an actionable error rather than opening another player or retrying indefinitely.
- Manual reload bypasses cached metadata and re-discovers the player configuration. Rapid channel changes cancel queued resolution and guard results with a playback generation. Callbacks from released players are ignored.

### Remote controls

| Key | Action |
| --- | --- |
| Up / Down | Previous / next channel |
| Right | Channel guide |
| OK | Show information; switch source when information is visible and multiple sources exist |
| Left | Next TG source (reload if only one exists) |
| Hold OK / Menu | Fetch a fresh stream and player configuration |
| Back | Close native playback |

## Automatic-update boundaries

The app follows supported **configuration, token, source and deployment-ID changes**. It does not download executable app updates or replace Media3 when TG changes its website. A new API argument schema, an incompatible serialization/DRM protocol, login requirements or a new codec can still require an APK update. Only advertised read-only GET functions are probed; arbitrary POST functions and remote scripts are not executed.

TG's public documentation states that upstream streams are restricted to India and that tokens expire. A native client cannot repair an unavailable upstream or invent missing DRM data. Returning a manifest URL, especially with `drm: null`, is not proof that a channel plays. Real playback must be checked on a supported Android device and network.

## Verification

Host-side tests cover protocol decoding, independent per-source DRM, redirects, signed query preservation, header isolation, source discovery/redeployment, cache expiry, V1/V2 separation, exact ID resolution for the 23 Star Sports entries and Sony YAY Hindi (872), interruption, and bounded recovery. Fixtures are synthetic and contain no working stream credentials.

With JDK 17, Gradle 8.9 and Android SDK 35 installed:

```sh
gradle testArm32DebugUnitTest testArm64DebugUnitTest
gradle assembleArm32Release assembleArm64Release
```

The GitHub build workflow runs the offline unit tests and builds both APKs. It also runs a separate, non-gating live diagnostic because upstream availability and geography are not deterministic:

```sh
gradle testArm64DebugUnitTest --tests 'com.aktv.app.TgLiveContractTest' -PliveTg=true
```

The live test uses the **production** resolver, protocol decoder and stream HTTP policy. It checks exact IDs and that the public API was not substituted for the player function, then inspects actual manifest responses for 872/1106. It does not launch Android or prove that video/audio decodes. The regular offline test run skips this opt-in check.

`.github/scripts/inspect-tg-player.py` independently inspects current website code and its manifest request rules. Both diagnostics expose only redacted summaries in GitHub check annotations. Keys, cookies and signed query values are not logged or saved as fixtures. A successful build or metadata check must not be advertised as a successful device playback test.

### Device release checklist (still required)

- Test Star Sports HD/SD, Hindi, Select, Tamil, Telugu and Kannada feeds, plus Sony YAY Hindi; confirm both video and the expected audio language.
- Test an ordinary V1 DASH channel, an HLS channel, TG-provided relay sources, and a V2 24/7 channel.
- Switch sources, hold OK to reload, zap rapidly, enter/exit the guide, and background/resume the app. No old-channel audio or web player should remain.
- Check muted previews followed by full-screen playback, low-memory arm32 boxes and arm64 devices.
- Confirm recovery from expired metadata, network loss, unavailable channels and sustained playback without reload loops.

Android compilation and unit tests are run in GitHub Actions. The coding sandbox has no Android SDK/emulator or attached TV, and the CI manifest requests were blocked with HTTP 451. On-device first-frame, audio and sustained playback verification remains required.
