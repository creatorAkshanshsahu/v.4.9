# Native live TV (4.9.11)

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
2. For a channel with no history, start with the last native provider that actually rendered video, or TG on a first installation. A channel's own successful provider takes precedence over that general hint. V2 always uses TG.
3. Resolve current metadata from that provider. Try its native sources automatically in order, including after HTTP 401/403, a native playback/DRM error, or a startup/buffering watchdog timeout.
4. Only if that list fails, load the other authorized provider's native configuration for the same V1 channel. Do not delay a working TG source while looking up JJTV. Do not mix one provider's keys or credentials into the other provider's stream object.
5. After all sources fail, perform one forced refresh of the configuration and credentials, then try one more bounded pass. Report an error rather than retrying indefinitely or opening an iframe.
6. Remember a non-secret source fingerprint and provider only after a rendered frame, not merely `STATE_READY`. New channels can use the last working V1 provider without repeating failures from a known-unavailable provider. Previews also try subsequent native sources and respect channel/lifecycle generation guards.

Both full-screen playback and muted previews use the same Media3/ExoPlayer media-source builder and HTTP policy. Hindi-named channels prefer Hindi audio during full-screen playback. The initial buffer threshold is 750 ms, but network, server and decoder startup still determine actual time to first picture; “instant” playback is not guaranteed.

## TG metadata

- Use the last verified player function immediately when TG is selected. If necessary, inspect current watch/embed HTML, same-origin module preloads, lazy imports and Vite dependency maps. Support single, double and **static backtick** literals; do not evaluate interpolated strings or execute JavaScript.
- Stop crawling unrelated UI dependencies after the server chunk supplies candidates. Validate the selected function's response and channel ID. Prefer full player configuration over sparse detail metadata.
- Support JSON, Seroval/shared references, NDJSON and version-1 TanStack frames. Preserve primary URL order and each server's own DRM, headers and cookie.
- TG's public API remains a last metadata lookup, not a different playback engine. A returned URL with `drm: null` does not prove that encrypted media is playable.
- V2 uses `/api/public/v2/channels/{id}` only.

## JJTV metadata

`JjtvResolver` reads the current `/pind?id=…` configuration and discovers the advertised `CHANNELS_URL`, `COOKIE_PRIMARY`, `COOKIE_FALLBACK` and `COOKIE_STAR_SPORTS` locations. Filenames, stream URLs, keys and tokens are not bundled into the APK. Static request-header assignments are read from the player configuration instead of trying guessed HTTP profiles.

- Select the **exact** channel ID from the live JSON document. Do not remap to a similarly named channel or another language.
- Prefer a non-empty `keys` array over legacy single-key fields. Retain every valid distinct key ID; repeated IDs use the last supplied value, as in the website's map. Normalize the provider's hex/UUID/JWK representation for Media3's local ClearKey callback.
- Try `url`, `url1`, `url2`, `url3` in their advertised order, including the page's `bpk-tv//` path correction.
- For `jiotvpllive.cdn.jio.com`, use **only that channel's** cookie from the PL/Star document. Both successful records and failed probes can contain a usable signed redirect in `error_details.final_url` or `final_url`. When multiple probes exist for the same channel, prefer an advertised non-expired token to an expired first record. Never replace a missing Star token with the general cookie or a neighbouring channel's token.
- Other advertised URLs use the general cookie and, if configured, its distinct fallback. An empty fallback endpoint is not fetched as an HTML page. A regular source with no cookie can still use its own URL.
- Reuse channel/key and cookie responses for at most 30 seconds, shortened before a known token expiry. Cached credentials are not made younger by zapping to another channel. Cache player endpoint configuration for at most five minutes; changed endpoint/header configuration invalidates dependent data. A forced refresh bypasses these caches.
- Do not execute JJTV scripts, load adverts/trackers, call its country-detection service, use its worldwide-player route, or open an embed.

## HTTP, DRM and lifecycle boundaries

- Each playback session has its own token policy. Keep signed URL bytes, unrelated parameters and independently signed child URLs intact. A successful signed redirect can teach that session the final CDN's token.
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
| Hold OK / Menu | Fetch fresh player configuration and credentials |
| Back | Close native playback |

## Verification and limits

Offline tests cover static-literal discovery, protocol parsing, multi-key and legacy ClearKey formats, exact Hindi/Star IDs, source order, PL-specific cookie selection, cache expiry and redeployment, header/redirect isolation, V1/V2 separation, provider preference, interruption and bounded recovery. Their URLs and key/token values are synthetic.

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

- Confirm video, the expected audio language and sustained playback for Star Sports HD/SD, Hindi, Select, Tamil, Telugu and Kannada; Sony YAY Hindi; and Cartoon Network Hindi 816.
- Test a normal TG DASH channel, an HLS channel, a JJTV native source and a V2 channel. Verify that V2 never uses the Jio/JJTV namespace.
- Confirm automatic source/provider changes on failure, and that retuning starts with a source/provider that actually rendered a frame. No web player should appear.
- Exercise previews, rapid zapping, manual reload, guide navigation, background/resume, low-memory arm32 boxes and arm64 devices.
- Check expiry/network-loss recovery and the final error message without endless loops.

The app follows supported configuration, source, token, header and deployment-ID changes. It does not download executable player code or replace Media3 automatically. A different API schema, authentication mechanism, DRM system or codec can still require an APK update. Upstream outages, geographic restrictions and missing credentials cannot be repaired by the native client.

Release APKs currently use the repository's debug signing configuration. A signing-key mismatch with an older test APK can prevent an in-place install; uninstalling it would clear its saved settings and favourites. Do not treat a build artifact as an automatically installed update.
