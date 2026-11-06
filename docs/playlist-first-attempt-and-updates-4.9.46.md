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

## 11. 4.9.52–4.9.54 — the playlist plays everywhere except here: request parity with a normal IPTV player

The report was exact: `jtv33.m3u8` opens **every** channel in an ordinary IPTV player, while the app
played only a couple of them. A playlist that behaves that way is not a playlist problem — the request
the app sends differs from the request the player sends.

### What the live report showed, and what it did not

`JioM3uLiveTest` prints, per channel: the playlist path, the key-document status and key count, the host
that finally answered, the manifest status and size, and whether the MPD declares ContentProtection. First
run, four channels (Zee Cinema HD 165 and &TV 1106 as controls, Sony SAB HD 471 and Sony Ten 3 Hindi HD
892 as the reported failures):

    all four: jtv1.mpd -> HTTP 451, 0 bytes, no redirect to the CDN
    key document: 200, 315 bytes / 2 keys (165), 181 bytes / 1 key (471)

The same answer for the channels that work and the channels that do not is the useful part: it means the
CI runner is not measuring this app at all. **Jio refuses non-Indian addresses with HTTP 451**, and the
GitHub runner is one. Playback for an Indian device cannot be judged from CI, and a change justified by
that 451 would have been a change made for the wrong reason. The report is still worth having: it proves
the key document, the playlist and the redirect contract, and it will show a real regression the day a
channel disappears from the playlist.

### So: match the player that works

An IPTV player opening this playlist sends, for `jtv1.mpd?id=N`:

| header | value |
| --- | --- |
| `User-Agent` | `@allinone_reborn` (the playlist's `#EXTVLCOPT`) |
| `Cookie` | the playlist's `#EXTHTTP` `__hdnea__` token |

and nothing else — no `Origin`, no `Referer`. The app sent the opposite: its own browser User-Agent on the
CDN, plus `Origin: https://allinonereborn2.online` and `Referer: …/` on every request (including segments),
because the request policy attached the site origin to every stream.

4.9.52–4.9.54 restore parity:

| request | headers |
| --- | --- |
| `jtv33.m3u8` | playlist user agent |
| first attempt at `jtv1.mpd?id=N` | playlist user agent + playlist cookie (the IPTV player's request) |
| later attempts, if that is refused | the page's browser-style headers, then the cookie, then the app user agent |
| the signed Jio CDN link and every segment under it | playlist user agent + playlist cookie — no `Origin`, no `Referer` |

`TgStream.omitOrigin` carries the rule into `StreamRequestPolicy` so segments inherit it, and the cookie
stays a plain `Cookie` header: it is never re-sent as a `?__hdnea__=` query parameter, which is what broke
the redirector in 4.9.47. Provider identity, remembered sources, the source fingerprint and the whole
ClearKey path (key document over `GET`, KIDs matched against the manifest, JWK licence to Media3) are
unchanged.

## 12. 4.9.55 — the page's tiles, all of them, all opening the page's own player

Two corrections, both from using 4.9.54 on the device.

**The section was larger than the page.** 4.9.50 added "every other Sony channel the playlist brands
Sony" — the regional Yay feeds, BBC Earth Tamil/Telugu, SAB SD, MAX1 — which turned 22 links into 34
tiles. The user asked for the page's list, not an interpretation of it. The section is now exactly the 22
links the page publishes, in its order, with its own labels (`SONY HD`, `SAB HD`, `SONY TEN 3 HD`,
`BBC EARTH`, …) and its own logo files. Nothing is added and nothing is guessed.

**Only two tiles reached the player.** Those two were the page-only slugs (Ten 3 HD, Ten 5), which went
straight to the page; the other twenty tried the native playlist first, and on the device that attempt
never ended in the player. Every tile of the section now opens the site's player for its slug, with no
native attempt and no fallback chain — tapping a Sony tile is that page, full screen, in the app. A tile
whose channel is also in the playlist keeps that channel's identity so favourites and recents still line
up, but it plays the same page as the rest.

`ChannelProviderOrder.playbackOrder(..., pageOnly = true)` returns no native provider for these tiles, so
nothing can start a stream from somewhere else even if a later change forgets the routing.

## 13. 4.9.56 — the same Sony channel opens the player from every row, not only from the Sony row

4.9.55 made all 22 page tiles open the site's player, but only the tiles *in that row* carry a slug. The
same channels also appear in the playlist's category rows, in favourites, in recents and in search, and
those copies have no slug - so tapping `Sony Ten 1 HD` there still went down the native path, which is the
behaviour that was reported as "only Ten 3 HD and Ten 5 open the player".

`SonyPage.pageFor(name)` closes that gap by matching a channel's **name** to the page's list, and only by
name:

1. the name is exactly one of the page's own names (`Sony Ten 3 Hindi HD`, `SET HD`, `Sony MAX2`);
2. or it is a tile's title with a trailing `HD`/`SD` (`Sony Marathi SD` → `SONY MARATHI`).

Anything else gets no slug, deliberately: `Sony SAB SD`, `Sony Yay Tamil`, `SONY YAY Bengali`, `Sony MAX1`,
`Sony BBC Earth HD Telugu` and `Sony Six` are not page tiles, so they stay native instead of being shown
as a tile the page does not have. A test pins both directions.

From this build, tapping any channel the page lists - from any row - opens
`suniliv.pages.dev/ptest1?id=<slug>` full screen in the app.

## 14. 4.9.57 — one channel, three ways to open it, instead of guessing which credential the CDN wants

The playlist channels still do not reach a picture: black, or a player that keeps retrying. The last two
releases each picked one request shape and hoped it was the right one:

- 4.9.53 sent the playlist's user agent only, and let the CDN's own signed query authorize the segments;
- 4.9.54 also sent the playlist's `#EXTHTTP` cookie, which is what an IPTV player sends.

Both are plausible, and they conflict: the cookie is a **coarse, site-wide token** (`acl=/*`), while the
URL behind the redirect carries a **token minted for that channel and path**. A CDN that prefers the
cookie will reject a request whose query token it just minted, and a CDN that needs the cookie will reject
one that carries only the query. Which of the two happens differs per channel, and no amount of reading
the playlist from CI settles it — the runner is geo-blocked (section 11).

So the resolver no longer chooses. One channel now yields **three candidates**, and the existing recovery
loop walks them in order:

| candidate | request |
| --- | --- |
| `· CDN` | the signed CDN address with the playlist's user agent and cookie |
| `· CDN, no cookie` | the same address with the user agent only, authorized by the query token alone |
| `· playlist redirect` | `jtv1.mpd?id=N` exactly as the playlist writes it, user agent and cookie, redirect followed by the player |

All three are the same source, the same ClearKey configuration and the same "no Origin, no Referer" rule,
and the source menu lists them by those labels. When a candidate loads its manifest and keys but never
produces a frame, the health check retires it and moves to the next one automatically, then one fresh
re-resolve, then the error card — which still prints the exact stage (manifest / init / media / DRM stage)
so the next report names the failing step instead of "black screen".

If one of the three plays, the next release keeps only the ones that do.

## 15. 4.9.58 — the section decides, not the channel's name

4.9.56 made any channel whose *name* matched a Sony tile open the site's player. That was wrong for one
section and the user said so plainly: **JioTV plays natively, Live TV opens the player**.

The two sections hold the same channels, so the name cannot decide. What differs is the tile:

- the Live TV **Sony section** builds its rows from `SonyPage.channels(...)`, and every one of those tiles
  carries the page's slug in `page`; playing one opens `suniliv.pages.dev/ptest1?id=<slug>` in the in-app
  browser, and if native playback of such a tile ever fails, the same page is the fallback;
- the **JioTV** section holds the playlist's own `Channel` objects, and those have no `page`. They are
  ordinary channels: they resolve and play on the native player exactly like every other playlist
  channel — Sony included. `SonyPage.pageFor(name)` is gone, so no name can divert a real channel to the
  browser.

Favourites, recents and search keep whatever the row they came from carries, which is the playlist
channel: native.

### Back no longer leaves the app

Pressing Back while a playlist channel was still resolving (or with the surface not yet attached) fell
through the player branch to the activity and **closed the app**. A `playerSession` flag now marks the
whole span from "channel picked" to "player closed", and Back closes that session for as long as it is
open, whatever the surface is doing.

Back also walks up instead of out: a lower row returns to the top of its section, and any section other
than Live TV returns to Live TV. The app closes only from Live TV's own top row.

### Updates arrive when the network does

The launch check stays, and a network callback now asks again the moment a network appears, so a TV that
was switched on before the router picks up a release published in the meantime with no reinstall and no
manual check.

## 16. 4.9.59 — the app reports why a run died, and movies get the site's own servers

### A run that ends by itself has to say why

"Lag, freeze, then it closes" cannot be fixed from a description, and a TV has no log the user can reach.
Two kinds of death are now recorded and offered on the next start, with a Copy button:

- **an uncaught exception** - a default exception handler writes the stack, the build, the ABI, the
  device and both heaps (`Runtime.maxMemory` and the device's own free memory) before the process dies;
- **an unclean exit** - a session marker is written at start and cleared in `onPause`. If the next start
  finds it still `running`, the previous process was killed without a crash: that is what an
  out-of-memory kill, a native crash inside the decoder or an ANR kill looks like from inside the app.
  The note then carries the moment the dead run started plus the same memory detail, which is what
  distinguishes a 32-bit heap limit on a 1 GB box from a player fault.

The note is capped, redacted (no URLs, cookies, key ids or keys), shown once per distinct text, and never
blocks playback - it is a dialog the viewer can copy and close.

The network-triggered update check also gained a two-minute floor: a TV behind a flapping router fires
that callback repeatedly, and each firing used to start a GitHub request.

### Movies and series: the site's own servers, in our player

`theogpiratebot.online` is a metadata front end: its catalogue is TMDB, and a title's Play button opens
`theogpiratebot.online/player?id=<tmdb>&type=movie|tv&server=<n>`. Its streams are third party providers
embedded by that page and selected inside it - they are not addresses this app can own, and copying them
would break as soon as a provider rotates.

So the movie and series player's source menu now ends with **OG player · Server 1..4**, each opening that
site's player for the title's TMDB identity (and the episode, for a series) inside the in-app browser. The
site's own player then plays, exactly as the Sony section does, and choosing a dead server is the site's
own "switched to the working one" behaviour. `server=0` is the number the site's own Play button uses.

Nothing is invented: four numbers, the site's own parameter names, and a test that pins the address shape
for a movie, a series with an episode, a series without one, and a server number the site does not publish
(clamped, never extrapolated).

### The browser engine no longer stays resident on a tight box

`GeckoRuntime` was a process-wide singleton, kept warm so the next page opened instantly. On a 32-bit TV
box that resident browser engine is the largest single allocation in the process, and it survives long
after the page is gone - the shape of an out-of-memory kill that arrives minutes later, while the viewer
is somewhere else. `BrowserActivity` now shuts the engine down when it closes **if the box is short of
memory** (`isLowRamDevice`, or under 300 MB free); a box with room keeps the warm engine and still opens
the next page instantly. Losing the warm engine costs a second on the next page, not a crash.

## 17. 4.9.60 — `HTTP 415` on the playlist, and the section that must not go empty

The JioTV tab came back as *"JioTV channels didn't load (JioTV m3u8 HTTP 415)"*. The host now refuses the
request this build had been sending for the playlist itself - its own `User-Agent`, the site as
`Origin`/`Referer`, and an `application/vnd.apple.mpegurl` `Accept`. Nothing about that request is wrong
in principle; the front door simply stopped accepting it.

Three things changed, and each of them alone would have kept the section alive:

1. **The file is asked for in several shapes.** A plain browser request first (`Accept: */*`, nothing
   else), then an IPTV player's own identity, then the shape 4.9.54 shipped. The first shape that returns
   a body this build can parse wins, and it is remembered for the rest of the session, so the refusal is
   not paid again on the next refresh.
2. **A body is only accepted if it is a playlist.** A 200 that lists no channels is a refusal, not an
   empty section, so the next shape is tried instead of the tab going blank.
3. **The last good playlist is kept on disk** (`files/jiotv-playlist.m3u8`, no tokens, cookies or keys)
   and used when every shape is refused, for five minutes at a time before the network is asked again.
   The file describes the same channels for days: each channel's own address, cookie and ClearKey are
   still resolved fresh at the moment it is played. An outage can therefore delay the *list*, never empty
   it - and when there is no copy to fall back on, the tab says `HTTP 415` rather than pretending there
   are no channels.

### The Sony row inside JioTV plays the playlist

4.9.58 decided which player to use from the tile, not the channel's name: a tile carrying a page opens the
page. That is right on Live TV and wrong inside JioTV, where the playlist is the player and the viewer
asked for native playback there. `SonyPage.playlistChannels(...)` now builds the JioTV copy of the section
with the page removed from every tile the playlist actually carries, so those tiles play natively like
every other channel in that section; a tile the playlist does not carry (there is no native source at
all) keeps its page instead of going silent. Live TV's copy is untouched.

## 18. 4.9.61 — the site's servers, played by our own player

theogpiratebot.online lists its servers by name (`?server=vidstucki`, `vidfast`, `vidlink`, …) and embeds a
third party player for each. Those players are pages, but a page for a title names the media address it is
about to play - a bare `.m3u8`/`.mp4`, the JSON field it keeps that address in (`file`, `playlist`, `source`,
`src`, `hls`), or, when one server wraps another, one more embed in an iframe. That is what `OgNative`
reads:

1. each of the site's server families is asked for the title in its own scheme
   (`vidstuck.xyz/embed/movie/<tmdb>`, `vidstuck.xyz/embed/tv/<tmdb>/<s>/<e>`, `vidfast.pro/...`,
   `vidlink.pro/...`), with a browser identity and the site as the referrer;
2. the media addresses the page names are taken from the body, one iframe hop deep, with escaped slashes
   resolved;
3. only **HLS and MP4 over https** are kept. A DASH manifest from these hosts means Widevine, which this
   app never touches, and an embed page or a plain http address is not media;
4. what is left becomes ordinary native sources (`FrostProvider.OG`), so the existing preflight probes
   them, the existing source menu switches between them, and the existing Media3 player plays them - the
   same path as TG FROST and TG OUT, with no browser anywhere.

In the movie/series player the source menu gains **"Reload OG servers (native · Vidstuck / Vidfast /
Vidlink)"**. Because the provider is a normal member of the provider list, it is also what the player
falls back to when both TG providers have nothing.

Addresses are read fresh for every title; a stored one would be dead within hours. A server that names no
address contributes no source rather than a black screen, and when every server is silent the card says so
with the player's own diagnostics.

## 19. 4.9.62 — the gate in front of the playlist, and the site's second list

`HTTP 415` survived 4.9.60's five request shapes, and the reason is that it is not a header problem: the
site put a **script gate** in front of its playlists. A scriptless client is refused outright; a browser
that runs the gate gets the file. Nothing in an HTTP client can pass that, which is why every shape was
refused on the TV while the site itself plays fine in a browser.

So the last resort is now the same request made by a browser:

- `BrowserFetch` loads the address in a hidden `WebView` on the main thread, waits (500 ms steps, 15 s
  ceiling) until the document body really contains `#EXTM3U`, and returns the text;
- `JioM3uResolver` only calls it **after** all five scriptless shapes have been refused, and only accepts
  the answer when it parses as a playlist. A box with no `WebView` simply keeps the refusal and the
  section's message stays truthful.

The site's second list, `playlist/ttv6t.m3u`, is now served by the same resolver as an extra playlist:
its channels resolve natively with the same probe, cookie and ClearKey path, and the JioTV tab shows them
as their own **TATA** section (a "TATA · Playlist" row plus one row per category the file declares). The
two lists are independent - one being refused never empties the other - and both are kept on disk so a
refused refresh can delay a section but never blank it.

### OG says what each of its servers answered

An OG title that ends with "no native sources" now names the reason per server, in the card and in the
playback details: `Vidstuck: HTTP 403 · Vidfast: no address on its page · Vidlink: unreachable`. That is
the difference between "OG does not work" and a fixable answer, and it is what the next round is built
from. The request also carries `Origin` as well as `Referer` now, because some embed hosts check the
former.

## 20. 4.9.63 — a slow link, an upgrade, and where the second list lives

4.9.62 reported *"no connection to the server"* on a TV whose status bar showed 7 KB/s. That is the shape of
the problem: the playlist is a large file, `TgHttp` gave it 12 seconds, and the first request on a link that
slow is dropped. Three things follow:

1. **The playlist gets its own transport** - 30 s read, 45 s call - so a slow link is slow, not failed. Media
   probes keep the short timeouts they need.
2. **One silent retry** per list, 700 ms apart, and one silent retry of the whole section (5 s later) before
   the tab is allowed to say it failed.
3. **The copy kept by 4.9.61 is still read**: the cache moved into `files/playlists/`, but the old
   `files/jiotv-playlist.m3u8` is consulted when the new file is not there yet, so an upgrade does not lose
   the list that answered yesterday.

The browser-engine read is now only attempted for a *gate* (`403`, `415`, `429`, `503`), never for a dead
connection, where waiting for a browser would only make the section slower to say what is wrong.

The section message also names the real reason now - "the server's address did not resolve (DNS)", "the
server did not answer in time", "the server refused the connection" - instead of one phrase for all three.

The second list (`TATA · Playlist`) sits at the top of the JioTV tab rather than below the Sony row: the TV
rail is full (Live, Always on, JioTV, Movies, Series, Search), so the site's second list is a section inside
JioTV, and it now renders as soon as it loads even when the first list has not.

## 21. 4.9.64 — the crash on Back, the seventh tab, and a stream that only exists in script

### The crash

`refreshRowsKeepFocus` rebuilt the rows for a channel tab with the VOD branch:

```kotlin
else -> continueRow() + vodRows[tab - 3]
```

On the JioTV tab that is `vodRows[-1]`, and it threw `ArrayIndexOutOfBoundsException: length=2; index=-1`
from `closePlayer` → `dispatchKeyEvent` — so Back, out of a player, back to the JioTV tab, closed the app.
Every tab now has its own branch, and the two helpers `channelsTab` / `vodTab` / `vodRowsFor(t)` are the only
way the code asks what a tab is. `vodRows` can no longer be indexed by a tab that is not 3 or 4, anywhere.

### The seventh tab

The TV rail is Live, Always on, JioTV, Movies, Series, **TATA** - the site's second list has its own tab now,
with the site's own order, one row per category it declares, its own header, its own count and its own
message when it cannot load. Selecting the tab again retries it.

### OG: a stream that only exists after the page runs

`Vidstuck: no address on its page · Vidfast: no address on its page · Vidlink: no address on its page` is the
truth about those pages: they contain no address. The address is built by their scripts, and only the request
they then make names it. So the last resort is no longer a scrape but a **run**: `MediaSniffer` loads the
site's own player page in a hidden browser with JavaScript on, records every address the page's fetch/XHR/
`<video src>` asks for (plus the bodies of its JSON answers), and takes the first `.m3u8`/`.mp4` it finds -
skipping `.mpd`, because that is Widevine here. That manifest is then played by the same native Media3
player as everything else, with the site as `Referer`. It is the same stream the site plays; the app simply
plays it natively, as asked.

## 22. 4.9.65 — an update nobody was told about

4.9.64 was published while a 4.9.63 installation was inside its **six-hour** automatic-check throttle, and
the throttle had been stamped in `SharedPreferences`, which survives an install. So the release existed, the
app was one version behind, and nothing said so — for up to six more hours.

Three changes, all in the same place:

- the automatic interval is **20 minutes**, not six hours;
- a **failed** check no longer counts: the timestamp is written only when GitHub actually answered, so a
  flaky launch is retried on the next one instead of silencing the app;
- **a build that was installed after the last check always asks**, because `update_checked_code` no longer
  matches the installed code. Freshly installing a release can never hide the next one.

The manual route is unchanged and still the fastest: **Playback details → Check for updates**.

## 23. 4.9.66 — the section paints from disk again

Two things had made the JioTV tab slower than 4.9.61, and both were mine:

- `jioCatalogue()` returned the **merged** result of both of the site's lists, so the JioTV section waited for
  the second list before it could draw anything. Each tab now loads only its own list, and `resolve()` still
  searches every list, so a TATA channel still plays from either tab.
- the cache on disk was only consulted **after** a request failed, and the request may now take 45 seconds on
  a slow link. The disk copy is read **first**: the section appears in the time a file read takes, and the
  network answer replaces it when it arrives.

Selecting the TATA tab is what puts that list on screen; if it has never loaded, it says so and a second
selection retries it.

## 24. 4.9.67 — watching the requests, not the HTML, and saying the whole of it

The 4.9.64 card was cut off at `· site`, and the sniffer it reported was looking in the wrong place: a page
cannot see the requests made inside a cross-site iframe, and these players run inside one. So the sniffer no
longer uses scripts at all - `WebViewClient.shouldInterceptRequest` sees **every** request the page and its
iframes make, whatever their origin, and returns null so the page keeps working. The page is loaded inside a
one-page wrapper whose base URL is the site, so the embed sees the site's `Referer`, which several of these
hosts require. A player that waits for a click is nudged twice (a `play()` and a click on the first
play/server control), `images` are blocked so a TV link is not spent on artwork, and `.mpd`/ad hosts are
still never offered.

The order is: the site's own player page first (that is exactly what a viewer opens), then, if it names no
manifest, the servers' own player pages one at a time.

The report is no longer truncated to two lines. Each line is a fact - `site player: 41 requests, 7 script(s),
no manifest`, `Vidstuck player: manifest found` - the card carries 320 characters of it, and **Playback
details** carries all of it, followed by the usual diagnostics, so one screenshot answers the question.

## 25. 4.9.68 — the master, not the audio track; and a source that stays in the list

The sniffer worked in 4.9.67 - the menu showed `1. OG · OG Vidfast · HLS` - but the film played **sound over
a black screen**, because the first manifest a player fetches is often an **audio rendition** and that is what
was taken. Now the sniffer keeps watching for six seconds after the first manifest, collects every candidate,
and then **reads** them: the one that lists `#EXT-X-STREAM-INF` with a `RESOLUTION` is the master, and the
master is what the native player needs. Audio-named addresses are only used when nothing else exists, and the
report says `N manifest(s), none carries video` when that is the truth.

Two other things that made it feel broken:

- **the OG entry disappeared from the menu** after one failure, because starting a source replaced `sources`
  with the next provider's list. OG sources found for the title are now kept separately and always listed;
- **the second try cost a full search again**. `OgNative` remembers what it found for a title for 30 minutes,
  so opening the film again starts from the same stream immediately. The whole search is also capped at 45
  seconds, so a title that cannot be found fails in a bounded time instead of a minute.

## 26. 4.9.69 — play what the site's player plays, on the first attempt

Both TG FROST and TG OUT answer `api/sources?server=tgflix|tgout&tmdb_id=…`, and both keep a page a viewer
opens: `tgfrost.lovable.app/watch/movie/{id}` (and `/watch/tv/{id}/{season}/{episode}`, `?server=tgout` for
the second). The site's own player is what silently hops servers until one answers - the behaviour the docs
describe as "broken servers are skipped automatically" - and until now the app left that work to the viewer,
which is why the second try worked and the first did not.

So the resolver now does what the site does:

1. ask its own endpoint (fast, as before);
2. if the answer is empty, or contains **only DASH** (which from these hosts is Widevine and cannot be opened
   here - the `MovieBoxV2 · 1080p · DASH` case), **run the site's own player page** in the hidden browser and
   take the `.m3u8`/`.mp4` that player actually plays, chosen by reading the manifests rather than by name;
3. play that address with this app's native Media3 player, with the site as `Referer`, labelled
   `TG FROST · site player · HLS`.

The result is remembered per title and provider for 30 minutes, so the first open does the work and every
later open starts immediately. The same mechanism is what OG has used since 4.9.67; this release gives the
two TG providers the same last resort, which is why both now play the stream the website plays.

## 27. 4.9.70 — the site the viewer actually opens

`tgflix.lovable.app` is the player the viewer uses, and it opens with **"Who's Watching?"** - every path,
including `/watch/movie/{id}`, answers with that profile screen first. So the fallback now:

- asks **`tgflix.lovable.app` first**, then `tgfrost.lovable.app` (the same family, the host the app's own API
  has always used);
- loads the page **directly**, with no wrapper, because passing the profile screen needs real navigation;
  the wrapper that supplies a `Referer` is still used where it helps (OG's embed pages);
- **chooses the profile** the way a viewer does - the first profile/avatar/continue control, or the first
  image on the grid - and then re-opens the title, since that screen replaced the page that was asked for;
- again takes only a manifest that carries video, on the master playlist.

`tgflix.qzz.io`, which the viewer also listed, answers *"This deployment is temporarily paused"* - that host
has nothing to play and is not used.

## 28. 4.9.71 — the route the site really uses

`tgflix.lovable.app/play/{tmdbId}` is the viewer's own address for a title, and `/watch/movie/{id}` - what
4.9.70 tried - is not a route on that deployment: the app was passing the profile screen and then asking for
a page that does not exist, which is why nothing was found. The fallback now walks the addresses this family
of sites actually plays on, in this order:

1. `tgvid.lovable.app/embed/movie/{id}` - the documented embed player, and the only one with **no profile
   screen** in front of it;
2. `tgflix.lovable.app/play/{id}` - the viewer's route, behind "Who's Watching?";
3. `tgfrost.lovable.app/watch/movie/{id}` - the host the app's own API has always used.

Each page is loaded the way it needs to be: the embed through the Referer wrapper, `tgflix` directly so the
profile screen can be passed. The profile is clicked, and the title is then **re-opened by its own address**
- at most once, so it cannot loop - because the profile screen (or the home screen it leads to) replaces the
page that was asked for. The whole walk has a 45-second ceiling.

## 29. 4.9.72 — the app that never asked again

4.9.65 shortened the interval to twenty minutes, and it still did not help: the check ran **only at start-up**
and when the network changed. A television - or a phone with the app left open - can stay in front for days,
so a build published at noon was never noticed at six in the evening. Two changes:

- the check is **repeated while the app is in front** (every five minutes, still subject to the twenty-minute
  floor, cancelled when the app goes to the background), so an open app learns about a release;
- the **version label in the rail is tappable** on the phone layout and runs the check directly, so the
  update route no longer depends on finding Playback details first.

`https://github.com/creatorAkshanshsahu/v.4.9/releases/latest/download/update.json` was verified to be the
release's own manifest (`/releases/latest` resolves to the rolling `latest` tag), so the failure was never the
address - it was that nobody asked.

## 30. 4.9.73 — the site's servers have names

The viewer's own link for a film is

```
theogpiratebot.online/player?id=656908&type=movie&server=elfura
```

`server=elfura`, not a number - and the app was asking the site for `/player?id=…` with **no server at all**,
then guessing three third-party embed hosts that this site does not necessarily use. That is why a film the
website plays produced nothing here.

The fallback now asks the **site's own player, by its own server names** (`elfura` first, then the three host
names the app already knew), loading the page directly so it navigates exactly as it does for a viewer and the
player inside it receives the site as its `Referer`. The name that yields a manifest is remembered for the
session and moved to the front, so the next title starts with the server that works. Each probe is bounded to
12 seconds and the host-page probes to 14, so the whole search still fits inside the same ceiling.

The card and Playback details now name each attempt: `server elfurain: manifest found`,
`server vidfast: 21 requests, 4 script(s), no manifest`. That is the difference between "the site refused us"
and "we asked the wrong server".

## 31. 4.9.74 — the crash on the first page load

```
java.net.URISyntaxException: Illegal character in scheme name at index 0:
  "https:\/\/tgvid.lovable.app\/embed\/movie\/1507696"
  at MediaSniffer$Reader.nudge(MediaSniffer.kt:196)
```

That quoted, slash-escaped address is the giveaway: `nudge()` built the JSON-quoted form of the address into a
local named `target`, and then read `URI(target).path` - so the URI was parsed from the *quoted* string, whose
first character is `"`. Index 0 is not a scheme, and the exception travelled out of `onPageFinished` and killed
the app.

The route is now read from `this.target`, the real address, inside `runCatching`, and the quoted form has its
own name. Both page callbacks (`onPageFinished` and the poll) also wrap the nudge, so a page callback can never
take the process down again.

## 32. 4.9.75 — Sony natively, the TATA list read properly, and a TV that survives

### The TATA list was never parsed

`#EXTINF` was matched by **position**: `tvg-id`, then `group-title`, then `tvg-logo`, in that order. The JioTV
list happens to write them in that order; the site's second list does not, so `parse()` returned **zero
entries** and the tab stayed empty while JioTV worked. Attributes are now read by name, `#EXTINF` ids fall back
to the `id=` in the address, relative addresses resolve against **their own** playlist (`/playlist/ttv6t.m3u`,
not `jtv33.m3u8`), and the second list loads on **its own thread** instead of behind the first one, with its own
retry, so a slow JioTV fetch cannot delay it.

### Sony plays natively

The Live TV Sony row is now built with the playlist-bound tiles (`SonyPage.playlistChannels`): a tile that the
site's own playlist carries plays that channel on **this app's player**, with its native manifest and its
ClearKey document - not the site's browser player. Only a tile the playlist does not carry keeps the page as
its last resort.

### The TV

The browser engine is the one part of this app that can take the whole process down on a television, and it was
being created repeatedly (once per server, per title). It is now **off by default on a TV or a low-RAM device**
(`SiteBrowserGate`): the native paths never touch it there, and the JioTV/OG/site-player paths say so instead of
crashing. On a phone it stays on. The switch is the last entry of **Change source** (`Site player search: ON/OFF`),
so a box that survives it can be turned back on deliberately.

### The look

A new identity, built around the mark the viewer actually uses: ink-black ground (`#050609`), lacquered crimson
accents (`#E00E27`), cool neutral greys instead of the previous purple cast, larger corner radii on every tile,
a quieter rail, and the launcher icon is the AK TV mark itself.

## 33. 4.9.76 — instant

Twenty minutes was still not "now". A build published while the viewer is looking at the app should be offered
within a minute or two, and nothing about the check is expensive: it is one small file.

- the floor between automatic checks is **90 seconds** (was 20 minutes), and the tick that drives it while the
  app is in front is **2 minutes** (was 5);
- coming back to the app **always asks**, with only a 45-second floor, so "publish, switch back, update" works;
- the request carries a **changing query string** (`?t=<minute>`), because the CDN in front of a release asset
  can keep serving the previous `update.json` for a few minutes after a publish, which looks exactly like "the
  update never arrived".

`updates.json` itself is unchanged: same asset, same manifest, same SHA-256 verification before the installer
is invoked.

## 34. 4.9.77 — the Sony tiles the playlist spells differently

The page's 22 tiles are addressed by **slug** - `sony-yay`, `sony-ten-3-hd` - and each slug is also a name.
The playlist spells several of them its own way ("Sony YAY" for `sony-yay`, "Sony Ten 3" for `sony-ten-3-hd`),
so the exact-name and logo passes missed and those tiles kept the site's browser player. A third pass now
binds them by what the slug says: `sony-ten-3-hd` yields "sony ten 3 hd", "sony ten 3" and "ten 3 hd".

That matters because the site's own player (`suniliv.pages.dev/ptest1.html?id=<slug>`) is **not readable**: it
is a script player behind an anti-bot screen ("Brave Shields Detected"), with no manifest in its markup. What
*is* readable is the same site's **playlist** (`jtv33.m3u8`), which carries these Sony channels with their
native manifest (`jtv1.mpd?id=<n>`), their ClearKey document (`jtv-fetch/keyid.php?id=<n>`) and their cookie.
So every tile that the playlist carries now plays **natively** - the same channel the page plays, on this
app's player - and only a tile the playlist does not carry at all keeps the page as its last resort.

## 35. 4.9.78 — the new mark, Ten 4 / Ten 5, and the answer a script keeps to itself

### The mark

The launcher icon is no longer the only place the new identity appears: the intro draws it, and the rail's
brand image is it. One asset (`aktv_mark_v4`) for the icon, the entrance and the app.

### Ten 4 and Ten 5

They were the two tiles that stayed on the browser. The playlist can spell them in more than one way - Ten 4
also carries a Telugu and a Tamil feed, and **Ten 5 was Sony Six**, which the list may still call it - so both
tiles now list every spelling the site has used: `sony ten 4 hd`, `ten 4 hd`, `sony ten 4 telugu`,
`sony six`, `six`, `sony ten 5 hd`, and the slug form. A tile that binds is native; a tile that does not is the
site's browser player, so the aliases are the whole difference.

### OG: the stream a JSON answer names

The sniffer watched **requests**. A player that asks an API "what is the stream for this title?" gets the
address back in the **body**, and no request ever contains it - which is why OG's own player resolved nothing
while TG FROST's plain embed did. `MediaSniffer` now also installs a small script hook that keeps the last few
`fetch`/`XHR` response bodies and reads them for a `.m3u8`/`.mp4`, alongside the network-level interception
that sees iframes and segments. Whichever way the player learns its stream, one of the two channels sees it.

## 36. 4.9.79 — a television that can open the app

The mark I shipped in 4.9.78 was a 1024x1024 PNG, and the app decoded it **twice** (the entrance and the rail):
about four megabytes of bitmap each time, on a box where the whole heap can be 256 MB. The in-app copy is now a
256x256 asset (33 kB); the full-size file is only the launcher icon, where another process decodes it.

Three more things that only matter on a TV:

- **the entrance is skipped** on a television, a low-memory device, or a run that follows a crash;
- **the second playlist waits for its tab** on a TV, instead of two playlist fetches at once during start-up;
- **safe mode**: if the previous run ended by itself (`CrashLog` has a note), this run starts with the entrance,
  the hero previews, the browser engine, the movie prefetch and the second playlist all off. The app has to be
  usable *after* a crash, not repeat it - and the crash note is still offered so it can be sent.

## 37. 4.9.80 — the report card was the freeze

The dialog that offered the previous run's crash note was itself the thing that stopped a TV: a modal window
built over a box that is still starting up (rows, playlist fetch, entrance) is enough to take it out. So:

- on a **television** (and in safe mode) there is no dialog at all: one line of toast says the previous run
  stopped, and the **full note is carried inside Playback details**, which already has a Copy button;
- a phone still gets the dialog, fifteen seconds in instead of six, with the note capped and the whole thing
  wrapped so a failure to show it cannot close the app.

Playback details also now states the browser-engine state (`site browser engine: off on this TV…`) and whether
this run is in safe mode, so one copied report answers the next question without another round trip.

## 38. 5.0.0 — STREAMR

The app is STREAMR now, by Akshansh, and it does not share a screen with the old one.

- **Name and mark**: launcher icon, entrance reveal (mark, then the wordmark, then "BY AKSHANSH"), the app
  label, and every report, toast and clipboard label. The mark is the supplied amber play glyph.
- **Palette**: near-black blue ground, amber brand signal, a single red reserved for LIVE and progress.
  Generated channel tints now live in the amber arc (24..46 degrees), so a wall of tiles reads as one brand.
- **Chrome**: the side rail is gone. A top glass bar holds the mark and wordmark, a pill tab group, Search and
  the profile; the hero is full-bleed beneath it. The active tab is a white pill with dark type, focus is a
  glass fill with a 3dp white ring, and the hero's primary action is a white pill with dark type.
- **Sizing**: 300x169 landscape cards, 176-wide posters, 22sp row titles, 46sp hero titles, 12dp corners.
- **TATA is removed**: its tab, its start-up load and its retry path.

## 39. 5.0.1 — Always on goes, and the chrome follows the home screen

- **"Always on" is removed**: the tab is gone from the top bar and from the phone pill row. The internal tab
  ids did not move (0 Live, 1 retired, 2 JioTV, 3 Movies, 4 Series); a `tabIds` map translates a pill position
  into a section id, so nothing else in the screen code had to change.
- **Players**: action chips are glass with a white ring instead of crimson gradients, the seek bar is the one
  red in the chrome, the mark in the player is the STREAMR mark, and the scrims sit on the new ground.
- **Posters and trending cards**: 12dp corners with a hairline edge, the bottom scrim uses the new ground, and
  focus is the same 3dp white ring as the rest of the app.
- Every remaining reference to the old wordmark asset now uses the new mark.

## 40. 5.0.2 — the last of the old surfaces

The shared list/row background (`Ui.focusBg`) still activated to a slate blue from the old identity; it now
activates to amber glass, so every list that uses it - search keys, the channel guide, episode lists - matches
the bar. The search layer already sits on the new ground and inherits the new type.

## 41. 5.0.3 — a source that buffers is not a source that plays

A movie host serves its manifest to the page it belongs to. The app was asking with the site's root as
`Referer` no matter which page it had taken the stream from, so a CDN that checks the referer answered with
something the player could not use - and a player that cannot load segments shows a spinner, not an error.

- **The page travels with the source**: `Referer` is the page the stream was sniffed from, `Origin` is that
  page's origin. Both are sent by the player's own HTTP client, per source.
- **The sniffer asks with the same referer** when it reads a manifest to decide whether it carries video;
  before, it always claimed to come from one site, which is why the choice could be blind.
- **A body can name a trailer or an ad as readily as a stream**, so text-scraped candidates are manifests only
  (`.m3u8`, `.mpd`). Network-level interception still catches a direct file.
- **Bounded startup**: sixteen seconds without a picture moves to the next source (was thirty), a stream that
  stops responding for twenty seconds does the same (was forty), and the whole startup budget is 45 seconds
  (was 120). A spinner is now a state that ends.

## 42. 5.0.4 — the site's player is TG, and an address that answers 404 is not a source

The playback details from the box named it exactly: `#1 OUT / HLS … HTTP 404 / Media3 2004 /
InvalidResponseCodeException`, `manifest 0 … 0 bytes`, and on the OG side `Vidstuck: no address on its page ·
Vidfast: no address on its page · Vidlink: no address on its page`. Two separate faults, one shape.

**A dead address was still being offered.** The endpoint answered, so its URLs were handed to the player; the
manifest then 404'd, and a player that gets 404 does not fail - it retries, holds the spinner, and burns the
viewer's sixteen seconds. `FrostPreflight` now asks each address once, with the same client and the same
per-source headers the player uses: `401/403/404/410`, an HLS answer that is not a playlist, or a dead MP4 is
dropped; a timeout is kept, because a slow link is not a dead one. If nothing survives, the app says the
addresses were stale instead of pretending to start.

**OG is TG underneath.** theogpiratebot's player offers TG FROST and TG OUT for the same title id - it is the
same stream, one layer down. So OG now asks TG's own endpoint **first**: headless (no browser engine, so it also
works on a television, where the engine is off), no page scraping, no iframe hop. The site's own servers are
still asked after that, and their addresses are preflighted the same way. The documented `tgvid` route is also
asked by id (`/docs?id=<id>&type=<movie|tv>`), which is the page that has served this player across builds.

## 43. 5.0.5 — the browser engine is gone

Every page-reading path in this app used a WebView: OG's server pages, TG FROST's `/play` page, the JioTV gate,
the Sony tile fallback, the movie trailer panel, and the playlist's last-resort fetch. On a television that
engine is a stripped Chromium, and creating it takes the whole process down - which is exactly the "it opens
and closes on the TV, it runs on the phone" report. So it is removed, not defaulted off:

- `SiteBrowserGate.allowed` is a `const false`; `load()` is a no-op, so a stored preference cannot turn it on;
- `MediaSniffer.find()` returns "nothing found" without constructing anything - the entry point stays so the
  resolvers still compile, and no page is ever run;
- `BrowserActivity` and `BrowserFetch` are deleted, and the manifest no longer declares a browser activity;
- the movie player's source menu loses the site-server entries and its engine switch: every entry plays here;
- the trailer panel (a YouTube WebView) is deleted with its button - the button was `lateinit`, and a screen
  that touched it would have thrown;
- a Sony tile with no native source now says so instead of opening the site's page;
- `TgServers.init` and the home setup are guarded, and the crash note is installed on the first line of
  `onCreate`, so a start-up failure leaves a report instead of an app that closes with nothing recorded.
