package com.aktv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads a player page with HTTP and returns the media addresses it names.
 *
 * There is no browser engine in this app, and none is wanted: on a television that engine costs the
 * process more than the whole player. What a player page does with scripts can still be read as text,
 * because the address it will play is in the page it serves - as a JSON field, a `<source>`/`<iframe>`
 * target, or a bare `.m3u8`/`.mp4`. That is all this reads.
 *
 * The crawl is bounded in every direction: pages, bytes, depth and time. Each address is returned with
 * the page that named it, because the CDN usually refuses a manifest that is asked for from anywhere
 * else - the page is the `Referer` the native player then sends.
 */
internal object PageCrawl {

    /** One media address, and the page that named it. */
    internal class Hit(val page: String, val url: String, val format: VodFormat) {
        override fun toString() = "Hit($format)"
    }

    const val MAX_PAGE_BYTES = 1_500_000
    const val MAX_PAGES = 8
    const val MAX_HITS = 12
    const val MAX_DEPTH = 3

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS).readTimeout(9, TimeUnit.SECONDS).callTimeout(11, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(false).build()

    /** Injectable so a fixture page can be crawled without a socket. */
    internal var fetcher: (String, String?) -> String? = ::http

    /** Puts the real transport back; tests that inject fixtures call this when they finish. */
    internal fun restoreTransport() { fetcher = ::http }

    /**
     * Media addresses reachable from [start], HLS first, each with the page it was found on.
     *
     * Never throws and never keeps a page body: a page that does not answer simply contributes nothing.
     */
    fun crawl(
        start: String,
        referer: String? = null,
        budgetMs: Long = 9_000L,
        maxPages: Int = MAX_PAGES,
        includeDash: Boolean = false
    ): List<Hit> {
        if (!start.startsWith("https://", true)) return emptyList()
        val deadline = System.currentTimeMillis() + budgetMs.coerceIn(1_000L, 30_000L)
        val queue = ArrayDeque<String>()
        val parent = HashMap<String, String?>()
        val seen = HashSet<String>()
        val hits = LinkedHashMap<String, Hit>()
        queue.add(start); parent[start] = referer
        var pages = 0
        while (queue.isNotEmpty() && pages < maxPages && System.currentTimeMillis() < deadline && hits.size < MAX_HITS) {
            val page = queue.removeFirst()
            if (!seen.add(page)) continue
            pages++
            val body = try { fetcher(page, parent[page]) } catch (_: Exception) { null } ?: continue
            // A playlist is the media itself: a server that answers a page request with one has named it.
            if (body.startsWith("#EXTM3U", true)) {
                hits.putIfAbsent(page, Hit(page, page, VodFormat.HLS))
                continue
            }
            for (url in media(body, page)) {
                val format = format(url) ?: continue
                if (format == VodFormat.DASH && !includeDash) continue
                if (hits.size >= MAX_HITS) break
                hits.putIfAbsent(url, Hit(page, url, format))
            }
            if (pages + queue.size < maxPages) {
                for (frame in frames(body, page)) {
                    if (frame !in seen) { parent[frame] = page; queue.add(frame) }
                }
            }
        }
        return hits.values.sortedWith(compareBy({ if (it.format == VodFormat.HLS) 0 else 1 }, { it.page.length }))
    }

    /** Media addresses a page names, absolute and https, in the order the page names them. */
    fun media(body: String, page: String): List<String> {
        val text = unescape(body)
        val found = LinkedHashSet<String>()
        BARE.findAll(text).forEach { found += it.value }
        KEYED.findAll(text).forEach { match -> found += match.groupValues[1] }
        return found.mapNotNull { absolute(it, page) }.filter { format(it) != null }.distinct().take(MAX_HITS)
    }

    /** Pages a page embeds: iframes, and the players that publish themselves as a `<source>`. */
    fun frames(body: String, page: String): List<String> {
        val text = unescape(body)
        return (IFRAME.findAll(text) + SOURCE.findAll(text))
            .map { it.groupValues[1] }
            .mapNotNull { absolute(it, page) }
            .filter { format(it) == null }
            .distinct().take(6).toList()
    }

    /** HLS and progressive files only: a DASH manifest from these hosts is Widevine, which this player does not open. */
    fun format(url: String): VodFormat? = when (extension(url)) {
        "m3u8", "m3u" -> VodFormat.HLS
        "mp4", "m4v" -> VodFormat.MP4
        "mpd" -> VodFormat.DASH
        else -> null
    }

    private fun extension(url: String): String =
        url.substringBefore('#').substringBefore('?').substringAfterLast('/', "")
            .substringAfterLast('.', "").lowercase(Locale.US)

    private fun absolute(value: String, page: String): String? = try {
        val raw = value.trim().trim('"', '\'', ',', ';', ')')
        if (raw.isEmpty() || raw.length > 3_000) return null
        if (raw.startsWith("data:", true) || raw.startsWith("blob:", true) || raw.startsWith("javascript:", true)) return null
        val uri = URI(page).resolve(raw.replace(" ", "%20"))
        uri.toASCIIString().takeIf {
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null
        }
    } catch (_: Exception) {
        null
    }

    /** A page is read as a browser sends it: the address that embedded it is the Referer. */
    private fun http(url: String, referer: String?): String? = try {
        val page = referer ?: url
        val origin = origin(page)
        val request = Request.Builder().url(url)
            .header("User-Agent", TgHttp.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/json,*/*")
            .header("Accept-Language", "en-IN,en;q=0.9")
            .apply { origin?.let { header("Referer", "$it/"); header("Origin", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_PAGE_BYTES) return null
            val input = body.byteStream()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_PAGE_BYTES) break
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    } catch (_: Exception) {
        null
    }

    /** `scheme://host[:port]` of a page, which is what an `Origin` header may be. */
    fun origin(page: String): String? = try {
        val uri = URI(page)
        uri.host?.takeIf { uri.scheme.equals("https", true) }?.let { host -> "https://$host" }
    } catch (_: Exception) {
        null
    }

    private fun unescape(body: String): String = body
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&")
        .replace("&#x2F;", "/", ignoreCase = true)
        .replace("&#47;", "/")

    /** A bare address, absolute or protocol-relative, as pages that keep one in text publish it. */
    private val BARE = Regex("""(?i)(?:https?:)?//[^\s"'<>\\]{4,2000}?\.(?:m3u8|m3u|mp4|m4v|mpd)(?:\?[^\s"'<>\\]*)?""")

    /** The field a player keeps its address in, when it is not a bare address in the page. */
    private val KEYED = Regex(
        """(?i)"(?:file|url|src|source|hls|playlist|link|stream|video)"\s*:\s*"((?:https?:)?//[^"\s]{4,2000})""""
    )
    private val IFRAME = Regex("""(?i)<iframe[^>]*?\b(?:data-)?src\s*=\s*["']([^"']{4,2048})["']""")
    private val SOURCE = Regex("""(?i)<source[^>]*?\bsrc\s*=\s*["']([^"']{4,2048})["']""")
}
