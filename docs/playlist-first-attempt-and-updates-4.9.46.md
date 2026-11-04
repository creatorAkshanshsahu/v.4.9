# 4.9.46 — first-attempt movies, playlist ClearKey, in-app updates

Prepared 2026-11-04 for the user-supplied sources
[tgfrost.lovable.app](https://tgfrost.lovable.app),
[allinonereborn2.online JioTV M3U8](https://allinonereborn2.online/jiotv-m3u/jtv33.m3u8) and
[allinonereborn2.online/sony](https://allinonereborn2.online/sony/).

Three separate changes, in the order they affect watching:

1. A movie/episode now starts on a source that has answered, instead of on the first URL the API listed.
2. Playlist channels get the ClearKey **key document** the playlist advertises, so they can open their
   DRM session natively; the playlist tab also gained a Sony section.
3. The APK updates itself from the project's GitHub release, so a new build reaches an installed app
   without a manual download and reinstall.

Nothing here adds a web player, a bundled playlist, a guessed key or a borrowed credential.

---

## 1. Why a movie needed a second try

The Frost player resolves a title to many sources (observed: 12 for movie 1288069, 4 for series 213562
S1E1). Those sources are different scrapers, different formats and different relays. The website can
afford to start a source, notice that its manifest fails and move on — that happens inside one page
load and the viewer does not see it. The app could not: it created ExoPlayer for the first listed
source and paid a full startup cycle (manifest fetch, DRM setup, decoder selection, then the error)
before it advanced. A dead first source therefore looked exactly like "the app doesn't load on the
first try, but the website does".

`VodPreflight` fixes the ordering rather than the symptom:

- The first six candidates are probed in parallel with a 4-second deadline, using the same transport,
  headers and cookies the player would use (`Range: bytes=0-511`, response discarded).
- A response counts only if it is a DASH/HLS document by body marker or by a media content type. An
  HTML or JSON error page does not count.
- Verified sources are moved to the front, keeping the provider's relative order inside each group.
  **No source is dropped**: a CDN that merely dislikes a ranged request stays in the list behind the
  verified ones, so the previous behaviour is still available.
- If nothing answered, the provider order is used unchanged — the app never refuses to start because a
  probe was inconclusive.
- A source that played this title last time is still started immediately, without waiting for a probe.

The probe result is not persisted and contains no URL, header or signature.

## 2. Playlist channels: the missing key document

The playlist carries three transport variants. The most common one advertises its keys as a **URL**:

```text
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=https://allinonereborn2.online/jtv-fetch/keyid.php?id=143
#EXTVLCOPT:http-user-agent=@allinone_reborn
#EXTHTTP:{"cookie":"__hdnea__=…"}
https://allinonereborn2.online/jiotv-m3u/jtv1.mpd?id=143
```

Read on 2026-11-04, `keyid.php?id=143` answers a plain `GET` with the ClearKey document itself:

```json
{ "keys": [ { "kty": "oct", "k": "…", "kid": "…" } ], "type": "temporary" }
```

The previous native path only knew two shapes: local ClearKey material, or a Widevine licence URL. A
`clearkey` entry whose keys were a **URL** matched neither, so `nativeMediaSource()` threw
"did not provide usable DRM for this source" before the first frame. That is a direct, reproducible
reason why these playlist channels played on the website and not in the app.

What changed:

- `JioM3uProtocol.licenseUrl()` accepts a key URL only when it is HTTPS, has no userinfo/fragment and
  is on the playlist's **own host**. Another origin is never followed for keys.
- `JioM3uResolver` fetches that document (bounded to 64 KiB, 10-minute in-memory cache, cleared on a
  refused request), parses JWK / `{"keys":{"kid":"key"}}` / `kid:key` forms and attaches the pairs to the
  stream, so the existing local ClearKey path opens the session.
- `JioM3uProtocol.parseKeys()` accepts only values that look like a 16-byte key or key id (32 hex digits
  or base64url). Anything else is ignored rather than guessed.
- If the document cannot be read, the stream keeps the advertised endpoint and `ClearKeyLicenseCallback`
  performs the same `GET` when Media3 opens the DRM session. Media3's own HTTP callbacks `POST` a
  challenge or a Widevine licence request, which this endpoint does not accept.
- Keys stay in memory. Nothing is written to preferences or disk, and no key is printed in a report.
- A refused playlist stream (HTTP 401/403) now re-reads the playlist **and** its key document once
  (`TgServers.invalidateJio()`), like the TG/GioTV providers already did.

The playlist cookie (`__hdnea__`) keeps its existing handling: the initial relay redirect carries its own
query, and the CDN token learned from a successful manifest is applied to the same channel's later Jio
requests. No cookie is copied to another channel or origin.

## 3. New playlist section

The JioTV tab now leads with two sections built from the playlist catalogue itself:

| Section | Source of the tiles | Playback |
| --- | --- | --- |
| **Sony Live · Allinone** | `allinonereborn2.online/sony/` tile order, matched **by channel name** in the playlist catalogue | playlist stream + its key document + cookie (native ClearKey) |
| **Allinone Playlist** | the full `jtv33.m3u8` catalogue, featured selection | same |

The Sony page still embeds a third-party web player (`suniliv.pages.dev/ptest1.html?id=<slug>`) that the
app does not open. Its tiles are matched by name against the loaded catalogue — never by hand-written
channel IDs, which previously resolved 14 slugs to unrelated broadcasters. The Live tab keeps its
`Sony Live · Allinone` shelf for the TG/GioTV catalogue; the playlist tab's version needs no TG or GioTV
lookup at all.

The tab header now reads `JIOTV PLAYLIST · SONY SECTION · NATIVE CLEARKEY`.

## 4. The app updates itself

Every build job publishes a rolling GitHub release named `latest` containing:

```text
AK-TV-arm32.apk   AK-TV-arm64.apk   SHA256SUMS   update.json   (release notes)
```

`update.json` is generated from `app/build.gradle.kts` by `.github/scripts/publish-update.py`:

```json
{
  "versionCode": 63,
  "versionName": "4.9.46",
  "build": "1234",
  "notes": "AK TV 4.9.46 (code 63) · CI build 1234",
  "apks": {
    "arm32": { "url": "…/AK-TV-arm32.apk", "sha256": "…", "size": 123 },
    "arm64": { "url": "…/AK-TV-arm64.apk", "sha256": "…", "size": 456 }
  }
}
```

On the device:

- `Updater.check()` reads `https://github.com/creatorAkshanshsahu/v.4.9/releases/latest/download/update.json`,
  picks the asset matching this APK's ABI and treats a build as newer only when its `versionCode` is
  higher, or when the same code was re-published with a different SHA-256 than the build this app last
  installed.
- The check runs at launch (throttled to once every six hours) and shows a **Download and install**
  prompt; nothing downloads without that tap. `Native playback details` (MENU/INFO, or a tap on the
  status line) carries a **Check for updates** button for a manual check.
- The download is bounded (300 MiB), and the file must match the published SHA-256 before it is used.
- The APK's package name and `versionCode` are read from the downloaded file before the installer is
  started, and its signature is compared with the installed app. A mismatch is explained instead of
  failing inside Android's installer.
- Android's one-time "install unknown apps" permission is requested through the system settings screen.
- The APK is handed to the installer through this app's own `FileProvider`
  (`com.akshansh.aktv.updates`), so no storage permission and no public directory is used.
- Favourites, remembered channels, the TG function id and movie progress are kept because the package
  name is unchanged and the update installs over the current app.

### Signing key (important, one-time)

Updating in place is only possible when consecutive builds share a signing key. Earlier CI builds used
the runner's throwaway debug key, which changes on every run, so Android would reject an in-place update
over one of those builds with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

`keystore/aktv-update.p12` is therefore committed and used for every release build
(`AKTV_UPDATE_STORE_PASSWORD` overrides its password; the file is a sideload key, not a store upload
key). Consequences:

- **The first update over a build made before 4.9.46 needs one manual uninstall.** The app detects the
  signature mismatch and says so before opening the installer.
- Every update from 4.9.46 onwards installs in place.

## 5. Verification status

Checked while writing this change:

- The live `keyid.php` response shape, the playlist's `#KODIPROP`/`#EXTHTTP` lines and the
  `jtv1.mpd?id=143` → `jiotvmblive.cdn.jio.com/bpk-v/...` redirect were read directly.
- The Frost source list for movie 1288069 was read directly (DASH relay sources first, direct MP4 later).
- `.github/scripts/publish-update.test.py` passes (manifest shape, per-ABI assets, missing-APK failure).
- `.github/scripts/check-playback-baseline.py` passes with one documented amendment (a new
  `TgServers.invalidateJio()`; TG/GioTV behaviour unchanged).
- New Kotlin unit tests cover the probe ordering/body rules, the key document parsing and the update
  manifest/version rules.

Not yet done:

- No compiler or JDK was available in the environment where this change was written, so **CI is the
  first compile** of these edits.
- No device has played a playlist channel, a Sony tile or a Frost title with this build. The ClearKey
  document is now supplied where it previously was not; the channel's own authorization (an expiring
  `__hdnea__` cookie) and the device's HEVC/ClearKey support remain the same real limits described in
  [native-live-player.md](native-live-player.md).
- A Ranged `GET` answered with an error is only used to *order* sources. It never proves that decoding
  will succeed.

## 6. Where the builds live

Every verified push to this branch republishes one rolling GitHub release, tagged `latest`:

| Asset | Purpose |
| --- | --- |
| `AK-TV-arm32.apk` | 32-bit build (older/cheaper boxes) |
| `AK-TV-arm64.apk` | 64-bit build (most phones and TV sticks) |
| `update.json` | the manifest the in-app updater reads |
| `SHA256SUMS` | checksums of both APKs |

Direct links: `https://github.com/creatorAkshanshsahu/v.4.9/releases/latest/download/AK-TV-arm64.apk`
and `.../AK-TV-arm32.apk`. Installing either APK once is the only manual step; afterwards
**Update → Install** (or the **Check for updates** button in *Native playback details*) downloads the
newer asset and hands it to the system installer, so the app updates over itself.

`Updater.check()` compares the manifest's `versionCode` with `max(installed versionCode,
update_installed_code)`. The second term matters because the running process keeps the old binary
until the system swap completes — without it the same update would be offered on every launch.

## 7. 4.9.47 — why the playlist channels refused to start, and what changed

The 4.9.46 build listed the playlist's channels but none of them played. Two faults, both in the
hand-over between the playlist and the player:

1. **A Jio CDN token was sent where it does not belong.** The playlist's `#EXTHTTP` cookie is not a
   cookie for `allinonereborn2.online`; it is a signed Jio CDN token (`st`/`exp`/`acl`/`hmac`) for the
   channel's manifest path, and the playlist's copy expires within hours. The stream carried it as
   `TgStream.cookie`, so the request policy re-sent it as `?__hdnea__=…` on every manifest request —
   the redirector was handed a signature it never minted. The stream no longer carries it; it is used
   only as a `Cookie` header on the playlist host, and only as a fallback attempt.
2. **The player was pointed at a 302.** `jtv1.mpd?id=N` only answers with a redirect to
   `jiotvmblive.cdn.jio.com`, and a refusal at either hop surfaced as an opaque decoder error. The
   resolver now opens the channel itself: it follows the redirect, checks that the body really is a
   manifest (`<MPD`, or `#EXTM3U` for HLS), and hands Media3 the **signed CDN address that answered**.
   If the CDN address is another origin, the playlist's headers are dropped for it, exactly as the
   redirect would have done.

The probe never removes a candidate: if every attempt fails, the advertised URL is kept and the
refusal (`HTTP 403`, `network error`, `not a DASH manifest`) is appended to the source label, which is
what the source list and the playback details dialog show.

One more rule was added while checking the failure mode: a ClearKey licence must name the key id the
manifest actually uses. When the key document and the MPD's `cenc:default_KID` disagree, a single
advertised key is now relabelled onto the manifest's key id instead of being offered under a key id no
sample uses — that case decrypts nothing and shows a black screen with no error text.

The Sony shelf on the Live tab now prefers the playlist's own tiles (they carry their manifest and
keys, so they play directly) and only falls back to another provider's tile when the playlist does not
list that channel. The hero's *Play Live* button also stopped playing the first channel of the TG
catalogue when the hero showed a playlist tile.

## 8. 4.9.48 — what the movie service actually serves, and the separate Sony section

### Movies: a manifest that answers is not a playable source

The live `/api/sources` response was read for two titles (`1288069`, `550`) on both servers. Two shapes
matter:

- **TG FROST (`tgflix`)** returns DASH through a relay: `https://cinemaos-relay.<x>.workers.dev/?url=…`
  where the inner URL is `https://holly.mpddash.workers.dev/p/<token>.mpd`. The relay is a generic
  pass-through proxy (confirmed with an unrelated URL) that rewrites the manifest's `<BaseURL>` to its
  own `/t/<base64 {"dir","headers"}>/` form. The manifest itself is static DASH, HEVC only
  (`hev1.1.6.L150.90` …), with **absolute CloudFront segment URLs** carrying `Policy`/`Signature`/
  `Key-Pair-Id`. Playback therefore never needed the relay for the segments.
- **TG OUT (`tgout`)** returns signed HLS (`Expires=…&KeyName=Signature&Signature=…`) whose first
  "720p source" for 1288069 is a **seek-bar sprite sheet**: a genuine `#EXTM3U` whose `#EXTINF` media
  lines are `.jpg` images under `/720/0000.jpg`. Its header is indistinguishable from video.

The preflight used to accept both. A sprite sheet was *ranked first* and confirmed reachable, so the
player paid a startup cycle on it before failing — which is exactly "the source is found, the server is
not". The probe now:

- reads the document (bounded), then fetches **the one media resource it names** — the DASH
  `initialization` segment with `$RepresentationID$`/`$Number$` substituted and XML escapes decoded, or
  the HLS `#EXT-X-MAP` / first media segment resolved against the playlist — and requires a 2xx/206 for
  it. A manifest whose signature has outlived its content no longer counts as confirmed;
- recognises sprite playlists ([VodPreflight.isImagePlaylist]) and rejects them;
- runs fewer, wider probes (4 candidates, 2 workers) because every candidate costs a manifest request
  plus a media request against a rate-limited relay.

A progressive MP4 is checked with a single short ranged request instead of a manifest read.

### Sony: a separate section, and it is not a replacement

`allinonereborn2.online/sony/` lists 22 tiles and hands each to `suniliv.pages.dev/ptest1.html?id=<slug>` —
a web player this app never opens. The page is still useful as the *list*: which Sony channels its
author carries and in what order. `SonyPage` rebuilds that list on the native side and binds each tile
to the **playlist's own channel** by name, so the tile carries the playlist manifest, ClearKey document
and cookie that 4.9.47 made playable. No id, stream or credential is copied from the page, and a tile
the loaded catalogue does not carry is left out rather than guessed.

It is a separate row (*Sony Live · Allinone Playlist*). The catalogued *Sony Live* shelf keeps exactly
the tiles it always had, in the same order: the new section is added beside it, never in place of it.

## 9. 4.9.49/4.9.50 — one Sony section, from the site's own playlist, and nothing behind it

4.9.48 added the site's Sony list as a *second* row and left the catalogued `Sony Live` shelf in place.
Two rows of Sony channels is what the user rejected, and the older row played through a different
provider than the one they asked for. 4.9.49 replaces both with a single section.

### What the source actually is

`suniliv.pages.dev/ptest1.html?id=<slug>` cannot be read usefully: the page is a browser player that
resolves its stream with Media Source Extensions, so no manifest, key or URL appears in its markup
(five independent proxies returned player chrome only). The site does, however, publish the same
channels a second time as a plain playlist — `allinonereborn2.online/jiotv-m3u/jtv33.m3u8` — where each
entry carries the native pieces 4.9.47 made playable:

| piece | where it comes from |
| --- | --- |
| manifest | `https://allinonereborn2.online/jiotv-m3u/jtv1.mpd?id=<n>` → 302 → Jio CDN `index.mpd?__hdnea__=…` |
| ClearKey | `GET https://allinonereborn2.online/jtv-fetch/keyid.php?id=<n>` (plain JSON) |
| headers | `User-Agent: @allinone_reborn` plus the entry's own `__hdnea__` cookie, re-parsed at play time |

So the section's *list* is the Sony page (22 tiles, page order) and the section's *stream* is the
playlist — the same site, the same channels, played natively.

### What the live playlist actually carries

The first live run of the section against the real playlist (1169 channels) bound **14 of the page's
22 tiles**: SET HD, Sony SAB HD, Sony Pal, Sony Wah, Sony Marathi SD, Sony Max HD, Sony MAX2, Sony Pix
HD, Sony Ten 1 HD, Sony Ten 2 HD, Sony Ten 3 Hindi HD, Sony Yay Hindi, Sony aath, Sony BBC Earth HD
English. The other eight slugs — the SD twins of Ten 1/2/3 and Max, and the Ten 4 and Ten 5 (Six)
feeds — have no entry in that playlist at all, so the site serves them only through its browser player.
4.9.51 keeps those eight as tiles and plays them through that page inside the app (section 10); they are
never filled from another provider.

To make the section *all* the Sony this source has, it then adds every remaining channel the playlist
brands Sony: the regional Sony Yay feeds (Tamil 873, Telugu 874, Kannada 3420, Bengali 3424, Malayalam
3423), Sony BBC Earth HD 821 with its Tamil 853 and Telugu 854 feeds, Sony SAB SD 154 and Sony MAX1
3418. Channels that merely share a word — Mahaa Max, Jaya Max, Aakash Aath, MK Six, Animax — are never
pulled in.

The shipped section therefore reads, in order: SET HD 291, Sony SAB HD 471, Sony Pal 474, Sony Wah 1393,
Sony Marathi SD 1146, Sony Max HD 476, Sony MAX2 483, Sony Pix HD 762, Sony Ten 1 HD 162, Sony Ten 2 HD
891, Sony Ten 3 Hindi HD 892, Sony Yay Hindi 872, Sony aath 697, Sony BBC Earth HD English 852 — then
the ten listed above. Twenty-four tiles, every one of them resolved from this playlist.

### How a tile is bound

`SonyPage.channels()` walks the page's 22 tiles in page order and takes, for each one, the first channel
of the playlist that is not already used and that matches by

1. normalised name (`Sony Ten 3 Hindi HD`), then
2. **published logo file** (`Ten3_HD.png`) — the page and the playlist both use the Jio image CDN, so a
   channel the playlist renames still binds, then
3. a contained alias, as a last resort.

A channel is used once, so the page's SD and HD tiles stay two tiles when the playlist carries both, and
a channel the playlist does not carry is dropped instead of being filled with a different broadcaster.

### Why it cannot fall back

Binding by name was never the problem in 4.9.48 — falling back was. A bound tile is now **pinned**:
`Channel` carries `pinned`, and `ChannelProviderOrder.playbackOrder(..., pinned = true)` returns the
playlist provider only. If the playlist stream fails, the tile reports the failure; it never quietly
starts a TG or GioTV stream that the section did not promise. Favourites and recents re-pin a Sony key
when it is restored, so a saved tile replays from the same source.

### What was removed

`LiveCollections` (the catalogued `Sony Live` shelf and its name-matched variant) and its tests are
gone, together with the second row. There is exactly one Sony row in the app — on Live TV and on the
JioTV tab, both built by `SonyPage.channels()` from the loaded playlist. The JioTV tab's full
`Allinone Playlist` row and the ordinary catalogue categories are untouched.

## 10. 4.9.51 — every Sony tile has a picture: native playlist first, the site's own player behind it

The section from 4.9.50 resolved natively for the 24 channels the playlist carries, but a tile still
ended on an error card when the playlist refused its manifest, and the eight page-only slugs (SD twins
of Ten 1/2/3 and Max, Ten 4 HD/SD, Ten 5 HD/SD) had no tile at all.

Both are now closed without leaving the source:

| tile | what plays it |
| --- | --- |
| a page slug the playlist carries (24 channels) | natively from `jtv33.m3u8` — `jtv1.mpd?id=N`, ClearKey, cookie |
| the same tile when the playlist refuses | the site's own player page, `suniliv.pages.dev/ptest1?id=<slug>`, inside the app |
| a page slug the playlist does not carry (8 channels) | that same page directly; no native provider is even tried |

The page player is not a new provider and not a browser hand-off: `BrowserActivity` already runs the
bundled engine with a D-pad pointer, and it now receives the channel's name and opens full screen. The
rule from 4.9.49 still holds — a tile never falls back to TG or GioTV; the only fallback is the site
that the section came from.

`Channel.page` carries the slug's player URL and `Channel.pageOnly` marks the eight channels the
playlist cannot serve. `ChannelProviderOrder.playbackOrder(..., pageOnly = true)` returns an empty list,
so the native machinery is skipped entirely, and `MainActivity.fail()` closes a playlist refusal by
opening the page instead of drawing an error. Favourites and recents resolve the section's own tiles, so
a saved Sony channel keeps working after a restart.
