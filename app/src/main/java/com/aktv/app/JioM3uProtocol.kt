package com.aktv.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Static data contract for the allinonereborn2.online JioTV m3u8 playlist.
 *
 * The playlist is a standard M3U8 with per-channel blocks:
 *
 *   #EXTINF:-1 tvg-id="143" group-title="English" tvg-logo="…",CNBC TV18 Prime
 *   #KODIPROP:inputstream.adaptive.license_type=clearkey
 *   #KODIPROP:inputstream.adaptive.license_key=https://allinonereborn2.online/jtv-fetch/keyid.php?id=143
 *   #EXTVLCOPT:http-user-agent=@allinone_reborn
 *   #EXTHTTP:{"cookie":"__hdnea__=st=…"}
 *   https://allinonereborn2.online/jiotv-m3u/jtv1.mpd?id=143
 *
 * Three transport variants appear:
 *  1. JioTV DASH  — license_key is a URL, cookie + UA override required
 *  2. SunNxt DASH — license_key is inline `keyid:key`, no cookie
 *  3. Local HLS   — no DRM, plain m3u8 URL
 *
 * No remote JavaScript, adverts or embeds are executed.
 */
internal object JioM3uProtocol {

    const val SITE = "https://allinonereborn2.online"
    const val PLAYLIST = "$SITE/jiotv-m3u/jtv33.m3u8"
    const val UA_OVERRIDE = "@allinone_reborn"
    /** Only the playlist's own host may supply the key document; no other origin is followed. */
    val HOST: String = URI(SITE).host.orEmpty()
    const val MAX_KEY_BYTES = 64 * 1024

    data class Entry(
        val id: String,
        val name: String,
        val category: String,
        val logo: String,
        val url: String,
        val licenseKey: String?,       // raw KODIPROP license_key value (URL or keyid:key)
        val licenseType: String,       // "clearkey" or ""
        val userAgent: String?,        // EXTVLCOPT override or null
        val cookie: String?            // EXTHTTP cookie or null
    )

    // #EXTINF:-1 tvg-id="143" group-title="English" tvg-logo="…",CNBC TV18 Prime
    private val extinf = Regex(
        """^#EXTINF:.*tvg-id="(?<id>[^"]*)"(?:\s+group-title="(?<cat>[^"]*)")?(?:\s+tvg-logo="(?<logo>[^"]*)")?\s*,(?<name>.+)$""",
        RegexOption.IGNORE_CASE
    )
    // Also matches entries with tvg-name instead of comma-separated name
    private val extinfNamed = Regex(
        """^#EXTINF:.*tvg-name="(?<name>[^"]*)"(?:\s+group-title="(?<cat>[^"]*)")?(?:\s+tvg-logo="(?<logo>[^"]*)")?\s*,?(?<id>[^"\s]*)\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val kodiLicenseType = Regex(
        """^#KODIPROP:inputstream\.adaptive\.license_type=(?<val>\S+)\s*$""", RegexOption.IGNORE_CASE
    )
    private val kodiLicenseKey = Regex(
        """^#KODIPROP:inputstream\.adaptive\.license_key=(?<val>\S+)\s*$""", RegexOption.IGNORE_CASE
    )
    private val extvlcUserAgent = Regex(
        """^#EXTVLCOPT:http-user-agent=(?<val>\S+)\s*$""", RegexOption.IGNORE_CASE
    )
    private val extHttpCookie = Regex(
        """^#EXTHTTP:\{"cookie":"(?<val>[^"]*)"\}\s*$""", RegexOption.IGNORE_CASE
    )

    /** A media URL line: starts with http and is not a comment or directive. */
    private val mediaUrl = Regex("""^https?://\S+$""")

    fun parse(body: String): List<Entry> {
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val entries = ArrayList<Entry>(200)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val ext = parseExtinf(line)
            if (ext == null) { i++; continue }
            val (id, name, category, logo) = ext

            var licenseKey: String? = null
            var licenseType = ""
            var userAgent: String? = null
            var cookie: String? = null
            var url: String? = null

            var j = i + 1
            while (j < lines.size && !mediaUrl.matches(lines[j])) {
                val l = lines[j]
                kodiLicenseType.matchEntire(l)?.let { licenseType = it.groupValues[1] }
                kodiLicenseKey.matchEntire(l)?.let { licenseKey = it.groupValues[1] }
                extvlcUserAgent.matchEntire(l)?.let { userAgent = it.groupValues[1] }
                extHttpCookie.matchEntire(l)?.let { cookie = it.groupValues[1] }
                j++
            }
            if (j < lines.size && mediaUrl.matches(lines[j])) {
                url = resolveUrl(lines[j], PLAYLIST)
                j++
            }
            if (url != null && id.isNotBlank()) {
                entries.add(Entry(id, name, category, logo, url, licenseKey, licenseType, userAgent, cookie))
            }
            i = j
        }
        return entries
    }

    private fun parseExtinf(line: String): Quad? {
        extinf.matchEntire(line)?.let { m ->
            return Quad(m.groupValues[1], m.groupValues[4].trim(), m.groupValues[2], m.groupValues[3])
        }
        extinfNamed.matchEntire(line)?.let { m ->
            return Quad(m.groupValues[3].trim(), m.groupValues[1], m.groupValues[2], m.groupValues[4])
        }
        return null
    }

    private data class Quad(val id: String, val name: String, val category: String, val logo: String)

    private fun resolveUrl(raw: String, page: String): String? = try {
        URI(page).resolve(raw).let { uri ->
            uri.toASCIIString().takeIf { uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() }
        }
    } catch (_: Exception) { null }

    /** Build a TgStream from an Entry, attaching ClearKey DRM (inline keys and/or a fetched key document). */
    fun toTgStream(entry: Entry, keys: Map<String, String> = emptyMap()): TgStream {
        val drm = buildDrm(entry, keys)
        return TgStream(
            label = entry.name,
            url = entry.url,
            type = mediaType(entry.url),
            drm = drm,
            headers = requestHeaders(entry),
            // Deliberately empty: this playlist's `#EXTHTTP` cookie holds a *Jio CDN* token, not a cookie
            // for the playlist host. Handing it to the request policy made every manifest request carry a
            // stale `__hdnea__` for the wrong resource; the host mints a fresh one on each redirect.
            cookie = "",
            requestOrigin = SITE,
            // ... and no browser origin at all. The media host is Jio's CDN, and it refuses a request that
            // carries `Origin`/`Referer: allinonereborn2.online` (HTTP 451) far more often than the bare
            // request a normal IPTV player sends - which is exactly why the same playlist plays there and
            // not here. The identity above is still the playlist's, so provider attribution is unchanged.
            omitOrigin = true
        )
    }

    /** "dash", "hls" or "mp4" from the media URL, never from the page. */
    fun mediaType(url: String): String = when (url.substringAfterLast('.', "").substringBefore('?').lowercase()) {
        "m3u8" -> "hls"
        "mp4" -> "mp4"
        else -> "dash"
    }

    /** The headers the playlist itself declares for its media URL. */
    fun requestHeaders(entry: Entry): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        entry.userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        headers["Origin"] = SITE
        headers["Referer"] = "$SITE/"
        return headers
    }

    /**
     * The headers an IPTV player sends for one of these channels, and therefore the only ones this app
     * sends: the playlist's own `User-Agent`, and the playlist's own `#EXTHTTP` cookie when it declares
     * one. No `Origin`, no `Referer` - nothing about this app's origin reaches Jio's CDN.
     *
     * The cookie is sent as a plain `Cookie` header and never as a signed query parameter: the CDN URL
     * already carries the token the redirector minted for it.
     */
    fun mediaHeaders(entry: Entry): Map<String, String> = linkedMapOf<String, String>().apply {
        put("User-Agent", entry.userAgent?.takeIf { it.isNotBlank() } ?: UA_OVERRIDE)
        entry.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }

    /** The playlist's own `#EXTHTTP` cookie, sent only to the playlist host as a last attempt. */
    fun cookieHeaders(entry: Entry): Map<String, String> =
        entry.cookie?.takeIf { it.isNotBlank() }?.let { mapOf("Cookie" to it) } ?: emptyMap()

    /** A real manifest, not an HTML error page or a login wall that happens to return 200. */
    fun isManifest(type: String, body: String): Boolean {
        val head = body.take(2048)
        return when (type) {
            "hls" -> head.trimStart().startsWith("#EXTM3U")
            "mp4" -> false
            else -> head.contains("<MPD", true) || head.contains("urn:mpeg:dash", true)
        }
    }

    /**
     * `cenc:default_KID` values advertised by an MPD, normalised to the base64url form ClearKey uses.
     * A manifest may carry several; an empty list means the manifest declares no content key at all.
     */
    fun defaultKids(mpd: String): Set<String> {
        val out = LinkedHashSet<String>()
        Regex("""cenc:default_KID\s*=\s*["'](?<kid>[0-9a-fA-F-]{32,36})["']""").findAll(mpd).forEach { match ->
            val hex = match.groupValues[1].replace("-", "")
            if (hex.length == 32) {
                val bytes = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                out.add(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
            }
        }
        return out
    }

    /**
     * The key endpoint this playlist advertises for a channel, if it is an HTTPS URL on the playlist's
     * own host. It returns the ClearKey document itself over GET, so it is a key source and not a
     * Widevine licence endpoint.
     */
    fun licenseUrl(entry: Entry): String? {
        val raw = entry.licenseKey?.trim().orEmpty()
        if (!raw.startsWith("https://", true)) return null
        return try {
            val uri = URI(raw)
            raw.takeIf {
                uri.scheme.equals("https", true) && uri.userInfo == null && uri.host != null &&
                    uri.host.equals(HOST, true) && uri.fragment == null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse the provider's ClearKey document: `{"keys":[{"kty":"oct","k":"...","kid":"..."}]}`.
     * Plain `kid:key` pairs and `{"keys":{"kid":"key"}}` are accepted too. Values must look like a
     * 16-byte key/key id (32 hex digits or base64url); anything else is ignored rather than guessed.
     */
    fun parseKeys(body: String): Map<String, String> {
        if (body.isBlank() || body.length > MAX_KEY_BYTES) return emptyMap()
        val out = LinkedHashMap<String, String>()
        fun normalised(value: String?): String? {
            val trimmed = value.orEmpty().trim()
            return trimmed.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) || it.matches(Regex("[A-Za-z0-9_-]{16,88}")) }
        }
        fun pair(kid: String?, key: String?) {
            val id = normalised(kid) ?: return
            val k = normalised(key) ?: return
            out[id] = k
        }
        val parsed = try { JSONObject(body) } catch (_: Exception) { null }
        if (parsed != null) {
            parsed.optJSONArray("keys")?.let { array ->
                for (index in 0 until minOf(array.length(), 8)) {
                    val item = array.optJSONObject(index) ?: continue
                    pair(item.optString("kid", item.optString("keyId", "")), item.optString("k", item.optString("key", "")))
                }
            }
            parsed.optJSONObject("keys")?.let { objectKeys ->
                objectKeys.keys().forEach { kid -> pair(kid, objectKeys.optString(kid)) }
            }
            if (out.isEmpty()) pair(parsed.optString("kid", ""), parsed.optString("k", ""))
            return out
        }
        body.trim().split(Regex("[,;\\s]+")).forEach { token ->
            val key = token.substringAfter(':', "").trim()
            if (key.isNotEmpty()) pair(token.substringBefore(':'), key)
        }
        return out
    }

    /** Convert the playlist's key material into the app's ClearKey JSON shape. */
    fun buildDrm(entry: Entry, keys: Map<String, String> = emptyMap()): JSONObject? {
        val pairs = LinkedHashMap<String, String>()
        // Inline `keyid:key` (the SunNxt variant of this playlist).
        entry.licenseKey?.takeUnless { it.startsWith("http", true) }?.let { inline ->
            inline.split(",").map { it.trim() }.filter { it.contains(':') }.forEach {
                val kid = it.substringBefore(':').trim()
                val key = it.substringAfter(':').trim()
                if (kid.isNotEmpty() && key.isNotEmpty()) pairs[kid] = key
            }
        }
        keys.forEach { (kid, key) -> pairs[kid] = key }
        val keyUrl = licenseUrl(entry)
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

    /** Group entries by category for the UI. */
    fun categories(entries: List<Entry>): List<Pair<String, List<Entry>>> {
        val map = LinkedHashMap<String, ArrayList<Entry>>()
        entries.forEach { e ->
            val cat = e.category.ifBlank { "Other" }
            val bucket = map.getOrPut(cat) { ArrayList() }
            bucket.add(e)
        }
        return map.map { it.key to it.value }
    }
}
