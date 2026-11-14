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
        val root = raw(body) as? JSONObject ?: return null
        val js = root.optJSONObject("js") ?: root
        val text = js.optString("error").ifBlank { js.optString("msg") }
            .ifBlank { root.optString("error") }
            .trim()
        return text.takeIf { it.isNotEmpty() }
    }

    /** `get_all_channels`: the `js.data` array of `{id, name, cmd, tv_genre_id, logo}`. */
    fun channels(body: String): List<StalkerChannel> {
        val js = envelope(body) ?: return emptyList()
        val data = js.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<StalkerChannel>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val cmd = item.optString("cmd").trim()
            val name = item.optString("name").ifBlank { item.optString("title") }.trim()
            val id = item.optString("id").ifBlank { item.optString("ch_id") }.trim()
            // A row without a name or without a handle cannot be shown or played.
            if (cmd.isEmpty() || name.isEmpty()) continue
            out.add(StalkerChannel(id.ifEmpty { name }, name, cmd,
                item.optString("tv_genre_id").trim(), item.optString("logo").trim()))
        }
        return out
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
