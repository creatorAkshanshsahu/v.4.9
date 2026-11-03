# How TG handles the affected channels

Checked against the deployed TG player code on 17 October 2026. This is a source/request trace, not a claim that the streams decoded successfully in CI.

## Channels and actual source sequence

| Channel | V1 ID | First TG source | Second TG source | Final website fallback |
|---|---:|---|---|---|
| Star Sports 1 HD | 1106 | Jio PL DASH | TG-advertised redirect/relay | JJTV `/pind`, same ID |
| Star Sports 1 Hindi HD | 1108 | Jio PL DASH | TG-advertised redirect/relay | JJTV `/pind`, same ID |
| Star Sports 1 Hindi (SD) | 362 | Jio PL DASH | TG-advertised redirect/relay | JJTV `/pind`, same ID |
| Sony YAY Hindi | 872 | Jio mobile DASH | TG-advertised redirect/relay | JJTV `/pind`, same ID |

```text
TG /watch/<exact channel ID>
    │
    ├─ GET current discovered server function, data: { id }
    │      React Query staleTime: 60 seconds; lookup retry: 1
    │
    ├─ Server 1: primary urls[] → Shaka → Jio DASH
    │      try the URLs in order; use this source's cookie/keys
    │
    ├─ Server 2: backups[] → Shaka → advertised relay → signed Jio URL
    │      do not replace the relay's own query
    │
    └─ JJTV iframe: /pind?id=<same ID>, referrerPolicy=no-referrer
           fetch JJTV's catalogue, that channel's keys and appropriate cookie
           a new Shaka player inside this frame; not another TG media URL
```

The upstream backup is labelled **“Server 3”** in sampled metadata. TG's `servers` module renumbers the visible entries sequentially: **Server 1 / Server 2**. It is not a missing third numbered provider that the app needs to guess.

The sampled TG configurations for these channels do not supply explicit ClearKeys on the primary/relay entries. That does not establish that the media is unencrypted, or that a manifest cannot describe DRM. JJTV separately supplies one or more keys for the corresponding channel. Its keys must not be borrowed to patch an unrelated TG URL.

## TG's player rules

The deployed `TgPlayer` module:

1. Creates Shaka, installs its browser polyfills, and attaches the video element.
2. Applies `drm.clearKeys` when that particular source provides a nonempty map; otherwise uses its single `keyId`/`key` pair when supplied.
3. Sets `streaming.lowLatencyMode = true` and `rebufferingGoal = 4` seconds. It otherwise lets Shaka/browser capability and ABR selection operate; it does not install a special Android TV codec.
4. Registers a request filter for **both manifests and media segments**. If the source has an active cookie and the request is not for the `allinonereborn` relay, the filter replaces the query with that cookie. It is not a one-time manifest-only token attachment, nor a device-clock expiry check.
5. Tries each URL in the current source. A critical Shaka error (`severity === 2`) or exhausted loads calls the parent's failure handler. A 20-second startup guard also does so if `video.readyState < 3`; the `playing` event cancels that guard.
6. The parent advances to the next numbered source. After exhaustion, lookup failure, or no available source, it mounts JJTV's no-referrer iframe. Source changes destroy the old Shaka instance.

TG's website therefore has a browser-player fallback. Its successful website behavior cannot be described as “the same native player with another URL.” Our app intentionally retains one native Media3 engine, as requested; using JJTV's source data is not the same thing as running JJTV's browser player.

## What the actual browser requests showed

The verified trace for Hindi HD in [run 39049659222](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39049659222) recorded:

| Request phase | Origin | Referer |
|---|---|---|
| TG direct Jio manifest | TG's origin | TG's origin |
| TG relay request | TG's origin | TG's origin |
| Cross-origin redirected Jio request | literal `null` | TG's origin |
| JJTV-frame Jio request | JJTV's origin | absent |

The relay returned HTTP 302. The requests then reached Jio. CI's media responses were HTTP 451 and no decoded video was observed. This demonstrates source selection and redirect/header behavior, not playback on the user's Indian connection.

The current browser tracer now audits **all four TG entry pages** above, adding elapsed times, request durations and an explicit `visitedJjtv` flag. The live inspector also queries the current server function for all four IDs. The latest execution/artifact links are kept in [PR #1](https://github.com/creatorAkshanshsahu/v.4.9/pull/1).

## Native differences corrected in 4.9.17

### Reapply active Jio channel authentication

Previously the app replaced the initial manifest token, but preserved an existing token on every child URL. That did not match TG/JJTV's active-cookie rule: a stale token embedded in a child manifest or segment could survive even after the app had obtained a current credential.

The native transport now replaces `__hdnea__` on matching, same-channel requests to the two advertised HTTPS Jio CDN hosts when the provider explicitly supplied a cookie. A successful authorized relay-manifest redirect may establish that channel scope with its current CDN token. Different channels, origins, ports, lookalike hosts and generic independently signed media are not swept into this rule. The relay's own query is still preserved.

Unlike blindly copying `split('?')[0]`, the app changes only the known authentication parameter and retains other query bytes. It does not manufacture a signature or copy general credentials into a different Star channel.

### Match browser Origin handling across redirects

Browser-style native requests now retain per-request redirect state. A cross-origin redirect sends `Origin: null`; a later direct segment request starts fresh with the page origin again. The state stays tainted if a redirect chain comes back to its original host. It is not stored globally on the playback session or connection pool.

Declared native header profiles are not silently converted to browser behavior. Credentials and source-only headers remain scoped as before. The actual authenticated network URL—not OkHttp's restored pre-interceptor URL—is still used for observing manifest tokens.

## Intentional differences and limits

- The app still prefers JJTV for an untried V1 channel, or the provider proven for that exact channel. This is the starvation fix from 4.9.15, not TG's default source order. **MENU → Play TG TV now** (or tap the visible source line on a phone) starts the TG route for comparison.
- Media3 buffer durations and the 25-second provider / 60-second total budgets are not numerical copies of Shaka's 4-second rebuffer goal / 20-second startup guard. Those settings have different roles.
- Browser ClearKey uses the browser's EME/CDM path. Native Media3 uses the box's Android MediaDrm/MediaCrypto and decoders. Matching requests does not prove equivalence of those implementations, especially on firmware with a misleading Android release string.
- The two request mismatches above are demonstrable. Without a device report, neither is proven to be the sole cause of the user's failures. A passing build or a metadata response is not a successful playback test.

## Public code inspected

- [TG source construction and JJTV iframe](https://livetgtv.lovable.app/assets/servers-kHxJvDPi.js)
- [TG watch-page selection and failure handler](https://livetgtv.lovable.app/assets/watch._id-BFOMAa-D.js)
- [TG Shaka configuration and request filter](https://livetgtv.lovable.app/assets/TgPlayer-CUYT6Wgf.js)

These asset names identify this inspection only; the app discovers the current deployment rather than pinning these hashes. No stream token, key, key ID or signed URL is included in this report.
