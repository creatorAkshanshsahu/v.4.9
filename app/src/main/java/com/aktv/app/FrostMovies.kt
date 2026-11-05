package com.aktv.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Movie identity is independent of a short-lived URL or a browser embed. */
internal data class FrostTitle(val id: Int, val type: String, val season: Int = 0, val episode: Int = 0) {
    init {
        require(id > 0 && type in setOf("movie", "tv")) { "Invalid movie identity" }
        require(if (type == "tv") season > 0 && episode > 0 else season == 0 && episode == 0) { "Invalid episode identity" }
    }
    val isSeries get() = type == "tv"
    val progressKey get() = "vod_${type}_${id}_${season}_${episode}"
    val catalogKey get() = (if (isSeries) "tv" else "mv") + id
}

internal enum class FrostProvider(val id: String, val label: String) {
    FROST("tgflix", "TG FROST"), OUT("tgout", "TG OUT"),
    // theogpiratebot.online's own servers (Vidstuck, Vidfast, Vidlink…), read from each server's page and
    // played by this same native player: the addresses are media, not embeds.
    OG("og", "OG")
    // TG BOLT and TGVID are iframe players, not verified native source contracts.
}

internal enum class VodFormat(val mime: String) {
    DASH("application/dash+xml"), HLS("application/x-mpegURL"), MP4("video/mp4")
}

/** Avoid data-class toString leaking signed source URLs in exceptions/logs. */
internal class FrostSubtitle(val url: String, val label: String, val language: String?, val mime: String, val default: Boolean) {
    override fun toString() = "Subtitle($language, $mime)"
}

internal class FrostSource(
    val provider: FrostProvider, val identity: String, val url: String, val format: VodFormat,
    val quality: String, val language: String, val hevc: Boolean?, val headers: Map<String, String>,
    val subtitles: List<FrostSubtitle>, val credentialOrigin: String = url, val scraperName: String = ""
) {
    val label get() = listOf(provider.label, scraperName, quality, language, format.name).filter { it.isNotBlank() }.joinToString(" · ")
    override fun toString() = "FrostSource(${provider.name}, ${format.name}, $quality)"
}

internal class FrostResolveException(val status: Int? = null, val kind: String = "unavailable") : IOException(
    when {
        status == 429 -> "Movie source service is rate limited. Please wait before retrying."
        status == 401 || status == 403 -> "Movie source service denied access (HTTP $status)."
        status == 404 || kind == "empty" -> "No native sources are available for this title or episode."
        kind == "tls" -> "Secure connection failed. Check the device date and TLS/certificate support."
        kind == "timeout" -> "The movie source service did not respond in time."
        kind == "dns" -> "The movie source service could not be reached (DNS)."
        kind == "too-large" -> "Movie source response was too large."
        kind == "schema" -> "Movie source service returned an unsupported response."
        else -> "Movie source service is unavailable${status?.let { " (HTTP $it)" } ?: ""}."
    }
)

internal object FrostProtocol {
    const val SITE = "https://tgfrost.lovable.app"
    const val MAX_BYTES = 2 * 1024 * 1024

    fun endpoint(base: HttpUrl, provider: FrostProvider, title: FrostTitle): HttpUrl = base.newBuilder()
        .addPathSegments("api/sources").addQueryParameter("server", provider.id)
        .addQueryParameter("type", title.type).addQueryParameter("tmdb_id", title.id.toString()).apply {
            if (title.isSeries) { addQueryParameter("season", title.season.toString()); addQueryParameter("episode", title.episode.toString()) }
        }.build()

    /** HTTPS only, no browser pages, userinfo, local addresses or opaque/blob URL schemes. */
    fun mediaUrl(value: String?, base: String = SITE): String? = try {
        value?.takeIf { it.length in 1..16_384 && it.none { c -> c.isISOControl() } }?.let { raw ->
            val uri = URI(base).resolve(raw.trim())
            val host = uri.host.orEmpty().lowercase(Locale.US)
            uri.toASCIIString().takeIf {
                uri.scheme == "https" && uri.userInfo == null && uri.fragment == null && host.isNotEmpty() &&
                    !host.matches(Regex("[0-9.]+")) && !host.contains(':') &&
                    !Regex("(^|\\.)(localhost|local|internal|lan|home)$").containsMatchIn(host) &&
                    !uri.path.orEmpty().contains(Regex("(^|/)(embed|watch)(/|$)", RegexOption.IGNORE_CASE))
            }
        }
    } catch (_: Exception) { null }

    private fun text(value: Any?, max: Int = 60): String = (value as? String).orEmpty()
        .replace(Regex("[\\p{Cntrl}]+"), " ").trim().take(max)

    private fun format(type: String, url: String): VodFormat? = when (type.lowercase(Locale.US)) {
        "dash", "mpd", "application/dash+xml" -> VodFormat.DASH
        "hls", "m3u8", "application/x-mpegurl", "application/vnd.apple.mpegurl" -> VodFormat.HLS
        "mp4", "video/mp4" -> VodFormat.MP4
        "", "video" -> when (URI(url).path.substringAfterLast('.', "").lowercase(Locale.US)) {
            "mpd" -> VodFormat.DASH; "m3u8" -> VodFormat.HLS; "mp4" -> VodFormat.MP4; else -> null
        }
        else -> null
    }

    private fun headers(value: JSONObject?): Map<String, String> = buildMap {
        if (value == null) return@buildMap
        value.keys().asSequence().take(24).forEach { key ->
            val lower = key.lowercase(Locale.US)
            val raw = value.opt(key) as? String ?: return@forEach
            if ((lower in setOf("user-agent", "referer", "origin", "authorization", "cookie", "accept") ||
                    lower.matches(Regex("x-[a-z0-9-]{1,60}"))) && raw.length <= 4096 && raw.all { it == '\t' || it in ' '..'~' })
                put(lower, raw)
        }
    }

    private fun subtitles(array: JSONArray?): List<FrostSubtitle> = (0 until minOf(array?.length() ?: 0, 40)).mapNotNull { index ->
        val item = array?.optJSONObject(index) ?: return@mapNotNull null
        val kind = text(item.opt("kind")).lowercase(Locale.US)
        if (kind !in setOf("", "captions", "subtitles")) return@mapNotNull null
        val url = mediaUrl(item.opt("url") as? String ?: item.opt("file") as? String ?: item.opt("src") as? String)
            ?: return@mapNotNull null
        val type = text(item.opt("format") ?: item.opt("type")).lowercase(Locale.US)
        val ext = URI(url).path.substringAfterLast('.', "").lowercase(Locale.US)
        val mime = when {
            type in setOf("vtt", "webvtt", "text/vtt") || ext == "vtt" -> "text/vtt"
            type in setOf("srt", "application/x-subrip") || ext == "srt" -> "application/x-subrip"
            type in setOf("ass", "ssa", "text/x-ssa") || ext in setOf("ass", "ssa") -> "text/x-ssa"
            type in setOf("ttml", "application/ttml+xml") || ext == "ttml" -> "application/ttml+xml"
            // Observed Frost tracks contain only url/label/lang. This supplied subtitle service
            // returned text/vtt + a WEBVTT signature for both sampled movie and episode tracks.
            type.isBlank() && URI(url).host.equals("embed.reelsdownload.online", true) -> "text/vtt"
            else -> return@mapNotNull null // Do not guess formats for arbitrary unknown endpoints.
        }
        val language = text(item.opt("language") ?: item.opt("lang") ?: item.opt("srclang"), 32)
            .takeIf { it.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*")) }
        FrostSubtitle(url, text(item.opt("label") ?: item.opt("name")).ifBlank { language ?: "Subtitles" }, language, mime, item.optBoolean("default", false))
    }.distinctBy { it.url }

    fun parse(body: String, provider: FrostProvider): List<FrostSource> {
        if (body.length > MAX_BYTES) throw FrostResolveException(kind = "too-large")
        val root = try { JSONObject(body) } catch (_: Exception) { throw FrostResolveException(kind = "schema") }
        val array = root.optJSONArray("sources") ?: throw FrostResolveException(kind = "schema")
        val result = ArrayList<FrostSource>()
        for (index in 0 until minOf(array.length(), 80)) {
            val item = array.optJSONObject(index) ?: continue
            if (text(item.opt("kind")).equals("embed", true)) continue
            // No guessed licence URLs or conversion of an unrelated provider's DRM. Such sources need
            // a separately verified contract; the current Frost samples do not supply explicit DRM.
            if (listOf("drm", "licenseUrl", "license_url", "clearKeys").any { item.has(it) && !item.isNull(it) && item.opt(it) != false }) continue
            val primary = mediaUrl(item.opt("url") as? String) ?: continue
            val format = format(text(item.opt("type")), primary) ?: continue
            val quality = text(item.opt("quality"), 16).takeIf { it.matches(Regex("(?i)([0-9]{3,4}p?|4k|auto)")) }.orEmpty()
            val language = text(item.opt("language"), 40)
            val scraperName = text(item.opt("scraperName"), 40).takeIf {
                it.matches(Regex("[\\p{L}\\p{N} ._()-]{1,40}"))
            }.orEmpty()
            val hevc = item.opt("hevc") as? Boolean // Missing does NOT mean AVC. MPD codec data is authoritative.
            val tracks = subtitles(item.optJSONArray("tracks") ?: item.optJSONArray("subtitles"))
            val urls = arrayListOf(primary)
            item.optJSONArray("mirrors")?.let { mirrors ->
                for (i in 0 until minOf(mirrors.length(), 4)) mediaUrl(mirrors.opt(i) as? String)?.let(urls::add)
            }
            val identity = text(item.opt("id"), 160).ifBlank { "$index" }
            for ((mirror, url) in urls.distinct().withIndex()) {
                val key = "$provider|$identity|${format.name}|$quality|$language|$mirror"
                val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
                result.add(FrostSource(provider, digest, url, format, quality, language, hevc, headers(item.optJSONObject("headers")), tracks, primary, scraperName))
            }
            if (result.size >= 64) break
        }
        // Keep the website's within-format ordering; adaptive/relay paths precede direct MP4 URLs.
        val sources = result.distinctBy { "${it.url}|${it.format}|${it.language}" }
            .sortedBy { if (it.format == VodFormat.MP4) 1 else 0 }.take(64)
        if (sources.isEmpty()) throw FrostResolveException(kind = "empty")
        return sources
    }

    fun readBounded(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw IOException("Movie lookup cancelled")
            val count = input.read(buffer, 0, minOf(buffer.size, limit - out.size() + 1))
            if (count == -1) return out.toByteArray()
            out.write(buffer, 0, count)
            if (out.size() > limit) throw FrostResolveException(kind = "too-large")
        }
    }
}

internal class FrostResolver(
    private val base: HttpUrl = FrostProtocol.SITE.toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
) {
    /**
     * What the site's own player played for a title, remembered, so a second attempt is instant.
     */
    private data class Memo(val until: Long, val sources: List<FrostSource>)
    private val memo = HashMap<String, Memo>()
    private fun memoKey(t: FrostTitle, p: FrostProvider) = "${p.id}:${t.type}:${t.id}:${t.season}:${t.episode}"

    /**
     * The address of the page a viewer opens on the site, which is what the fallback runs when the app's own
     * request for this title comes back empty or with nothing this player can use.
     */
    /**
     * Runs the site's own player and takes the stream it plays. The site hops servers silently until one
     * answers, which is exactly the work this app used to leave to the viewer; doing it here means the first
     * attempt plays instead of the second one.
     */
    /**
     * Every address where this family of sites plays a title, best first:
     *
     * - `tgvid.lovable.app/embed/movie/{id}` - the documented embed player, no profile screen in front of it;
     * - `tgflix.lovable.app/play/{id}` - the route the viewer uses, behind "Who's Watching?";
     * - `tgfrost.lovable.app/watch/movie/{id}` - the host the app's own API has always used.
     *
     * The second element is the Referer to send; the third is null when the page must be loaded directly so a
     * profile screen can be passed and the title re-opened.
     */
    private fun sitePages(title: FrostTitle, provider: FrostProvider): List<Pair<String, String?>> {
        val tv = if (title.isSeries) "tv/${title.id}/${title.season}/${title.episode}" else "movie/${title.id}"
        val server = if (provider == FrostProvider.FROST) "" else "?server=${provider.id}"
        return buildList<Pair<String, String?>> {
            add("https://tgvid.lovable.app/embed/$tv$server" to "https://tgvid.lovable.app/")
            add("https://tgflix.lovable.app/play/${title.id}" to null)
            add("${FrostProtocol.SITE}/watch/$tv$server" to "${FrostProtocol.SITE}/")
            if (title.isSeries) add("https://tgflix.lovable.app/play/${title.id}?season=${title.season}&episode=${title.episode}" to null)
        }.distinctBy { it.first }
    }

    private fun sitePlayback(title: FrostTitle, provider: FrostProvider): FrostSource? {
        val context = MediaSniffer.appContext ?: return null
        var media: String? = null
        val deadline = System.currentTimeMillis() + 45_000
        for ((page, referer) in sitePages(title, provider)) {
            if (System.currentTimeMillis() > deadline) break
            media = try { MediaSniffer.find(context, page, referer) } catch (_: Exception) { null }
            if (media != null) break
        }
        val chosen = media ?: return null
        val format = when (chosen.substringBefore('?').substringAfterLast('.', "").lowercase(java.util.Locale.US)) {
            "m3u8" -> VodFormat.HLS
            "mp4" -> VodFormat.MP4
            else -> return null
        }
        return FrostSource(
            provider = provider,
            identity = "site:${provider.id}:${title.id}:${chosen.hashCode()}",
            url = chosen,
            format = format,
            quality = "",
            language = "",
            hevc = null,
            headers = mapOf("User-Agent" to TgHttp.USER_AGENT, "Referer" to "${FrostProtocol.SITE}/"),
            subtitles = emptyList(),
            credentialOrigin = chosen,
            scraperName = "site player"
        )
    }

    /** No bundled sources, browser context, global cookie jar, or cached credentials. */
    fun resolve(title: FrostTitle, provider: FrostProvider = FrostProvider.FROST, fresh: Boolean = false): List<FrostSource> {
        // OG has no endpoint of this app's own: its sources are the media addresses each of that site's
        // servers names on its page for the title, so they are resolved per server instead.
        if (provider == FrostProvider.OG) return OgNative.resolve(title, fresh)
        synchronized(memo) { memo[memoKey(title, provider)] }
            ?.takeIf { !fresh && it.until > System.currentTimeMillis() }?.let { return it.sources }
        val direct = try {
            direct(title, provider)
        } catch (error: FrostResolveException) {
            // The site itself would have moved on to its next server; so do we, before telling anyone it failed.
            sitePlayback(title, provider)?.let { sniffed ->
                remember(title, provider, listOf(sniffed)); return listOf(sniffed)
            }
            throw error
        }
        // A DASH manifest from these hosts is Widevine, which this player does not open: a source list made
        // only of those is, for the viewer, an empty list.
        val playable = direct.filter { it.format != VodFormat.DASH }
        if (playable.isNotEmpty()) { remember(title, provider, playable); return playable }
        sitePlayback(title, provider)?.let { sniffed ->
            val result = listOf(sniffed) + direct
            remember(title, provider, result)
            return result
        }
        return direct
    }

    private fun remember(title: FrostTitle, provider: FrostProvider, sources: List<FrostSource>) {
        if (sources.isEmpty()) return
        synchronized(memo) { memo[memoKey(title, provider)] = Memo(System.currentTimeMillis() + 30 * 60_000, sources) }
    }

    private fun direct(title: FrostTitle, provider: FrostProvider): List<FrostSource> {
        val request = Request.Builder().url(FrostProtocol.endpoint(base, provider, title))
            .header("User-Agent", TgHttp.USER_AGENT).header("Accept", "application/json")
            .header("Cache-Control", "no-store").build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw FrostResolveException(response.code)
                val body = response.body ?: throw FrostResolveException(kind = "empty")
                if (body.contentLength() > FrostProtocol.MAX_BYTES) throw FrostResolveException(kind = "too-large")
                return FrostProtocol.parse(FrostProtocol.readBounded(body.byteStream()).toString(Charsets.UTF_8), provider)
            }
        } catch (error: FrostResolveException) { throw error }
        catch (error: Exception) { // Never expose exception text containing a signed URL.
            val kind = when (error) {
                is javax.net.ssl.SSLException -> "tls"
                is java.net.UnknownHostException -> "dns"
                is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "timeout"
                else -> "unavailable"
            }
            throw FrostResolveException(kind = kind)
        }
    }

    fun cancel() { client.dispatcher.cancelAll(); OgNative.cancel() }
}

/** Independent VOD transport. Never applies Jio token rewriting, live offsets or ClearKey overrides. */
internal object FrostHttp {
    private val base = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(false).build()

    fun client(source: FrostSource, transport: OkHttpClient = base): OkHttpClient {
        val origin = source.credentialOrigin.toHttpUrl()
        return transport.newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            val sameOrigin = origin.scheme == request.url.scheme && origin.host == request.url.host && origin.port == request.url.port
            val builder = request.newBuilder().header("User-Agent", TgHttp.USER_AGENT)
            source.headers.forEach { (name, value) ->
                builder.removeHeader(name)
                val scoped = name.equals("authorization", true) || name.equals("cookie", true) || name.startsWith("x-", true)
                if (!scoped || sameOrigin) builder.header(name, value)
            }
            chain.proceed(builder.build())
        }.build()
    }
}

internal object VodProgress {
    fun resume(position: Long, duration: Long): Long = when {
        position < 10_000 -> 0
        duration > 0 && position >= (duration - 30_000).coerceAtLeast(0) -> 0
        else -> position.coerceAtMost(24 * 3600_000L)
    }
    fun episodes(values: IntArray?): List<Pair<Int, Int>> = (values ?: IntArray(0)).toList().chunked(2)
        .filter { it.size == 2 && it[0] in 1..1000 && it[1] in 1..10000 }
        .map { it[0] to it[1] }.distinct().sortedWith(compareBy({ it.first }, { it.second })).take(2000)
    fun next(title: FrostTitle, episodes: List<Pair<Int, Int>>): FrostTitle? {
        if (!title.isSeries) return null
        val index = episodes.indexOf(title.season to title.episode)
        return if (index >= 0) episodes.getOrNull(index + 1)?.let { title.copy(season = it.first, episode = it.second) } else null
    }
}

/** Bounded recovery shared by errors and the no-picture deadline, reset only on explicit retry/title change. */
internal class VodRecovery {
    private val tried = HashSet<String>()
    private val refreshed = HashSet<FrostProvider>()
    private val visited = linkedSetOf<FrostProvider>()
    private var attempts = 0
    fun reset() { tried.clear(); refreshed.clear(); visited.clear(); attempts = 0 }
    fun visit(provider: FrostProvider) { visited.add(provider) }
    fun take(source: FrostSource): Boolean {
        // A refresh can change request headers without changing the URL. Keep secrets in memory only.
        val request = source.url + "\n" + source.headers.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(request.toByteArray()).joinToString("") { "%02x".format(it) }
        return attempts < 16 && tried.add(fingerprint).also { if (it) attempts++ }
    }
    fun refresh(provider: FrostProvider, status: Int?): Boolean = status in listOf(401, 403) && refreshed.add(provider)
    fun alternative(): FrostProvider? = if (attempts >= 16) null else FrostProvider.values().firstOrNull { it !in visited }
    fun exhausted() = attempts >= 16
}
