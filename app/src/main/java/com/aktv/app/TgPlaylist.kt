package com.aktv.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Turns a TG TV playlist entry into a fully-armed native stream.
 *
 * The playlist tab used to hand the player each entry's bare address with a generic user agent and
 * nothing else. That plays only while the file lists plain, ungated HLS: an entry whose stream is
 * DRM-protected or token-gated declares its key endpoint, cookie and user agent in the file, and a
 * tune that drops those declarations is refused (HTTP 403/450/451) or stalls on undecryptable
 * segments. The declarations the parser keeps travel onto the [TgStream] instead:
 *
 * - the entry's user agent rides the media requests (an IPTV player sends exactly that);
 * - the entry's `#EXTHTTP` cookie arms [StreamRequestPolicy], which signs same-origin manifest and
 *   segment requests with it and never leaks it cross-origin;
 * - ClearKey material becomes the stream's DRM: inline `kid:key` pairs are attached directly, while a
 *   key-document URL is passed through and the player reads that document itself when the DRM session
 *   opens (no key is ever written to disk).
 *
 * Kept Android-free so the mapping can be regression tested on the JVM.
 */
internal object TgPlaylist {
    const val SITE = TgResolver.SITE
    const val PLAYLIST = "$SITE/api/public/playlist.m3u"
    private const val HOST = "livetgtv.lovable.app"

    /** "dash", "hls" or "mp4" from the media URL, never from the page. */
    fun mediaType(url: String): String = when (url.substringAfterLast('.', "").substringBefore('?').lowercase()) {
        "m3u8" -> "hls"
        "mp4" -> "mp4"
        else -> "dash"
    }

    /**
     * The key document this entry advertises, when it is an HTTPS URL on the playlist's own host.
     * Anything else is not followed: a key endpoint on an unrelated host is not this entry's.
     */
    fun licenseUrl(entry: M3uChannel, playlistHost: String = HOST): String? {
        val raw = entry.licenseKey.trim()
        if (!raw.startsWith("https://", true)) return null
        return try {
            val uri = URI(raw)
            raw.takeIf {
                uri.scheme.equals("https", true) && uri.userInfo == null && uri.host != null &&
                    uri.host.equals(playlistHost, true) && uri.fragment == null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The entry's ClearKey material in the app's DRM shape, or null when the entry declares none.
     * A non-ClearKey licence type is left alone: configuring a ClearKey session for it would fail
     * less clearly than letting the player meet the stream as listed.
     */
    fun buildDrm(entry: M3uChannel, playlistHost: String = HOST): JSONObject? {
        if (entry.licenseType.isNotBlank() && !entry.licenseType.contains("clear", true)) return null
        val pairs = LinkedHashMap<String, String>()
        entry.licenseKey.takeUnless { it.trim().startsWith("http", true) }
            ?.takeIf { it.isNotBlank() }
            ?.let { pairs.putAll(JioM3uProtocol.parseKeys(it)) }
        val keyUrl = licenseUrl(entry, playlistHost)
        if (pairs.isEmpty()) {
            // No key material yet: keep the advertised endpoint so the player can ask for it itself.
            return keyUrl?.let { JSONObject().put("type", "clearkey").put("licenseUrl", it) }
        }
        val array = JSONArray()
        pairs.forEach { (kid, key) -> array.put(JSONObject().put("kid", kid).put("k", key)) }
        val drm = JSONObject().put("type", "clearkey").put("keys", array)
        keyUrl?.let { drm.put("licenseUrl", it) }
        return drm
    }

    /** The entry as the native player opens it: the playlist's own identity, cookie and DRM. */
    fun toStream(entry: M3uChannel, label: String = "TG TV", playlistHost: String = HOST): TgStream =
        TgStream(
            label = label,
            url = entry.url,
            type = mediaType(entry.url),
            drm = buildDrm(entry, playlistHost),
            headers = mapOf("User-Agent" to entry.userAgent.ifBlank { TgHttp.USER_AGENT }),
            cookie = entry.cookie,
            requestOrigin = SITE,
            // A playlist address is a plain stream, opened the way an IPTV player opens it: no
            // browser Origin or Referer, only the player's own user agent.
            omitReferrer = true,
            omitOrigin = true
        )

    /**
     * Where a playlist tune goes after the playlist's own address fails. TG's server configuration
     * is keyed by channel number, so only an entry that names one gets a second act — and the JioTV
     * playlist shares TG's numeric namespace, so a numeric id earns that lookup too.
     */
    fun fallbackProviders(tvgId: String): List<LiveProvider> = when {
        tvgId.matches(Regex("\\d{1,10}")) -> listOf(LiveProvider.TG, LiveProvider.JIOM3U)
        tvgId.matches(Regex("[A-Za-z0-9_-]{1,80}")) -> listOf(LiveProvider.TG)
        else -> emptyList()
    }
}
