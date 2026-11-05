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
