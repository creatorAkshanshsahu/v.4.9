package com.aktv.app

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap

/** Only metadata supplied by TG for this particular source. Never borrow another server's credentials. */
data class TgStream(
    val label: String,
    val url: String,
    val type: String,
    val drm: JSONObject? = null,
    val headers: Map<String, String> = emptyMap(),
    val cookie: String = "",
    // Non-secret browser origin stays consistent across redirects; source credentials do not.
    val requestOrigin: String = TgResolver.SITE,
    val omitReferrer: Boolean = false
)

/** TG's JSON / TanStack wire formats. Kept Android-free so protocol changes can be regression tested. */
object TgProtocol {
    const val MAX_BODY = 4 * 1024 * 1024
    private val urlKeys = listOf("playable", "url", "src", "stream", "streamUrl", "manifestUrl", "manifest", "mpd", "m3u8", "hls", "dash", "link")
    private val children = listOf("result", "data", "config", "playback", "primary", "urls", "sources", "streams", "servers", "backups")

    fun streams(body: ByteArray, contentType: String, page: String, defaultType: String = "dash", expectedId: String? = null): List<TgStream> {
        require(body.size <= MAX_BODY) { "Player response too large" }
        val decoder = Decoder()
        val out = ArrayList<TgStream>()
        for (record in records(body, contentType)) {
            val root = decoder.decode(JSONTokener(record).nextValue())
            val envelope = (root as? JSONObject)?.let { it.optJSONObject("result") ?: it.optJSONObject("data") ?: it }
            val responseId = envelope?.let { it.opt("channelId") ?: it.opt("channel_id") ?: it.opt("id") }
            if (expectedId != null && responseId != null && responseId != JSONObject.NULL && responseId.toString() != expectedId) continue
            collect(root, page, defaultType, Metadata(), out, Collections.newSetFromMap(IdentityHashMap()), 0)
        }
        return out.distinctBy { listOf(it.url, it.type, it.cookie, it.headers.toString(), it.drm?.toString()) }
    }

    /** A binary framed response is not newline-delimited JSON. Lengths are big-endian, including stream id. */
    internal fun records(body: ByteArray, contentType: String): List<String> {
        if (contentType.substringBefore(';').trim().equals("application/x-tss-framed", true)) {
            val version = Regex("(?:^|;)\\s*v=(\\d+)").find(contentType)?.groupValues?.get(1)
            require(version == null || version == "1") { "Unsupported player response version" }
            val input = ByteBuffer.wrap(body)
            val out = ArrayList<String>()
            while (input.hasRemaining()) {
                require(input.remaining() >= 9) { "Truncated player response" }
                val kind = input.get().toInt() and 255
                val stream = input.int
                val length = input.int
                require(length in 0..MAX_BODY && length <= input.remaining()) { "Invalid player frame length" }
                val data = ByteArray(length); input.get(data)
                if (kind == 0 && stream == 0) out.add(data.toString(Charsets.UTF_8))
                else if (kind == 3) throw IOException("Player response stream failed")
            }
            return out
        }
        val text = body.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
        // A plain JSON response can be pretty-printed. Try it as a whole before treating it as NDJSON.
        try {
            val tokener = JSONTokener(text)
            tokener.nextValue()
            if (tokener.nextClean() == '\u0000') return listOf(text)
        } catch (_: Exception) { }
        return text.lineSequence().map { it.trim() }.filter { it.startsWith('{') || it.startsWith('[') }.toList()
    }

    /** Seroval's data-only subset, including wrappers and shared references used by current TanStack builds. */
    private class Decoder {
        private val refs = HashMap<Int, Any>()
        fun decode(value: Any?, depth: Int = 0): Any? {
            require(depth <= 48) { "Player response nested too deeply" }
            if (value is JSONArray) return JSONArray().apply {
                for (i in 0 until value.length()) put(decode(value.opt(i), depth + 1) ?: JSONObject.NULL)
            }
            if (value !is JSONObject) return value
            val tag = value.opt("t")
            if (tag is JSONObject && value.has("f")) return decode(tag, depth + 1)
            if (tag !is Number) return JSONObject().apply {
                value.keys().forEach { key -> put(key, decode(value.opt(key), depth + 1) ?: JSONObject.NULL) }
            }
            fun child(v: Any?) = decode(v, depth + 1) ?: JSONObject.NULL
            fun remember(v: Any): Any { if (value.has("i")) refs[value.getInt("i")] = v; return v }
            return when (tag.toInt()) {
                0, 3 -> value.opt("s")
                1 -> unescape(value.optString("s"))
                2 -> when (value.optInt("s")) { 2 -> true; 3 -> false; else -> JSONObject.NULL }
                4 -> refs[value.optInt("i")] ?: JSONObject.NULL
                9 -> JSONArray().also { a ->
                    remember(a)
                    value.optJSONArray("a")?.let { values -> for (i in 0 until values.length()) a.put(child(values.opt(i))) }
                }
                10, 11 -> JSONObject().also { o ->
                    remember(o)
                    val props = value.optJSONObject("p") ?: return@also
                    val keys = props.optJSONArray("k") ?: return@also
                    val vals = props.optJSONArray("v") ?: return@also
                    for (i in 0 until keys.length()) {
                        val key = keys.opt(i).let { if (it is JSONObject) child(it).toString() else unescape(it.toString()) }
                        o.put(key, child(vals.opt(i)))
                    }
                }
                // A later NDJSON record can fulfil a deferred result. Its value is still useful on its own.
                23 -> value.optJSONArray("a")?.opt(1)?.let { child(it) }
                12 -> if (value.optBoolean("s", false) || value.optInt("s", 0) == 1) value.opt("f")?.let { child(it) } else null
                21 -> value.opt("f")?.let { child(it) }
                else -> null // No evaluation of executable/plugin nodes from a remote response.
            }
        }
    }

    private fun unescape(value: String) = Regex("""\\(?:\\|"|n|r|b|t|f|u2028|u2029|x3C)""").replace(value) { match ->
        when (match.value.substring(1)) {
            "\\" -> "\\"; "\"" -> "\""; "n" -> "\n"; "r" -> "\r"; "b" -> "\b"; "t" -> "\t"; "f" -> "\u000c"
            "u2028" -> "\u2028"; "u2029" -> "\u2029"; "x3C" -> "<"; else -> match.value
        }
    }

    private data class Metadata(
        val drm: JSONObject? = null, val headers: Map<String, String> = emptyMap(),
        val cookie: String = "", val label: String = "", val type: String = ""
    )

    private fun collect(node: Any?, page: String, defaultType: String, parent: Metadata, out: MutableList<TgStream>, seen: MutableSet<Any>, depth: Int) {
        if (depth > 16 || out.size >= 32 || node == null || node == JSONObject.NULL) return
        if ((node is JSONObject || node is JSONArray) && !seen.add(node)) return
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), page, defaultType, parent, out, seen, depth + 1)
            is String -> mediaUrl(node, page)?.let { u ->
                out.add(TgStream(parent.label.ifBlank { "Server ${out.size + 1}" }, u,
                    streamType(parent.type, u, defaultType), parent.drm, parent.headers, parent.cookie))
            }
            is JSONObject -> {
                if (text(node, "type").lowercase() in setOf("iframe", "embed", "website")) return
                val ownDrm = drmOf(node)
                val headers = LinkedHashMap(parent.headers)
                node.optJSONObject("headers")?.let { h -> h.keys().forEach { k ->
                    val v = h.opt(k) as? String
                    if (v != null && k.none { it == '\r' || it == '\n' } && v.none { it == '\r' || it == '\n' }) headers[k] = v
                } }
                for ((field, header) in listOf("userAgent" to "User-Agent", "referer" to "Referer", "referrer" to "Referer", "origin" to "Origin")) {
                    text(node, field).takeIf { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') }?.let { headers[header] = it }
                }
                val meta = Metadata(
                    if (node.has("drm") || ownDrm != null) ownDrm else parent.drm,
                    headers,
                    if (node.has("cookie")) text(node, "cookie") else parent.cookie,
                    text(node, "label").ifBlank { parent.label },
                    text(node, "mimeType").ifBlank { text(node, "type").ifBlank { parent.type } }
                )
                var ownsSource = node.optJSONArray("urls") != null || node.optJSONArray("sources") != null
                for (key in urlKeys) {
                    val u = node.opt(key) as? String ?: continue
                    if (mediaUrl(u, page) != null) {
                        ownsSource = true
                        collect(u, page, defaultType, meta, out, seen, depth + 1)
                        break
                    }
                }
                // The deployed website groups all primary URLs under Server 1 and renumbers its
                // advertised alternatives (an upstream "Server 3" can be the website's Server 2).
                val siteUrls = node.optJSONArray("urls")
                val siteServers = siteUrls != null && node.has("backups")
                // Only traverse playback containers, not logo/EPG/watch links or unrelated channel objects.
                for (key in children) {
                    val child = node.opt(key) ?: continue
                    val inherited = if (ownsSource && (key == "backups" || key == "servers")) Metadata() else meta
                    if (siteServers && key == "backups" && child is JSONArray) {
                        val firstNumber = if ((siteUrls?.length() ?: 0) > 0) 2 else 1
                        for (i in 0 until child.length()) {
                            val first = out.size
                            collect(child.opt(i), page, defaultType, inherited, out, seen, depth + 1)
                            for (j in first until out.size) out[j] = out[j].copy(label = "Server ${firstNumber + i}")
                        }
                    } else {
                        val first = out.size
                        collect(child, page, defaultType, inherited, out, seen, depth + 1)
                        if (siteServers && key == "urls") {
                            for (j in first until out.size) out[j] = out[j].copy(label = "Server 1")
                        }
                    }
                }
                // Some deployments use server1/server2 or source1/source2 rather than an array.
                node.keys().forEach { k -> if (k.matches(Regex("(?:server|source|url)\\d+"))) collect(node.opt(k), page, defaultType, meta, out, seen, depth + 1) }
            }
        }
    }

    private fun mediaUrl(raw: String, page: String): String? = try {
        val uri = URI(page).resolve(raw.trim().replace("&amp;", "&"))
        val path = uri.path.orEmpty()
        if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null ||
            path.matches(Regex(".*/(?:watch|embed)(?:/.*)?")) || path.endsWith(".html") || path.endsWith(".js") ||
            path.matches(Regex(".*\\.(?:png|jpg|jpeg|svg|webp)", RegexOption.IGNORE_CASE)) ||
            !(raw.startsWith("http") || raw.startsWith('/') || raw.startsWith("./") || raw.startsWith("../"))) null else uri.toASCIIString()
    } catch (_: Exception) { null }

    private fun streamType(type: String, url: String, default: String): String = when (type.lowercase()) {
        "dash", "application/dash+xml", "mpd" -> "dash"
        "hls", "application/x-mpegurl", "application/vnd.apple.mpegurl", "m3u8" -> "hls"
        "video/mp4", "mp4" -> "mp4"
        else -> when (URI(url).path.substringAfterLast('.').lowercase()) { "mpd" -> "dash"; "m3u8" -> "hls"; "mp4" -> "mp4"; else -> default }
    }

    private fun drmOf(node: JSONObject): JSONObject? {
        val nested = node.optJSONObject("drm")
        val source = nested ?: node
        val declared = text(source, "type")
        if (nested != null && declared.isNotBlank() && !declared.contains("clear", true)) return nested
        val keys = ClearKey.pairs(source)
        return if (keys.isNotEmpty()) JSONObject().put("type", "clearkey").put("keys", JSONArray().apply {
            keys.forEach { (kid, key) -> put(JSONObject().put("kid", kid).put("k", key)) }
        }) else nested
    }

    private fun text(o: JSONObject, key: String) = (o.opt(key) as? String).orEmpty()
}

/** Normalise the site's ClearKey formats, retaining every audio/video key, without saving them to disk. */
object ClearKey {
    private fun bytes(value: String): ByteArray? {
        val hex = value.trim().removePrefix("0x").removePrefix("0X").replace("-", "").replace(" ", "")
        if (hex.matches(Regex("[0-9a-fA-F]{32}"))) return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return try { Base64.getUrlDecoder().decode(value.trim().replace('+', '-').replace('/', '_')).takeIf { it.size == 16 } } catch (_: Exception) { null }
    }
    private fun encoded(value: String) = bytes(value)?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun pairs(drm: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun add(kid: String, key: String) {
            val id = encoded(kid) ?: return
            val k = encoded(key) ?: return
            out[id] = k
        }
        fun parts(value: Any?): List<String> = when (value) {
            is JSONArray -> (0 until value.length()).map { value.optString(it) }
            is String -> value.trim().split(Regex("[,;\\s]+"))
            else -> emptyList()
        }
        val ids = parts(drm.opt("keyId") ?: drm.opt("kid") ?: drm.opt("key_id"))
        val values = parts(drm.opt("key") ?: drm.opt("k"))
        ids.zip(values).forEach { (id, key) -> add(id, key) }
        for (name in listOf("clearKeys", "clearkeys")) drm.optJSONObject(name)?.let { o -> o.keys().forEach { add(it, o.optString(it)) } }
        drm.optJSONArray("keys")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { k ->
            add(k.optString("keyId", k.optString("kid")), k.optString("key", k.optString("k")))
        } }
        for (name in listOf("license_key", "licenseKey", "license")) (drm.opt(name) as? String)?.let { s ->
            if (!s.startsWith("http")) s.split(',').forEach { if (it.contains(':')) add(it.substringBefore(':'), it.substringAfter(':')) }
        }
        return out
    }

    fun license(drm: JSONObject): ByteArray? {
        val type = drm.optString("type", "")
        if (type.isNotBlank() && !type.contains("clear", true)) return null
        val pairs = pairs(drm)
        if (pairs.isEmpty()) return null
        val keys = JSONArray()
        pairs.forEach { (id, key) -> keys.put(JSONObject().put("kty", "oct").put("kid", id).put("k", key)) }
        return JSONObject().put("keys", keys).put("type", "temporary").toString().toByteArray(Charsets.UTF_8)
    }
}
