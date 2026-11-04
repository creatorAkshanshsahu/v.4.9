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
  prompt; nothing downloads without that tap.
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
