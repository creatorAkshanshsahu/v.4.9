# TG Frost: native Movies / Series feasibility

Inspected 2026-10-17. Site: https://tgfrost.lovable.app

## Conclusion

**A native integration is feasible in principle using Frost's resolved media sources—not by placing its JavaScript player inside Media3.** The current source API returned DASH and MP4 entries for both a movie and a TV episode. Complete DASH manifests were accessible; the direct MP4 checks returned HTTP 403. Media3 supports these container/streaming formats, but the sampled DASH video is HEVC/H.265, so actual device decoding must be tested.

This is an investigation, not a movie-player replacement. Android production code, the current TGVID/GeckoView movie player, and version 4.9.17 remain unchanged. No phone or TV-box playback has been verified.

## What the app uses now

- `Movies.kt` supplies the native TMDB catalogue, title IDs, seasons and episodes.
- Its playback URLs target `https://tgvid.lovable.app/embed`, **not TG Frost**.
- `MoviePlayerActivity` in `MovieScreens.kt` opens those URLs in GeckoView and forwards remote keys to the web player.
- The removed live-TV browser/backup player is separate from this movie implementation.

## What Frost actually does

The public `tgflixStreams-Cv-shetv.js` client calls a same-origin GET endpoint with `cache: no-store`:

```text
/api/sources?server=tgflix&type=movie&tmdb_id=<TMDB_ID>
/api/sources?server=tgflix&type=tv&tmdb_id=<TMDB_ID>&season=<SEASON>&episode=<EPISODE>
```

For TG OUT it uses `server=tgout`. The response must contain a nonempty `sources` array. The client keeps results and in-flight requests in memory and supports a forced refresh. It explicitly handles resolver errors, including HTTP 400, 403 and 429.

Observed source fields include `id`, `scraperId`, `scraperName`, `type`, `url`, `quality`, `language`, `mirrors` and `tracks`. Signed URL values and track URLs are deliberately omitted from this report.

### Server names are not equivalent implementations

| Choice | Current client implementation | Native Android implication |
| --- | --- | --- |
| TG FROST (`tgflix`) | Resolves `sources` and passes them to its own web video component | Suitable candidate for a Media3 source adapter |
| TG OUT (`tgout`) | Uses the same source API/component path | Adapter is feasible structurally; sampled lookups returned 404 |
| TG BOLT (`tgbolt`) | Current **embed component** skips source resolution and opens a `vidbolt.xyz` iframe | Cannot be supplied to Media3 as a video URL; requires a separately verified media-source contract |
| TGVID | Also appears in the main watch-player choices as a web embed | This is the web-based path the Android app currently uses |

The [embed documentation](https://tgfrost.lovable.app/docs) labels all three embed servers “Native player,” but the inspected embed implementation treats TG BOLT differently. A website's use of the word “native” does not mean an Android Media3 component.

## Observed API results

[Source inspection run 39053220250](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39053220250), job/check 117455861288:

| Provider | Sample | API result | Returned source types | Bounded first-source check |
| --- | --- | --- | --- | --- |
| TG FROST | Tenzing, movie 1288069 | HTTP 200; 12 source entries | DASH and MP4 | HTTP 206; `application/dash+xml`; 4 KiB read |
| TG FROST | Crystal Lake, TV 213562 / S1 E1 | HTTP 200; 4 source entries | DASH and MP4 | HTTP 206; `application/dash+xml`; 4 KiB read |
| TG FROST | Everest, movie 253412 | HTTP 404 | Not established | Not attempted |
| TG OUT | Everest, movie 253412 | HTTP 404 | Not established | Not attempted |
| TG OUT | Crystal Lake, TV 213562 / S1 E1 | HTTP 404 | Not established | Not attempted |

These GETs used no account cookie or Authorization header. Successful source entries did not supply custom request headers or explicit DRM configuration. The first DASH URLs were provider-supplied CinemaOS relay URLs. They must be treated as returned media URLs, not replaced with a guessed upstream path.

A 404 is a result for that lookup at that time—not proof that the whole provider is permanently unavailable. Returned quality labels and track counts are metadata, not proof of decoded resolution, language availability or playback.

### Detailed format checks

[Manifest/MP4 inspection run 39053415954](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39053415954), job/check 117456432867, inspected one DASH and one MP4 entry for each successful TG FROST lookup:

| Sample | DASH response | Manifest contents | Direct MP4 response |
| --- | --- | --- | --- |
| Movie 1288069 | HTTP 206; complete 61,306-byte MPD | 4 representations; `SegmentTemplate`; HEVC video and AAC audio | HTTP 403 / HTML; no MP4 header confirmed |
| TV 213562 / S1 E1 | HTTP 206; complete 44,993-byte MPD | 4 representations; `SegmentTemplate`; HEVC video and AAC audio | HTTP 403 / HTML; no MP4 header confirmed |

- Video codec strings were `hev1...`; audio was `mp4a.40.2`. Do **not** interpret a missing HEVC flag in source JSON as AVC/H.264. The initial inspection summary normalized an absent flag to `false`; the inspector now preserves it as `null` (unknown). The actual codec evidence is the MPD.
- Neither inspected complete MPD contained a `ContentProtection` element. This is narrower than claiming that every Frost source is unencrypted or that an Android device has successfully decrypted/decoded it.
- No media initialization/segment playback, native decoder output, audible audio, subtitle rendering or nonzero seeking was verified. These are still required acceptance tests.
- The MP4 403 responses were not retried with guessed credentials or bypassed. They do not prove that MP4 fails in the user's normal browser; request requirements or availability may differ.
- HLS is supported by the app's dependencies, but these successful lookups returned DASH/MP4, not a verified HLS source. TG OUT's sampled 404 responses do not establish its media format.

### Browser observation limitation

The separate [browser inspection run 39052898876](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39052898876) navigated to `about:blank` before playback was established. Ads, nonpublic destinations and unknown external script/document loads were restricted by the probe. The cause of the blank navigation was not established. That run is **not** evidence of successful browser playback, nor proof that the real site cannot play. The subsequent source API checks were ordinary independent HTTP requests, not a successful browser-playback trace.

## Recommended native design

```text
Existing TMDB movie / season / episode selection
    → Frost source resolver
    → Typed sources, mirrors, quality/language and track metadata
    → Dedicated Media3 VOD player
```

1. Keep the existing catalogue and pass structured TMDB/type/season/episode values into the player.
2. Resolve fresh sources for the selected title/episode. Preserve the returned URL and its encoding; do not bundle expiring URLs or substitute a different title/episode.
3. **Set Media3's MIME type explicitly from the source type.** A DASH URL behind a worker/proxy need not end in `.mpd`; extension-based guessing can choose the wrong extractor. Map DASH to `application/dash+xml`, HLS to `application/x-mpegURL`, and MP4 to `video/mp4` when those formats are actually returned.
4. Implement movie-specific play/pause, seeking, duration, resume, quality/language selection, subtitle handling, D-pad navigation and next-episode controls. HTML controls and browser `blob:` URLs are not reusable Android playback components.
5. Retry only bounded alternatives/mirrors for the same title and refresh expired sources. Respect API access/rate limits. Do not turn an iframe URL into a guessed media URL.
6. Select using actual manifest/container codecs and device capabilities. Prefer a compatible AVC alternative when genuinely available; do not promise one from a missing HEVC flag. Reuse appropriate general device/surface policies, **not** Jio/JJTV authentication, ClearKey overrides, live-edge offsets or live-channel watchdog settings.
7. Test picture **and** audio, nonzero seeking, subtitle/audio switching, resume, episode changes and resource cleanup on the phone and the actual TV box. Check real decoder capabilities rather than relying on the box's displayed Android version.

The public documentation supports iframe embedding; it is not a stability or permission guarantee for a standalone source API. Confirm permitted API use before distributing an integration. Provider availability, URL lifetime and schema can change independently of the app.

## Reproduction and privacy

- Inspector: `.github/scripts/inspect-tgfrost-player.cjs`.
- `node --test .github/scripts/inspect-tgfrost-player.test.cjs` exercises redaction, request validation and manifest summaries: **12 tests passed** locally and in the detailed inspection workflow.
- `node .github/scripts/inspect-tgfrost-player.cjs --api` checks the five API cases above.
- `node .github/scripts/inspect-tgfrost-player.cjs --api --details` limits itself to the two successful TG FROST cases and inspects one source per declared format: at most 128 KiB for manifest metadata or 4 KiB for an MP4 prefix. It does not decode video or save movie files.
- Browser mode is optional, requires Playwright/Chrome, and is not needed for the direct API check.
- The separate `Inspect movie player` workflow reports redacted schemas/statuses only. No raw source bodies, signed URL values, cookies, DRM keys, licence bodies or browser storage are committed or printed.

## Public source references

- [Embed documentation](https://tgfrost.lovable.app/docs)
- [Source resolver client](https://tgfrost.lovable.app/assets/tgflixStreams-Cv-shetv.js)
- [Embed component](https://tgfrost.lovable.app/assets/Embed-7OPRXmwX.js)
- [Watch component](https://tgfrost.lovable.app/assets/Watch-CD57SPkV.js)
- [Player bundle](https://tgfrost.lovable.app/assets/TgflixPlayer-OGmWq2vS.js)

Asset hashes describe the inspected deployment and are not stable API endpoints.
