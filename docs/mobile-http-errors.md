# Mobile HTTP / manifest failures — 4.9.19

Investigation date: 2026-10-18. This follow-up concerns **live TV**, not the Frost movie player.

## Current status

The user subsequently confirmed all channels working on the TV with **4.9.18**, apart from Star Sports 1 Hindi HD stutter, and clarified that website playback could work while the APK failed. The investigation below is historical and not a complete attribution of that mismatch. [4.9.21](ui-refresh-4.9.21.md) preserves the working 4.9.18 playback contracts while rebuilding UI/branding and stabilising actual rendition selection.

## User evidence and its limits

The user supplied four phone screenshots and confirmed that the **first three Playback Details screenshots came from 4.9.18**. The fourth explicitly shows an older 4.9.15 build. The user selected “another channel / several channels,” without naming the exact channel, and has **not checked current playback on TG's website**. Earlier website-works reports must not be treated as a new same-time comparison.

The visible failures include:

- JJTV metadata lookup: HTTP 403 / `JjtvHttpException`.
- TG attempts: HTTP 403 / Media3 2004 / `InvalidResponseCodeException`.
- Another TG attempt: Media3 3002 / `ParserException`.
- The displayed subsequent attempt has no completed manifest/init/media load, no selected tracks, and DRM stage `NOT_REQUESTED`.

This places the visible failure **before usable manifest parsing/track selection**, not at a proven codec or DRM failure. “Decoder not initialized” and zero unsupported-track counts do not establish decoder support or a missing library. “0 bytes” in the older report counts completed loads, not necessarily every byte received in a failed HTTP/parsing operation.

A parser exception alone does **not** prove the server sent HTML. It could also be malformed XML or a format mismatch. The old report did not retain the response classification or the JJTV metadata stage.

## Subsequent user update

The user reports JJTV recovered on **unchanged 4.9.18 on mobile**, using the native “browser request” profile, while the Android box still retries and ends in 403. That recovery cannot be attributed to the 4.9.19 patch. The box's currently installed build and precise failing stage remain unconfirmed. The user also reports successful playback on this box in 4.7; the [extended comparison now includes static inspection of the actual 4.7.3 APK](tv-4.7.2-comparison.md), including its different native HTTP/cookie path. Neither a missing codec nor use of the old web fallback has been established as the box's explanation.

## October 19 follow-up

New working/failed reports now identify channel 165 (HEVC/ClearKey, keys loaded and A/V progress) and Sony YAY 872 (working H.264/ClearKey on October 18, HTTP 403 on October 19). A fresh provider check found current authorization for 165 but expired supplied Sony/Star credentials. See [4.9.20 credential-renewal evidence and changes](live-credential-renewal.md). This is concrete new evidence, not a retrospective claim that every earlier 403 had the same cause.

## New browser trace: metadata, not just video

Read-only run [39058745317](https://github.com/creatorAkshanshsahu/v.4.9/actions/runs/39058745317), inspection job `117472093974`, extended the existing TG/JJTV trace to metadata requests. Representative IDs were 1106, 1108, 362 and 872; these are **not claimed to identify the user's unnamed failing channel**.

Within the **JJTV iframe**, the actual browser requests were:

| Request | Origin | Referer | Runner result |
| --- | --- | --- | --- |
| `/pind?id=<exact ID>` | Absent | Absent | 200 |
| Same-origin `/jstr4web.json` | Absent | Absent | 200 |
| Cross-origin general-cookie JSON | JJTV site | Absent | 200 |
| Cross-origin Star/PL-cookie JSON | JJTV site | Absent | 200 |

No account Cookie or Authorization header was present in those observed metadata requests. No challenge was indicated by the `cf-mitigated` response header.

TG's **parent page also fetches the JJTV catalogue**, with TG's Origin and Referer. That is distinct from the subsequent same-origin catalogue fetch inside the JJTV iframe; the two request contexts must not be conflated.

The app previously added JJTV Origin and full player-page Referer to **every** metadata lookup, and invented `_aktv` / `_aktv_refresh` query parameters. These differences were separate from the media-request corrections already delivered in 4.9.17.

The runner's HTTP probes returned 200 for both the older app-style metadata headers and canonical/no-referrer requests. The cache-busted cookie responses were unchanged in this sample. Therefore **the mismatch is verified, but its responsibility for the phone's 403 is not**. This is not a confirmed outage, expired-token diagnosis, missing-Gecko diagnosis, or proof that a new APK plays on that phone.

## Changes in 4.9.19

### 1. Match observed JJTV metadata requests

- Use the exact advertised/canonical metadata URL, preserving any provider-owned query/signature. No invented cache-busting parameters.
- Use no-cache/no-store headers and the existing bounded cache/renewal logic.
- Omit Referer throughout the JJTV metadata request and redirect chain.
- Do not add Origin to same-origin metadata or page/script navigation. Cross-origin data fetches use the JJTV origin.
- Apply Fetch redirect-origin semantics per request: a first redirect **from the document's own origin** to a foreign origin is not yet opaque; a subsequent cross-origin redirect from a foreign origin becomes `Origin: null`.
- The same correction is applied to browser-style live-media requests. Explicit provider-declared native header profiles retain their separate behavior.
- No credentials are invented, another channel's keys are not substituted, and no browser/challenge player is restored.

### 2. Inspect the response before blaming a decoder

- Retain only a bounded, non-sensitive HTTP summary: status, normalized MIME category, body category, inspected prefix byte count, redirect count, and whether the server explicitly indicated a challenge.
- At most 2 KiB of prefix is examined. It is not saved or included in diagnostics. No URLs, header values, response text, cookies, keys or key IDs are recorded.
- Metadata errors identify **player page, player script, channel catalogue, general cookie, fallback cookie, or Star/PL cookie**.
- Recognized HTML, JSON, XML error documents and empty successful responses are not repeatedly handed to a DASH/HLS manifest parser as if they were valid manifests.
- HTTP 403 remains HTTP 403. An HTTP-200 error page remains HTTP 200 plus an invalid-format classification; it is not mislabelled as an authentication error.
- A real DASH/HLS signature mismatch can cause **one** same-URL/request-profile native format correction. URL, headers, cookie and source-owned DRM stay unchanged. HTML or unknown data never becomes a guessed HLS/MP4 source.
- Normal valid manifests and transparent gzip remain intact. Arbitrary binary/unknown prefixes are left to Media3 rather than guessed. Media segments and progressive sources are not treated as initial manifest requests.
- Parse failures are reported as `FORMAT`, rather than being confused with decoder failures or a generic stopped stream.

### 3. Make the device report usable

- Show version, version code, CI build/run ID, channel name/ID, actual reported API and process bitness.
- Add **Copy redacted report**, an explicit user action. It copies the current report, not a stale screenshot or private stream credentials.
- Refresh open Playback Details when terminal errors/failure occur.
- Keep the local DRM self-test available, but explain that it cannot repair an HTTP refusal or invalid manifest.

Native Frost Movies/Series and the TV capability policy remain in place. The removed live web/backup player remains removed.

## Verification and next device check

Offline tests cover metadata request policy, redirect-origin state, stage-specific denials, successful-status error pages, response redaction/bounds, gzip/non-consuming inspection, and one-shot format correction. Existing resolver/auth/DRM, live TV, Frost and UI tests remain part of both ABI builds. Final test counts, run and APK artifact are recorded in PR #1.

The runner cannot reproduce the user's mobile network/device exactly. Its source/metadata success is not proof of phone picture/audio. If the new build still fails, use **Playback Details → Copy redacted report**, and supply that text plus the exact channel and whether a freshly loaded TG website plays it on the same phone/network. Do not send cookies, signed stream URLs or DRM keys. A DRM test is not the first action for the HTTP/manifest failures shown here.
