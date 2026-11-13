package com.aktv.app

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/** One channel as the portal advertises it. [cmd] is the handle the portal trades for a playable URL. */
internal data class StalkerChannel(
    val id: String,
    val name: String,
    val cmd: String,
    val genre: String = "",
    val logo: String = ""
)

/**
 * The Stalker / ministra portal wire format, kept free of Android and of the network so the shapes a
 * portal answers with can be regression tested. Everything arrives as `{"js": …}` JSON when the request
 * carries `JsHttpRequest=1-xml`; a few portals still wrap it in a JSONP comment, which is why parsing
 * starts at the first brace or bracket instead of at position zero.
 */
internal object StalkerProtocol {

    /** The MAG set-top box identity portals expect; a browser User-Agent is refused by most of them. */
    const val MAG_USER_AGENT =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2110 Safari/533.3"
    const val DEVICE_USER_AGENT = "Model: MAG254; Link: WiFi"
    private const val MAX_PORTAL_CHANNELS = 35_000

    private fun raw(body: String): Any? {
        val text = body.trim().removePrefix("/**/").trim()
        val start = listOf(text.indexOf('{'), text.indexOf('[')).filter { it >= 0 }.minOrNull() ?: return null
        return runCatching { JSONTokener(text.substring(start)).nextValue() }.getOrNull()
    }

    private fun envelope(body: String): JSONObject? {
        val root = raw(body) as? JSONObject ?: return null
        return root.optJSONObject("js") ?: root
    }

    /** The session token a handshake issues. */
    fun token(body: String): String? =
        envelope(body)?.optString("token")?.trim()?.takeIf { it.isNotEmpty() }

    /** True when the portal says the token is gone, so the caller has to handshake again. */
    fun expired(body: String): Boolean {
        if (body.length > 64 * 1024) return false
        val root = raw(body) as? JSONObject ?: return false
        val js = root.optJSONObject("js") ?: root
        when (val flag = js.opt("not_valid")) {
            is Boolean -> if (flag) return true
            is Number -> if (flag.toInt() != 0) return true
            else -> Unit
        }
        val text = (js.optString("error") + " " + js.optString("msg") + " " + root.optString("error")).lowercase()
        return "authorization failed" in text || "not valid" in text || ("token" in text && "expire" in text)
    }

    /** The portal's own refusal text, when it sent one. */
    fun error(body: String): String? {
        if (body.length > 256 * 1024) return null
        val root = raw(body) as? JSONObject ?: return null
        val js = root.optJSONObject("js") ?: root
        val text = js.optString("error").ifBlank { js.optString("msg") }
            .ifBlank { root.optString("error") }
            .trim()
        return text.takeIf { it.isNotEmpty() }
    }

    /**
     * `get_all_channels` / `get_ordered_list`: streams the `"data":[...]` array object-by-object instead
     * of building a monolithic `JSONObject` DOM for 15–20 MB portal responses, avoiding OOM spikes on TVs.
     */
    fun channels(body: String): List<StalkerChannel> {
        val text = body.trim().removePrefix("/**/").trim()
        if (text.isEmpty()) return emptyList()

        // Fast streaming scan of the `"data"` array when present (typical `{"js":{"total_items":...,"data":[...]}}`).
        val dataArrayStart = findDataArrayStart(text)
        if (dataArrayStart >= 0) {
            val out = ArrayList<StalkerChannel>(1024)
            scanArrayObjects(text, dataArrayStart) { item ->
                if (out.size >= MAX_PORTAL_CHANNELS) return@scanArrayObjects
                parseChannelObject(item)?.let(out::add)
            }
            return out
        }

        // Fallback for non-standard shapes (`{"js":[...]}` or bare `[...]`).
        val array = when (val root = raw(body)) {
            is JSONArray -> root
            is JSONObject -> root.optJSONObject("js")?.optJSONArray("data")
                ?: root.optJSONArray("js")
                ?: root.optJSONArray("data")
            else -> null
        } ?: return emptyList()
        val out = ArrayList<StalkerChannel>(array.length().coerceAtMost(MAX_PORTAL_CHANNELS))
        for (i in 0 until array.length()) {
            if (out.size >= MAX_PORTAL_CHANNELS) break
            val item = array.optJSONObject(i) ?: continue
            parseChannelObject(item)?.let(out::add)
        }
        return out
    }

    private fun parseChannelObject(item: JSONObject): StalkerChannel? {
        val cmd = item.optString("cmd").trim()
        val name = item.optString("name").ifBlank { item.optString("title") }.trim()
        val id = item.optString("id").ifBlank { item.optString("ch_id") }.trim()
        if (cmd.isEmpty() || name.isEmpty()) return null
        return StalkerChannel(
            id = id.ifEmpty { name },
            name = name,
            cmd = cmd,
            genre = item.optString("tv_genre_id").trim(),
            logo = item.optString("logo").trim()
        )
    }

    private fun findDataArrayStart(text: String): Int {
        var searchFrom = 0
        while (true) {
            val keyIdx = text.indexOf("\"data\"", searchFrom)
            if (keyIdx < 0) return -1
            var i = keyIdx + 6
            while (i < text.length && text[i].isWhitespace()) i++
            if (i < text.length && text[i] == ':') {
                i++
                while (i < text.length && text[i].isWhitespace()) i++
                if (i < text.length && text[i] == '[') return i
            }
            searchFrom = keyIdx + 6
        }
    }

    private inline fun scanArrayObjects(text: String, arrayOpenIdx: Int, onObject: (JSONObject) -> Unit) {
        val sb = StringBuilder(512)
        var depth = 0
        var inString = false
        var escape = false
        var i = arrayOpenIdx + 1
        val len = text.length
        while (i < len) {
            val c = text[i]
            if (depth == 0) {
                if (inString) {
                    if (escape) escape = false
                    else if (c == '\\') escape = true
                    else if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> {
                            depth = 1
                            sb.setLength(0)
                            sb.append('{')
                        }
                        ']' -> return
                    }
                }
            } else {
                sb.append(c)
                if (inString) {
                    if (escape) escape = false
                    else if (c == '\\') escape = true
                    else if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                runCatching { JSONObject(sb.toString()) }.getOrNull()?.let(onObject)
                                sb.setLength(0)
                            }
                        }
                    }
                }
                if (sb.length > 256 * 1024) {
                    depth = 0
                    sb.setLength(0)
                }
            }
            i++
        }
    }

    /** `get_genres`: `[{id, title}]`, or the same array under `js`. */
    fun genres(body: String): Map<String, String> {
        val array = when (val root = raw(body)) {
            is JSONArray -> root
            is JSONObject -> root.optJSONObject("js")?.optJSONArray("data")
                ?: root.optJSONArray("js")
                ?: root.optJSONArray("data")
            else -> null
        } ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val title = item.optString("title").trim()
            if (id.isNotEmpty() && title.isNotEmpty()) out[id] = title
        }
        return out
    }

    /** `create_link`: the raw `js.cmd` handle, still carrying the player's `ffmpeg ` prefix. */
    fun link(body: String): String? {
        val js = envelope(body) ?: return null
        return js.optString("cmd").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The URL inside a portal command. Portals answer `ffmpeg http://host/…/index.m3u8`, and some append
     * Kodi-style `|User-Agent=…` parameters that Media3 cannot use, so both are cut away here.
     */
    fun streamUrl(cmd: String): String? {
        val clean = cmd.trim().removePrefix("ffmpeg").trim().substringBefore('|').trim()
        return clean.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("rtsp://", true)
        }
    }

    fun saveCache(dir: File, cacheKey: String, channels: List<StalkerChannel>, genres: Map<String, String>) {
        runCatching {
            dir.mkdirs()
            val genresFile = File(dir, "genres-$cacheKey.json")
            val genresJson = JSONObject()
            genres.forEach { (k, v) -> genresJson.put(k, v) }
            genresFile.writeText(genresJson.toString(), Charsets.UTF_8)

            val tmp = File(dir, "portal-$cacheKey.tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for (ch in channels) {
                    w.write(
                        JSONObject()
                            .put("i", ch.id)
                            .put("n", ch.name)
                            .put("c", ch.cmd)
                            .put("g", ch.genre)
                            .put("l", ch.logo)
                            .toString()
                    )
                    w.newLine()
                }
            }
            tmp.renameTo(File(dir, "portal-$cacheKey.jsonl"))
        }
    }

    fun loadCache(dir: File, cacheKey: String): Pair<List<StalkerChannel>, Map<String, String>> = runCatching {
        val file = File(dir, "portal-$cacheKey.jsonl")
        if (!file.exists()) return emptyList<StalkerChannel>() to emptyMap()
        val genres = LinkedHashMap<String, String>()
        val genresFile = File(dir, "genres-$cacheKey.json")
        if (genresFile.exists()) {
            runCatching {
                val obj = JSONObject(genresFile.readText(Charsets.UTF_8))
                obj.keys().forEach { k -> genres[k] = obj.optString(k) }
            }
        }
        val out = ArrayList<StalkerChannel>(1024)
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank() && out.size < MAX_PORTAL_CHANNELS) {
                    val o = JSONObject(line)
                    val id = o.optString("i")
                    val name = o.optString("n")
                    val cmd = o.optString("c")
                    if (id.isNotEmpty() && name.isNotEmpty() && cmd.isNotEmpty()) {
                        out += StalkerChannel(id, name, cmd, o.optString("g"), o.optString("l"))
                    }
                }
            }
        }
        out to genres
    }.getOrDefault(emptyList<StalkerChannel>() to emptyMap())
}
