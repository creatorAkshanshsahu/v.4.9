package com.aktv.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native client for the Elfura backend at `embed.theogpiratebot.online` (`/api/v1/...`).
 *
 * The embed page (`/embed/movie/{id}` or `/embed/tv/{id}/{s}/{e}`) is a static UI shell; real playback
 * mints a short-lived session (`pltoken` + `nonce`) via `POST /api/v1/session` (or inline
 * `window.__ELFURA_BOOT__`), queries `/api/v1/playback` (plain JSON or AES-256-GCM envelope `s=1`),
 * and streams playlists/segments through `/api/v1/stream?t=...&nonce=...` with `X-Playback-Token`
 * scoped strictly to the Elfura origin (`FrostHttp.client`).
 *
 * Also reads `/api/v1/catalog` (Movies, Series, Anime) for the dedicated OG section and `/api/v1/episodes`
 * as a native series episode fallback. No WebView or GeckoView is ever opened.
 */
internal object ElfuraClient {
    const val ORIGIN = OgNative.ELFURA
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val CATALOG_TTL_MS = 6L * 3600_000L
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    val SOURCE_IDS = listOf("source-1", "source-2", "source-3", "source-4", "source-5", "source-6")

    private val BOOT_JSON = Regex(
        """window\.__ELFURA_BOOT__\s*=\s*(\{[\s\S]*?\})\s*(?:;|</script>)""",
        RegexOption.IGNORE_CASE
    )

    private val LANGUAGE_NAMES = mapOf(
        "en" to "English", "eng" to "English",
        "hi" to "Hindi", "hin" to "Hindi",
        "ta" to "Tamil", "tam" to "Tamil",
        "te" to "Telugu", "tel" to "Telugu",
        "ml" to "Malayalam", "mal" to "Malayalam",
        "kn" to "Kannada", "kan" to "Kannada",
        "bn" to "Bengali", "ben" to "Bengali",
        "mr" to "Marathi", "mar" to "Marathi",
        "pa" to "Punjabi", "pan" to "Punjabi",
        "gu" to "Gujarati", "guj" to "Gujarati",
        "ur" to "Urdu", "urd" to "Urdu",
        "ja" to "Japanese", "jpn" to "Japanese",
        "ko" to "Korean", "kor" to "Korean",
        "es" to "Spanish", "spa" to "Spanish",
        "fr" to "French", "fre" to "French", "fra" to "French",
        "de" to "German", "ger" to "German", "deu" to "German",
        "it" to "Italian", "ita" to "Italian",
        "pt" to "Portuguese", "por" to "Portuguese",
        "ru" to "Russian", "rus" to "Russian",
        "zh" to "Chinese", "zho" to "Chinese", "chi" to "Chinese",
        "ar" to "Arabic", "ara" to "Arabic"
    )

    internal data class Session(val pltoken: String, val nonce: String)

    internal data class Catalog(
        val movies: List<Movies.Title>,
        val series: List<Movies.Title>,
        val anime: List<Movies.Title>,
        val generatedAt: String = ""
    ) {
        val isEmpty get() = movies.isEmpty() && series.isEmpty() && anime.isEmpty()
        val all get() = movies + series + anime
    }

    private data class CachedCatalog(val savedAt: Long, val catalog: Catalog)
    private val catalogLock = Any()
    private var memoryCatalog: CachedCatalog? = null

    private val defaultClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    fun cancel() {
        defaultClient.dispatcher.cancelAll()
    }

    fun originString(baseUrl: HttpUrl): String {
        val defaultPort = (baseUrl.scheme == "https" && baseUrl.port == 443) ||
            (baseUrl.scheme == "http" && baseUrl.port == 80)
        return if (defaultPort) "${baseUrl.scheme}://${baseUrl.host}"
        else "${baseUrl.scheme}://${baseUrl.host}:${baseUrl.port}"
    }

    fun embedPageUrl(title: FrostTitle, baseUrl: HttpUrl = ORIGIN.toHttpUrl()): String {
        val root = originString(baseUrl)
        return if (title.isSeries) "$root/embed/tv/${title.id}/${title.season}/${title.episode}"
        else "$root/embed/movie/${title.id}"
    }

    /** Extracts `pltoken` and `nonce` from inline `window.__ELFURA_BOOT__ = {...}` HTML when needed. */
    fun parseBootHtml(html: String): Session? {
        val match = BOOT_JSON.find(html) ?: return null
        return try {
            val obj = JSONObject(match.groupValues[1])
            val token = cleanText(obj.opt("pltoken"), 512)
            val nonce = cleanText(obj.opt("nonce"), 256)
            if (token.isNotEmpty()) Session(token, nonce) else null
        } catch (_: Exception) {
            null
        }
    }

    /** Mints a playback session via `POST /api/v1/session`, falling back to `/embed/...` boot HTML. */
    fun obtainSession(
        title: FrostTitle,
        baseUrl: HttpUrl = ORIGIN.toHttpUrl(),
        client: OkHttpClient = defaultClient
    ): Session? {
        val origin = originString(baseUrl)
        val embed = embedPageUrl(title, baseUrl)
        val payload = JSONObject()
            .put("type", if (title.isSeries) "tv" else "movie")
            .put("tmdb", title.id.toString())
            .toString()
        val sessionUrl = baseUrl.newBuilder().encodedPath("/api/v1/session").query(null).build()
        val postReq = Request.Builder()
            .url(sessionUrl)
            .post(payload.toRequestBody(JSON_MEDIA))
            .header("User-Agent", TgHttp.USER_AGENT)
            .header("Accept", "application/json")
            .header("Origin", origin)
            .header("Referer", embed)
            .header("Cache-Control", "no-store")
            .build()
        try {
            client.newCall(postReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val text = resp.body?.byteStream()?.let { FrostProtocol.readBounded(it, MAX_BYTES).toString(Charsets.UTF_8) }.orEmpty()
                    if (text.isNotBlank()) {
                        val root = JSONObject(text)
                        val holder = root.optJSONObject("data") ?: root
                        val token = cleanText(holder.opt("pltoken") ?: holder.opt("token"), 512)
                        val nonce = cleanText(holder.opt("nonce"), 256)
                        if (token.isNotEmpty()) return Session(token, nonce)
                    }
                }
            }
        } catch (_: Exception) { }

        val htmlReq = Request.Builder()
            .url(embed)
            .header("User-Agent", TgHttp.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", "$origin/")
            .build()
        return try {
            client.newCall(htmlReq).execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.body?.byteStream()?.let {
                    parseBootHtml(FrostProtocol.readBounded(it, MAX_BYTES).toString(Charsets.UTF_8))
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decrypts Elfura's AES-256-GCM playback envelope (`s = 1, p = base64url(iv[12] || ciphertext+tag)`),
     * keyed with `SHA-256("${pltoken}:${nonce}:elfura-pb-v1")`.
     */
    fun decryptEnvelope(payload: String, pltoken: String, nonce: String): String? {
        if (payload.isBlank() || pltoken.isBlank() || nonce.isBlank()) return null
        return try {
            val normalized = payload.trim().replace('+', '-').replace('/', '_').trimEnd('=')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val raw = Base64.getUrlDecoder().decode(padded)
            if (raw.size <= 28) return null
            val iv = raw.copyOfRange(0, 12)
            val cipherAndTag = raw.copyOfRange(12, raw.size)
            val key = MessageDigest.getInstance("SHA-256")
                .digest("$pltoken:$nonce:elfura-pb-v1".toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherAndTag), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun unwrapResponse(body: String, session: Session?): JSONObject? {
        if (body.isBlank() || body.length > MAX_BYTES) return null
        return try {
            val outer = JSONObject(body)
            if (outer.optInt("s", 0) == 1) {
                val packed = outer.optString("p", "")
                val s = session ?: return null
                val clear = decryptEnvelope(packed, s.pltoken, s.nonce) ?: return null
                JSONObject(clear)
            } else outer
        } catch (_: Exception) {
            null
        }
    }

    private fun isLoopbackHost(host: String): Boolean {
        val h = host.lowercase(Locale.US)
        return h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "[::1]"
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port

    /**
     * Resolves a media or subtitle URL against [baseUrl], enforcing HTTPS in production while allowing
     * same-origin loopback URLs when tested against a local `MockWebServer`.
     */
    fun decorateUrl(
        rawUrl: String?,
        baseUrl: HttpUrl = ORIGIN.toHttpUrl(),
        nonce: String = "",
        forceProxy: Boolean = false,
        allowSubtitle: Boolean = false
    ): String? {
        val raw = rawUrl?.trim()?.takeIf { it.length in 1..16_384 && it.none { c -> c.isISOControl() } } ?: return null
        val resolved = try {
            val uri = URI(baseUrl.toString()).resolve(raw)
            if (uri.userInfo != null || uri.fragment != null) return null
            val httpUrl = uri.toASCIIString().toHttpUrlOrNull() ?: return null
            val loopbackTest = isLoopbackHost(baseUrl.host) && sameOrigin(httpUrl, baseUrl)
            if (!loopbackTest) {
                val host = httpUrl.host.lowercase(Locale.US)
                if (httpUrl.scheme != "https" || host.isEmpty() ||
                    host.matches(Regex("[0-9.]+")) || host.contains(':') ||
                    Regex("(^|\\.)(localhost|local|internal|lan|home)$").containsMatchIn(host)
                ) return null
            }
            if (!allowSubtitle && httpUrl.encodedPath.contains(Regex("(^|/)(embed|watch)(/|$)", RegexOption.IGNORE_CASE))) {
                return null
            }
            httpUrl
        } catch (_: Exception) {
            return null
        }

        if (!sameOrigin(resolved, baseUrl)) return resolved.toString()
        val builder = resolved.newBuilder()
        if (nonce.isNotBlank() && resolved.queryParameter("nonce").isNullOrBlank()) {
            builder.setQueryParameter("nonce", nonce)
        }
        if (forceProxy && resolved.encodedPath.startsWith("/api/") && resolved.queryParameter("p") != "1") {
            builder.setQueryParameter("p", "1")
        }
        return builder.build().toString()
    }

    /** Resolves native `FrostSource` streams for a movie or episode from Elfura. */
    fun resolve(
        title: FrostTitle,
        baseUrl: HttpUrl = ORIGIN.toHttpUrl(),
        client: OkHttpClient = defaultClient
    ): List<FrostSource> {
        val session = obtainSession(title, baseUrl, client)
        val initial = fetchPlayback(title, session, sourceId = null, baseUrl = baseUrl, client = client)
        val rawCandidates = ArrayList<JSONObject>()
        if (initial != null) collectCandidates(initial, rawCandidates)

        // When the fast server window returns empty, poll individual scraper lanes concurrently
        // under a tight shared deadline so empty titles fail over to VidLink / TG in ~3.5s.
        if (rawCandidates.isEmpty() && session != null) {
            val pool = Executors.newFixedThreadPool(SOURCE_IDS.size) { r ->
                Thread(r, "aktv-elfura-lane").apply { isDaemon = true }
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3_800)
                val jobs = SOURCE_IDS.map { lane ->
                    pool.submit(Callable {
                        fetchPlayback(title, session, sourceId = lane, baseUrl = baseUrl, client = client)
                    })
                }
                for (job in jobs) {
                    val remainNs = (deadline - System.nanoTime()).coerceAtLeast(10_000_000L)
                    val laneJson = try { job.get(remainNs, TimeUnit.NANOSECONDS) } catch (_: Exception) { job.cancel(true); null }
                    if (laneJson != null) collectCandidates(laneJson, rawCandidates)
                }
            } finally {
                pool.shutdownNow()
            }
        }
        if (rawCandidates.isEmpty()) return emptyList()
        return buildSources(title, rawCandidates, session, baseUrl)
    }

    private fun fetchPlayback(
        title: FrostTitle,
        session: Session?,
        sourceId: String?,
        baseUrl: HttpUrl,
        client: OkHttpClient
    ): JSONObject? {
        val origin = originString(baseUrl)
        val embed = embedPageUrl(title, baseUrl)
        val url = baseUrl.newBuilder()
            .encodedPath("/api/v1/playback")
            .query(null)
            .addQueryParameter("type", if (title.isSeries) "tv" else "movie")
            .addQueryParameter("tmdb", title.id.toString())
            .apply {
                if (title.isSeries) {
                    addQueryParameter("s", title.season.toString())
                    addQueryParameter("e", title.episode.toString())
                }
                if (!sourceId.isNullOrBlank()) addQueryParameter("source", sourceId)
                if (!session?.nonce.isNullOrBlank()) addQueryParameter("nonce", session!!.nonce)
            }
            .build()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", TgHttp.USER_AGENT)
            .header("Accept", "application/json")
            .header("Origin", origin)
            .header("Referer", embed)
            .header("Cache-Control", "no-store")
            .apply {
                if (!session?.pltoken.isNullOrBlank()) {
                    header("X-Playback-Token", session!!.pltoken)
                    if (session.nonce.isNotBlank()) header("X-Elfura-Envelope", "1")
                }
            }
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.byteStream()?.let {
                    FrostProtocol.readBounded(it, MAX_BYTES).toString(Charsets.UTF_8)
                }.orEmpty()
                unwrapResponse(body, session)?.takeIf { it.optBoolean("ok", true) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun collectCandidates(payload: JSONObject, dest: MutableList<JSONObject>) {
        payload.optJSONObject("primary")?.let { dest.add(it) }
        val arr = payload.optJSONArray("sources") ?: return
        for (i in 0 until minOf(arr.length(), 48)) {
            arr.optJSONObject(i)?.let { dest.add(it) }
        }
    }

    internal fun buildSources(
        title: FrostTitle,
        candidates: List<JSONObject>,
        session: Session?,
        baseUrl: HttpUrl = ORIGIN.toHttpUrl()
    ): List<FrostSource> {
        val origin = originString(baseUrl)
        val embed = embedPageUrl(title, baseUrl)
        val nonce = session?.nonce.orEmpty()
        val headers = buildMap {
            put("User-Agent", TgHttp.USER_AGENT)
            put("Referer", embed)
            put("Origin", origin)
            if (!session?.pltoken.isNullOrBlank()) put("X-Playback-Token", session!!.pltoken)
        }

        val direct = ArrayList<FrostSource>()
        val proxied = ArrayList<FrostSource>()
        val seen = HashSet<String>()

        for ((idx, item) in candidates.withIndex()) {
            if (listOf("drm", "licenseUrl", "license_url", "clearKeys").any {
                    item.has(it) && !item.isNull(it) && item.opt(it) != false
                }) continue

            val rawUrl = cleanText(item.opt("url") ?: item.opt("src") ?: item.opt("file"), 16_384)
            val mainUrl = decorateUrl(rawUrl, baseUrl, nonce, forceProxy = false) ?: continue
            val format = detectFormat(cleanText(item.opt("type"), 32), mainUrl) ?: continue
            val quality = normalizeQuality(cleanText(item.opt("quality"), 24))
            val sourceLabel = normalizeSourceName(
                cleanText(item.opt("label"), 40).ifBlank { cleanText(item.opt("sourceId"), 32) }
            )
            val multiAudio = item.optBoolean("multiAudio", false)
            val lang = if (multiAudio) "Multi-Audio" else readableLanguage(cleanText(item.opt("audioLang") ?: item.opt("language"), 32))
            val subs = parseSubtitles(item.optJSONArray("subtitles") ?: item.optJSONArray("tracks"), baseUrl, nonce)
            val baseId = cleanText(item.opt("id"), 96).ifBlank { "elfura-$idx" }

            if (seen.add("${mainUrl}|${format.name}|$lang")) {
                direct.add(
                    FrostSource(
                        provider = FrostProvider.OG,
                        identity = digest("og|elfura|$baseId|$mainUrl|${format.name}|$lang"),
                        url = mainUrl,
                        format = format,
                        quality = quality,
                        language = lang,
                        hevc = item.opt("hevc") as? Boolean,
                        headers = headers,
                        subtitles = subs,
                        credentialOrigin = origin,
                        scraperName = sourceLabel
                    )
                )
            }

            // Separate dubbed stream variants published under `audioTracks`
            val audioTracks = item.optJSONArray("audioTracks")
            if (audioTracks != null) {
                for (a in 0 until minOf(audioTracks.length(), 12)) {
                    val track = audioTracks.optJSONObject(a) ?: continue
                    val trackRaw = cleanText(track.opt("url"), 16_384)
                    if (trackRaw.isBlank()) continue
                    val trackUrl = decorateUrl(trackRaw, baseUrl, nonce, forceProxy = false) ?: continue
                    if (trackUrl == mainUrl) continue
                    val trackFormat = detectFormat(cleanText(track.opt("type") ?: item.opt("type"), 32), trackUrl) ?: format
                    val trackLang = cleanText(track.opt("label"), 32)
                        .ifBlank { readableLanguage(cleanText(track.opt("lang"), 32)) }
                        .ifBlank { lang }
                    if (seen.add("${trackUrl}|${trackFormat.name}|$trackLang")) {
                        direct.add(
                            FrostSource(
                                provider = FrostProvider.OG,
                                identity = digest("og|elfura|$baseId|audio-$a|$trackUrl|$trackLang"),
                                url = trackUrl,
                                format = trackFormat,
                                quality = quality,
                                language = trackLang,
                                hevc = null,
                                headers = headers,
                                subtitles = subs,
                                credentialOrigin = origin,
                                scraperName = sourceLabel
                            )
                        )
                    }
                }
            }

            // Backend-proxied (`p=1`, Elfura `roxy`) fallback for ISP-blocked or IP-bound upstream CDNs
            val parsedMain = mainUrl.toHttpUrlOrNull()
            if (parsedMain != null && sameOrigin(parsedMain, baseUrl) && parsedMain.encodedPath.startsWith("/api/")) {
                val proxyUrl = decorateUrl(rawUrl, baseUrl, nonce, forceProxy = true)
                if (proxyUrl != null && proxyUrl != mainUrl && seen.add("${proxyUrl}|${format.name}|$lang")) {
                    proxied.add(
                        FrostSource(
                            provider = FrostProvider.OG,
                            identity = digest("og|elfura|$baseId|proxy|$proxyUrl|${format.name}|$lang"),
                            url = proxyUrl,
                            format = format,
                            quality = quality,
                            language = lang,
                            hevc = item.opt("hevc") as? Boolean,
                            headers = headers,
                            subtitles = subs,
                            credentialOrigin = origin,
                            scraperName = if (sourceLabel.isBlank()) "Elfura Proxy" else "$sourceLabel Proxy"
                        )
                    )
                }
            }
        }

        // Prioritize Hindi / Multi-Audio / adaptive streams ahead of progressive MP4, then append `p=1` proxy mirrors.
        val orderedDirect = direct.sortedWith(
            compareBy<FrostSource>(
                { if (it.format == VodFormat.MP4) 1 else 0 },
                {
                    when {
                        it.language.equals("Multi-Audio", ignoreCase = true) -> 0
                        it.language.contains("Hindi", ignoreCase = true) -> 0
                        else -> 1
                    }
                }
            )
        )
        return (orderedDirect + proxied).take(48)
    }

    private fun parseSubtitles(array: JSONArray?, baseUrl: HttpUrl, nonce: String): List<FrostSubtitle> {
        if (array == null) return emptyList()
        val result = ArrayList<FrostSubtitle>()
        var defaultAssigned = false
        for (i in 0 until minOf(array.length(), 40)) {
            val sub = array.optJSONObject(i) ?: continue
            val rawUrl = cleanText(sub.opt("url") ?: sub.opt("file") ?: sub.opt("src"), 16_384)
            val url = decorateUrl(rawUrl, baseUrl, nonce, forceProxy = false, allowSubtitle = true) ?: continue
            val mime = detectSubtitleMime(cleanText(sub.opt("format") ?: sub.opt("type"), 32), url, baseUrl) ?: continue
            val rawLang = cleanText(sub.opt("lang") ?: sub.opt("language") ?: sub.opt("srclang"), 32)
            val lang = rawLang.takeIf { it.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*")) }
            val label = cleanText(sub.opt("label") ?: sub.opt("name"), 48)
                .ifBlank { readableLanguage(rawLang).ifBlank { lang ?: "Subtitles" } }
            val isDefault = sub.optBoolean("default", false) ||
                (!defaultAssigned && (lang?.startsWith("en", ignoreCase = true) == true || label.startsWith("English", ignoreCase = true)))
            if (isDefault) defaultAssigned = true
            result.add(FrostSubtitle(url, label, lang, mime, isDefault))
        }
        return result.distinctBy { it.url }
    }

    private fun detectFormat(type: String, url: String): VodFormat? {
        val ext = runCatching { URI(url).path.orEmpty().substringAfterLast('.', "").lowercase(Locale.US) }.getOrDefault("")
        when (ext) {
            "m3u8", "m3u" -> return VodFormat.HLS
            "mp4", "m4v" -> return VodFormat.MP4
            "mpd" -> return VodFormat.DASH
        }
        when (type.lowercase(Locale.US)) {
            "hls", "m3u8", "application/x-mpegurl", "application/vnd.apple.mpegurl" -> return VodFormat.HLS
            "mp4", "video/mp4", "video", "native" -> return VodFormat.MP4
            "dash", "mpd", "application/dash+xml" -> return VodFormat.DASH
        }
        if (PageCrawl.isTicket(url)) return VodFormat.HLS
        return null
    }

    private fun detectSubtitleMime(type: String, url: String, baseUrl: HttpUrl): String? {
        val t = type.lowercase(Locale.US)
        val parsed = url.toHttpUrlOrNull()
        val ext = parsed?.encodedPath?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
        val queryFmt = parsed?.queryParameter("format")?.lowercase(Locale.US).orEmpty()
        return when {
            t in setOf("vtt", "webvtt", "text/vtt") || ext == "vtt" || queryFmt == "vtt" -> "text/vtt"
            t in setOf("srt", "application/x-subrip") || ext == "srt" || queryFmt == "srt" -> "application/x-subrip"
            t in setOf("ass", "ssa", "text/x-ssa") || ext in setOf("ass", "ssa") || queryFmt in setOf("ass", "ssa") -> "text/x-ssa"
            t in setOf("ttml", "application/ttml+xml") || ext == "ttml" -> "application/ttml+xml"
            parsed != null && (sameOrigin(parsed, baseUrl) ||
                parsed.host.equals("embed.reelsdownload.online", ignoreCase = true) ||
                parsed.host.contains("wyzie", ignoreCase = true)) -> "text/vtt"
            else -> null
        }
    }

    private fun normalizeQuality(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.equals("auto", ignoreCase = true)) return "Auto"
        if (trimmed.equals("4k", ignoreCase = true) || trimmed == "2160" || trimmed.equals("2160p", ignoreCase = true)) return "4K"
        val digits = Regex("([0-9]{3,4})p?", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
        return if (digits != null) "${digits}p" else ""
    }

    private fun normalizeSourceName(raw: String): String {
        val cleaned = raw.substringBefore('·').replace(Regex("[^\\p{L}\\p{N} ._()-]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(32)
        if (cleaned.isEmpty()) return "Elfura"
        if (cleaned.matches(Regex("source-[0-9]+", RegexOption.IGNORE_CASE))) {
            return "Source " + cleaned.substringAfter('-')
        }
        return cleaned
    }

    private fun readableLanguage(code: String): String {
        val norm = code.trim().lowercase(Locale.US)
        if (norm.isEmpty() || norm == "und" || norm == "unknown") return ""
        val primary = norm.substringBefore('-').substringBefore('_')
        return LANGUAGE_NAMES[norm] ?: LANGUAGE_NAMES[primary] ?: code.trim().take(24)
    }

    // ---------- Curated OG section catalog (`/api/v1/catalog`) & episode fallback (`/api/v1/episodes`) ----------

    fun catalog(
        diskDir: File? = Movies.diskDir,
        force: Boolean = false,
        baseUrl: HttpUrl = ORIGIN.toHttpUrl(),
        client: OkHttpClient = defaultClient
    ): Catalog {
        val now = System.currentTimeMillis()
        if (!force) {
            synchronized(catalogLock) { memoryCatalog }?.let { cached ->
                if (now - cached.savedAt < CATALOG_TTL_MS && !cached.catalog.isEmpty) return cached.catalog
            }
        }
        val cacheFile = diskDir?.let { File(it, "og_elfura_catalog.json") }
        if (!force && cacheFile != null && cacheFile.exists() && now - cacheFile.lastModified() < CATALOG_TTL_MS) {
            val disk = runCatching { parseCatalog(cacheFile.readText()) }.getOrNull()
            if (disk != null && !disk.isEmpty) {
                synchronized(catalogLock) { memoryCatalog = CachedCatalog(cacheFile.lastModified(), disk) }
                return disk
            }
        }

        val url = baseUrl.newBuilder().encodedPath("/api/v1/catalog").query(null).build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", TgHttp.USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", "${originString(baseUrl)}/")
            .build()
        return try {
            val raw = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("empty")
                FrostProtocol.readBounded(body.byteStream(), MAX_BYTES).toString(Charsets.UTF_8)
            }
            val parsed = parseCatalog(raw)
            if (parsed.isEmpty) throw IOException("empty")
            synchronized(catalogLock) { memoryCatalog = CachedCatalog(now, parsed) }
            try { cacheFile?.writeText(raw) } catch (_: Exception) { }
            parsed
        } catch (error: Exception) {
            val staleMem = synchronized(catalogLock) { memoryCatalog?.catalog }
            if (staleMem != null && !staleMem.isEmpty) return staleMem
            val staleDisk = runCatching { cacheFile?.takeIf { it.exists() }?.readText()?.let(::parseCatalog) }.getOrNull()
            if (staleDisk != null && !staleDisk.isEmpty) {
                synchronized(catalogLock) { memoryCatalog = CachedCatalog(now, staleDisk) }
                return staleDisk
            }
            throw error
        }
    }

    fun parseCatalog(body: String): Catalog {
        val root = JSONObject(body)
        val movies = parseCatalogItems(root.optJSONArray("movies"), defaultType = "movie", extraGenres = emptyList())
        val series = parseCatalogItems(root.optJSONArray("series"), defaultType = "series", extraGenres = emptyList())
        val anime = parseCatalogItems(root.optJSONArray("anime"), defaultType = "series", extraGenres = listOf("Anime"))
        return Catalog(movies, series, anime, cleanText(root.opt("generatedAt"), 64))
    }

    private fun parseCatalogItems(
        array: JSONArray?,
        defaultType: String,
        extraGenres: List<String>
    ): List<Movies.Title> {
        if (array == null) return emptyList()
        val list = ArrayList<Movies.Title>()
        for (i in 0 until minOf(array.length(), 60)) {
            val item = array.optJSONObject(i) ?: continue
            val tmdb = cleanText(item.opt("tmdbId") ?: item.opt("tmdb") ?: item.opt("id"), 20).toIntOrNull()
                ?: item.optInt("tmdbId", item.optInt("id", 0))
            val name = cleanText(item.opt("title") ?: item.opt("name"), 140)
            val poster = safeImageUrl(cleanText(item.opt("poster"), 512))
            if (tmdb <= 0 || name.isEmpty() || poster.isEmpty()) continue
            val rawKind = cleanText(item.opt("type"), 20).lowercase(Locale.US)
            val type = when (rawKind) {
                "movie" -> "movie"
                "tv", "series", "anime" -> "series"
                else -> defaultType
            }
            val backdrop = safeImageUrl(cleanText(item.opt("backdrop"), 512))
            val year = cleanText(item.opt("year"), 12).take(4)
            val ratingNum = item.optDouble("rating", Double.NaN)
            val rating = if (!ratingNum.isNaN() && ratingNum > 0.0) String.format(Locale.US, "%.1f", ratingNum) else ""
            val overview = cleanText(item.opt("overview") ?: item.opt("description"), 800)
            val imdbKey = (if (type == "series") "tv" else "mv") + tmdb
            list.add(
                Movies.Title(
                    imdb = imdbKey,
                    tmdb = tmdb,
                    type = type,
                    name = name,
                    poster = poster,
                    background = backdrop,
                    logo = "",
                    year = year,
                    rating = rating,
                    genres = extraGenres,
                    description = overview
                )
            )
        }
        return list.distinctBy { it.imdb }
    }

    fun searchCatalog(
        query: String,
        diskDir: File? = Movies.diskDir,
        baseUrl: HttpUrl = ORIGIN.toHttpUrl(),
        client: OkHttpClient = defaultClient
    ): List<Movies.Title> {
        val tokens = query.trim().lowercase(Locale.US).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val cat = runCatching { catalog(diskDir = diskDir, force = false, baseUrl = baseUrl, client = client) }.getOrNull()
            ?: return emptyList()
        return cat.all.filter { title ->
            val hay = "${title.name} ${title.description} ${title.genres.joinToString(" ")}".lowercase(Locale.US)
            tokens.all { hay.contains(it) }
        }.distinctBy { it.imdb }
    }

    /** Fallback episode metadata via Elfura `/api/v1/episodes` when TMDB details are unreachable. */
    fun episodes(
        tmdb: Int,
        baseUrl: HttpUrl = ORIGIN.toHttpUrl(),
        client: OkHttpClient = defaultClient
    ): List<Movies.Episode> {
        if (tmdb <= 0) return emptyList()
        val probeTitle = FrostTitle(tmdb, "tv", 1, 1)
        val session = obtainSession(probeTitle, baseUrl, client)
        val firstSeason = fetchSeason(tmdb, 1, session, baseUrl, client) ?: return emptyList()
        val out = ArrayList<Movies.Episode>()
        appendEpisodes(firstSeason, 1, out)
        val seasonNums = ArrayList<Int>()
        firstSeason.optJSONArray("seasons")?.let { seasons ->
            for (i in 0 until minOf(seasons.length(), 20)) {
                val n = seasons.optJSONObject(i)?.optInt("seasonNumber", 0) ?: 0
                if (n > 1) seasonNums.add(n)
            }
        }
        for (seasonNumber in seasonNums.distinct().take(15)) {
            val seasonJson = fetchSeason(tmdb, seasonNumber, session, baseUrl, client) ?: continue
            appendEpisodes(seasonJson, seasonNumber, out)
        }
        return out.distinctBy { it.season * 10_000 + it.episode }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private fun fetchSeason(
        tmdb: Int,
        season: Int,
        session: Session?,
        baseUrl: HttpUrl,
        client: OkHttpClient
    ): JSONObject? {
        val origin = originString(baseUrl)
        val url = baseUrl.newBuilder()
            .encodedPath("/api/v1/episodes")
            .query(null)
            .addQueryParameter("tmdb", tmdb.toString())
            .addQueryParameter("s", season.toString())
            .apply {
                if (!session?.nonce.isNullOrBlank()) addQueryParameter("nonce", session!!.nonce)
            }
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", TgHttp.USER_AGENT)
            .header("Accept", "application/json")
            .header("Origin", origin)
            .header("Referer", "$origin/embed/tv/$tmdb/$season/1")
            .apply {
                if (!session?.pltoken.isNullOrBlank()) {
                    header("X-Playback-Token", session!!.pltoken)
                    if (session.nonce.isNotBlank()) header("X-Elfura-Envelope", "1")
                }
            }
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.byteStream()?.let {
                    FrostProtocol.readBounded(it, MAX_BYTES).toString(Charsets.UTF_8)
                }.orEmpty()
                unwrapResponse(body, session)?.takeIf { it.optBoolean("ok", true) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun appendEpisodes(payload: JSONObject, fallbackSeason: Int, dest: MutableList<Movies.Episode>) {
        val arr = payload.optJSONArray("episodes") ?: return
        for (i in 0 until minOf(arr.length(), 500)) {
            val epObj = arr.optJSONObject(i) ?: continue
            val epNum = epObj.optInt("episodeNumber", epObj.optInt("episode_number", 0))
            if (epNum <= 0) continue
            val sNum = epObj.optInt("seasonNumber", epObj.optInt("season_number", fallbackSeason)).coerceAtLeast(1)
            val name = cleanText(epObj.opt("name"), 140).ifBlank { "Episode $epNum" }
            val overview = cleanText(epObj.opt("overview"), 600)
            val still = safeImageUrl(cleanText(epObj.opt("still"), 512))
            dest.add(Movies.Episode(sNum, epNum, name, overview, still))
        }
    }

    private fun safeImageUrl(raw: String): String {
        val url = raw.trim()
        return if (url.startsWith("https://", ignoreCase = true) && url.none { it.isISOControl() }) url else ""
    }

    private fun cleanText(value: Any?, max: Int): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value.replace(Regex("[\\p{Cntrl}]+"), " ").trim().take(max)
        else -> value.toString().replace(Regex("[\\p{Cntrl}]+"), " ").trim().take(max)
    }

    private fun digest(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

    internal fun clearCacheForTest() {
        synchronized(catalogLock) { memoryCatalog = null }
    }
}
