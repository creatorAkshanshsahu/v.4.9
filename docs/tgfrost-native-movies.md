# TG Frost native Movies / Series — 4.9.18

Updated 2026-10-17. Provider: https://tgfrost.lovable.app

## Implementation

**Movies and Series now use native Media3 VOD, resolved through TG Frost.** This replaces the movie-only TGVID/GeckoView player. The native TMDB catalogue, detail pages and season/episode selection remain.

The feasibility investigation in commits `7616cc5`–`a0893fe` did **not** change production playback. Implementation followed the user's subsequent instruction to use Frost natively. Version 4.9.18 / code 35 contains that implementation. It does not restore the removed live-TV web backup.

### Playback flow

```text
Native TMDB catalogue → title / exact episode identity → Frost source API
                      → supplied DASH / HLS / MP4 URL → Media3 / Android decoders
```

- `MovieScreens.kt`: native title details; launches a structured TMDB identity instead of an embed URL. No Gecko prewarming.
- `MoviePlayerActivity.kt`: native touch and D-pad controls, seeking, source/quality/language choices, audio/subtitle selection, previous position, episode picker, next episode and optional auto-next.
- `FrostMovies.kt`: validated identities, bounded source parsing, fresh resolver requests, source-specific transport, resume/episode helpers and bounded recovery.
- `FrostMedia.kt`: explicit media MIME types, external subtitle configuration, VOD buffering and a ten-second back buffer. No live offset or live channel DRM is attached.
- The general `BrowserActivity` remains available for its unrelated browser functionality. Neither native movie playback nor native live playback initializes Gecko.

## Source contract

The inspected `tgflixStreams-Cv-shetv.js` client uses GET requests with `cache: no-store`:

```text
/api/sources?server=tgflix&type=movie&tmdb_id=<TMDB_ID>
/api/sources?server=tgflix&type=tv&tmdb_id=<TMDB_ID>&season=<SEASON>&episode=<EPISODE>
```

TG OUT uses `server=tgout`. The response contains `sources[]`. Observed fields include `id`, `scraperId`, `scraperName`, `type`, `url`, `quality`, `language`, `mirrors`, and `tracks`. Expiring media URLs and header values are deliberately not recorded here or persisted in preferences.

| Website choice | Observed implementation | Android handling |
| --- | --- | --- |
| TG FROST / `tgflix` | Resolves media sources | Default native resolver |
| TG OUT / `tgout` | Same source API | Native alternative; available through Sources and bounded automatic failover |
| TG BOLT / `tgbolt` | `vidbolt.xyz` iframe in the inspected embed component | **Not** a native source; never opened as a fallback |
| TGVID | Web embed | Previous Android movie player, now replaced |

The website's [embed documentation](https://tgfrost.lovable.app/docs) calling a web component “Native player” is not evidence that an iframe can be played by Media3.

### Formats, headers and subtitles

- DASH: `application/dash+xml`; HLS: `application/x-mpegURL`; MP4: `video/mp4`. Extensionless DASH relay URLs retain their explicit type.
- Provider-returned relay URLs, encoded queries and byte ranges are preserved. No invented proxy or token/signature rewriting.
- Supplied authorization/cookie/custom headers are scoped to the original source origin, including when following redirects, accessing child URLs or trying a different-origin mirror. They are not inherited from live TV or another movie session.
- The source API is anonymous in the inspected samples. The app does not ask for credentials, create a cookie jar or execute site scripts.
- Explicit unsupported DRM metadata is not replaced with a guessed licence or live-channel keys. Container encryption still requires genuine platform/source support.
- Observed external subtitles contain only `url`, `label`, and `lang`. The supplied `embed.reelsdownload.online` subtitle service returned `text/vtt` with a `WEBVTT` signature for both samples. This specific verified service is mapped to WebVTT; other untyped endpoints are not blindly guessed.
- Explicit WebVTT, SRT, SSA/ASS and TTML tracks are supported by their MIME/extension. Thumbnail tracks are excluded. Embedded audio and text tracks are handled by Media3.
- An absent `hevc` field remains **unknown**, not “AVC.” Actual manifest and renderer support determines whether a source can play.

## Native behavior and recovery

- Exact TMDB movie or series/season/episode identity is retained across retries. A late response for a previous episode cannot replace the current player.
- Source selection, seek controls and audio/subtitle/quality dialogs work through native views. Remote Menu opens Sources; Info opens redacted playback details. Media keys and ten-second seek keys are handled natively.
- Native progress is stored in milliseconds per title **and episode**, never as a resolved URL. Near-start and completed positions resume from the beginning. Existing last-season/episode preferences are retained. Old TGVID website-owned progress is not imported into the new native position store.
- Auto-next uses only known TMDB episode metadata, including season transitions; it never fabricates an episode number.
- Adaptive/relay sources precede direct MP4 alternatives. A rejected media URL can trigger one fresh lookup per provider; unchanged URL/header combinations are not looped indefinitely. Changed supplied headers count as a new request even if the URL is unchanged.
- Automatic attempts are bounded: at most 16 distinct requests, at most one compatible-decoder retry per provider on TVs, a 30-second no-picture deadline per attempt and a two-minute startup cycle. Intentional pause does not consume the picture deadline. HTTP 429 stops automatic retries; source API 401/403 does not trigger an access-control workaround.
- Native audio/video formats unavailable on the device can move to another supplied source rather than silently accepting a video-only or black-screen stream. This does not add missing decoder implementations.
- Codec/memory choices share the existing device policy, but VOD has its own buffers, a seek back buffer and aspect-ratio-aware limits. Cinematic movies are not constrained to a 16:9 width assumption.
- Activity stop/finish cancels the resolver, invalidates callbacks, saves position, detaches the surface and releases Media3. No browser surface remains behind the detail screen.

## Verified evidence

### Source inspection

Read-only inspection runs `39053647071` and `39054596333`:

| Sample | Source API | Detailed HTTP checks |
| --- | --- | --- |
| Movie 1288069, Tenzing | 200; 12 sources | Complete DASH metadata; direct MP4 403 |
| Series 213562, S1 E1, Crystal Lake | 200; 4 sources | Complete DASH metadata; direct MP4 403 |
| Movie 253412, Everest, Frost | 404 | No native source established |
| Same movie / sampled episode, TG OUT | 404 | No native source established for those lookups |

Both sampled MPDs have HEVC video, AAC audio and no `ContentProtection` elements. Absent JSON HEVC flags do not change that finding. The subtitle probes returned HTTP 200, `text/vtt`, and the WebVTT signature.

### Native backend verification

Build run [39054912949](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39054912949), job `117460801208`, validated the production resolver, its OkHttp transport and **Media3's own DASH parser**:

| Check | Movie 1288069 | Series 213562 / S1 E1 |
| --- | --- | --- |
| Native source count | 12 | 4 |
| Recognized external subtitles | 9 | 1 |
| MPD response / parser | 200 / parsed | 200 / parsed |
| MPD bytes | 61,306 | 44,993 |
| First inspected video format | HEVC, 2580×1080 | HEVC, 1920×1080 |
| Manifest DRM present | No | No |
| Initialization byte-range response | 206 | 206 |
| Media byte-range response | 206 | 206 |

Only bounded prefixes were read; no movie file, keys, credentials or signed URLs were saved. **These checks do not decode frames/audio, verify native seeking, or establish playback on a physical phone/TV.** This run validated the backend foundation; complete-app build/artifact verification is recorded in PR #1 with the delivered APK.

## Tests and remaining device checks

Deterministic tests cover exact API identities, URL/schema guards, MIME selection, mirrors/header scoping, redirects/ranges, subtitle mapping, native navigation, progress, episode boundaries, bounded recovery and native UI inflation. UI inflation tests do not start the resolver or claim decoder playback. Both arm32 and arm64 APK variants run the offline suite. The two opt-in native Frost checks remain separate from the two live-TV metadata checks.

Physical acceptance checks still required:

1. Start a movie and verify **both picture and audio** on the phone and box.
2. Seek forward/backward, pause/resume and reopen at the stored position.
3. Select alternate source/quality, embedded audio and external subtitles.
4. Start the exact selected episode, advance across a known episode/season boundary, and test auto-next Off/On.
5. Exit while resolving/buffering, return from Home, and check for audio/surface leftovers.
6. Confirm errors for unavailable titles, HTTP-denied MP4s and unsupported HEVC are bounded and actionable.

HEVC support, provider availability and HTTP-denied MP4s remain real limitations. Successful API/manifest/range checks are not a promise that every catalogue title or source plays on every device.
