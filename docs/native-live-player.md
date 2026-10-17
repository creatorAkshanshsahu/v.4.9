# Native live TV (4.9.8)

## Playback path

Both live TV and 24/7 channels now play only in Media3/ExoPlayer. The live-TV web-player switch, automatic web fallback, separate backup service, guessed HTTP profiles, and bundled expiring playlist have been removed. GeckoView remains for the existing movies/series screens; it is not a live-TV fallback.

1. Use TG's catalogue IDs without fuzzy name matching. V1 and V2 IDs have separate cache namespaces.
2. For V1, use the last verified TG player function immediately. If it disappears or fails to return sources, inspect the current watch/embed page, module preloads, lazy imports and Vite dependency maps. Discover and validate the deployed GET player functions instead of embedding a build-specific hash in the APK.
3. Prefer the player configuration over an incomplete channel-detail response. Fall back to TG's public channel API for native stream metadata only. V2 uses `/api/public/v2/channels/{id}` exclusively, never the V1 resolver.
4. Parse JSON, Seroval (including shared references), NDJSON and version-1 binary TanStack frames. Retain source order, type, headers and each source's own ClearKey data. Never borrow a sibling server's keys or token. An explicitly supplied Widevine HTTPS licence is also supported by the native player.
5. Start native playback with a 750 ms initial buffer threshold. Prefer Hindi audio tracks on channels named Hindi, including Sony YAY Hindi. All channels otherwise use the same resolver; there is no static Star Sports URL list or language remapping.

## Requests and refreshes

- Each source has its own HTTP policy. Cookies/credentials from one relay are not forwarded to a different origin. Previews and full-screen playback do not reset a global cookie jar.
- Carry `__hdnea__` to child media requests on the same origin, preserving unrelated parameters, signed URL bytes and any independently signed child URL. A fresh signed manifest redirect teaches that playback session the final CDN's token. Never replace a relay's entire query or apply one token to every host.
- Resolve URLs/keys at playback time. Metadata is held in memory for at most 30 seconds, shortened to ten seconds before a known token expiry. Only the verified function ID and a non-secret server fingerprint are saved to preferences.
- Recheck player chunks approximately every five minutes while the app is open. Recheck channel lists while browsing, including retrying an initially unavailable list.
- HTTP 401/403/410 triggers one fresh configuration lookup. Other failures advance through TG's native sources. After those sources and one forced refresh are exhausted, show an actionable error rather than opening another player or retrying indefinitely.
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

The GitHub build workflow runs the unit tests and builds both APKs for pull requests and its existing build triggers.

### Device release checklist (still required)

- Test Star Sports HD/SD, Hindi, Select, Tamil, Telugu and Kannada feeds, plus Sony YAY Hindi; confirm both video and the expected audio language.
- Test an ordinary V1 DASH channel, an HLS channel, TG-provided relay sources, and a V2 24/7 channel.
- Switch sources, hold OK to reload, zap rapidly, enter/exit the guide, and background/resume the app. No old-channel audio or web player should remain.
- Check muted previews followed by full-screen playback, low-memory arm32 boxes and arm64 devices.
- Confirm recovery from expired metadata, network loss, unavailable channels and sustained playback without reload loops.

The coding sandbox can run the JVM tests but has no Android SDK/emulator or device. An APK build and end-to-end channel playback are not claimed as verified there.
