package com.aktv.app

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Native Xtream Codes / StarShare IPTV client (Live TV, VOD Movies & Series).
 *
 * Uses standard IPTV player headers (`User-Agent: IPTVSmartersPlayer`, no browser `Origin`/`Referer`)
 * across load-balancer 302 redirects, streams large channel/VOD catalogues object-by-object without
 * holding a monolithic `JSONArray` in memory, and never persists credentials in cached catalogue files.
 */
internal data class XtreamAccount(
    val server: String,
    val username: String,
    val password: String
) {
    val isConfigured: Boolean
        get() = server.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    val normalizedOrigin: String
        get() = XtreamClient.normalizeOrigin(server)

    /** Non-secret cache key derived from server origin + username (password is never included). */
    val cacheKey: String
        get() {
            val raw = "${normalizedOrigin.lowercase(Locale.ROOT)}|${username.trim()}"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

    companion object {
        /** Build-time / inbuilt StarShare account so devices open channels with zero login prompt. */
        val INBUILT: XtreamAccount
            get() = XtreamClient.parseInput(
                BuildConfig.STARSHARE_SERVER.ifBlank { XtreamClient.DEFAULT_SERVER },
                BuildConfig.STARSHARE_USER.ifBlank { XtreamClient.DEFAULT_USER },
                BuildConfig.STARSHARE_PASS.ifBlank { XtreamClient.DEFAULT_PASS }
            )
    }
}

internal data class XtreamAuthInfo(
    val workingOrigin: String,
    val status: String,
    val expDateSec: Long?,
    val activeConnections: Int,
    val maxConnections: Int,
    val allowedFormats: List<String>,
    val canonicalUsername: String = "",
    val canonicalPassword: String = ""
) {
    fun summary(): String {
        val parts = ArrayList<String>(3)
        if (status.isNotBlank()) parts += status
        if (expDateSec != null && expDateSec > 0L) {
            val fmt = SimpleDateFormat("dd MMM yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            parts += "Exp ${fmt.format(Date(expDateSec * 1000L))}"
        }
        if (maxConnections > 0) parts += "$activeConnections/$maxConnections conn"
        return parts.joinToString(" · ").ifBlank { "Connected" }
    }
}

internal data class XtreamLiveChannel(
    val streamId: String,
    val name: String,
    val logo: String,
    val categoryName: String
)

internal data class XtreamVodItem(
    val streamId: Int,
    val name: String,
    val poster: String,
    val categoryName: String,
    val rating: String,
    val year: String,
    val containerExtension: String
)

internal data class XtreamSeriesItem(
    val seriesId: Int,
    val name: String,
    val poster: String,
    val categoryName: String,
    val rating: String,
    val year: String,
    val plot: String
)

internal data class XtreamEpisodeItem(
    val episodeId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val containerExtension: String
)

internal class XtreamException(message: String, val status: Int? = null) : IOException(message)

internal fun interface XtreamTransport {
    fun openReader(url: String, headers: Map<String, String>): XtreamHttpStream
}

internal class XtreamHttpStream(
    val code: Int,
    val reader: Reader,
    private val onClose: () -> Unit = {}
) : AutoCloseable {
    override fun close() {
        runCatching { reader.close() }
        runCatching { onClose() }
    }
}

internal object XtreamClient {
    const val DEFAULT_SERVER = "http://livebox.pro:8080"
    const val DEFAULT_USER = "hud1234"
    const val DEFAULT_PASS = "hud1234"
    const val PLAYER_USER_AGENT = "IPTVSmartersPlayer"
    const val XTREAM_ORIGIN_TAG = "https://xtream.local"
    const val PORTAL_ORIGIN_TAG = "https://stalker.local"
    private const val MAX_CATALOGUE_ITEMS = 35_000

    val SERVER_PRESETS: List<Pair<String, String>> = listOf(
        "livebox.pro:8080" to "http://livebox.pro:8080",
        "livebox.pro (80)" to "http://livebox.pro",
        "starshare.fans:8080" to "http://starshare.fans:8080"
    )

    private val STARSHARE_HOSTS = setOf(
        "livebox.pro",
        "starshare.fans",
        "starshare.st",
        "starshare.cx",
        "starshare.org",
        "starshare.one",
        "starshare.live"
    )

    private val apiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val playbackClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .removeHeader("Origin")
                    .removeHeader("Referer")
                    .header("User-Agent", PLAYER_USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Icy-MetaData", "1")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    private val stalkerPlaybackClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .removeHeader("Origin")
                    .removeHeader("Referer")
                    .header("User-Agent", StalkerProtocol.MAG_USER_AGENT)
                    .header("X-User-Agent", StalkerProtocol.DEVICE_USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Icy-MetaData", "1")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    val defaultTransport: XtreamTransport = XtreamTransport { url, headers ->
        val reqBuilder = Request.Builder().url(url)
            .header("User-Agent", PLAYER_USER_AGENT)
            .header("Accept", "application/json, */*")
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val response = apiClient.newCall(reqBuilder.build()).execute()
        val body = response.body
        val reader = if (body != null) {
            BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8), 16 * 1024)
        } else {
            StringReader("")
        }
        XtreamHttpStream(response.code, reader) { response.close() }
    }

    /**
     * Normalises raw user input (`livebox.pro:8080`, `http://livebox.pro/c/`, or a full `get.php?...` M3U link)
     * into a clean `XtreamAccount`.
     */
    fun parseInput(serverInput: String, usernameInput: String, passwordInput: String): XtreamAccount {
        val rawServer = serverInput.trim()
        var extractedUser = usernameInput.trim()
        var extractedPass = passwordInput.trim()

        if (rawServer.contains("username=", ignoreCase = true) || rawServer.contains("get.php", ignoreCase = true) ||
            rawServer.contains("player_api.php", ignoreCase = true)
        ) {
            val withScheme = if (rawServer.startsWith("http://", true) || rawServer.startsWith("https://", true)) {
                rawServer
            } else {
                "http://$rawServer"
            }
            runCatching {
                val uri = URI(withScheme)
                val queryPairs = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) null
                    else URLDecoder.decode(part.substring(0, eq), "UTF-8") to
                        URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                }.toMap()
                if (extractedUser.isBlank()) extractedUser = queryPairs["username"] ?: queryPairs["u"].orEmpty()
                if (extractedPass.isBlank()) extractedPass = queryPairs["password"] ?: queryPairs["p"].orEmpty()
            }
        }

        val origin = normalizeOrigin(rawServer.ifBlank { DEFAULT_SERVER })
        return XtreamAccount(origin, extractedUser, extractedPass)
    }

    fun normalizeOrigin(raw: String): String {
        val trimmed = raw.trim().ifBlank { DEFAULT_SERVER }
        val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return runCatching {
            val uri = URI(withScheme)
            val scheme = (uri.scheme ?: "http").lowercase(Locale.ROOT)
            val host = (uri.host ?: return@runCatching withScheme.trimEnd('/')).lowercase(Locale.ROOT)
            val portPart = if (uri.port > 0 && !((scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443))) {
                ":${uri.port}"
            } else {
                ""
            }
            // Keep non-portal subpath only if the panel actually lives in a subfolder (never `/c`, `/get.php`, etc.).
            val cleanPath = uri.rawPath.orEmpty().trimEnd('/').let { p ->
                when {
                    p.isEmpty() -> ""
                    p.equals("/c", true) || p.endsWith("/c", true) -> p.removeSuffix("/c").removeSuffix("/C")
                    p.endsWith("/player_api.php", true) -> p.substringBeforeLast('/')
                    p.endsWith("/get.php", true) -> p.substringBeforeLast('/')
                    p.endsWith("/portal.php", true) -> p.substringBeforeLast('/')
                    p.equals("/stalker_portal", true) -> ""
                    else -> p
                }
            }
            "$scheme://$host$portPart$cleanPath".trimEnd('/')
        }.getOrElse { withScheme.trimEnd('/') }
    }

    /**
     * Candidate Xtream origins in priority order: exact configured origin first, followed by port/mirror
     * fallbacks when the host belongs to the StarShare / Livebox cluster.
     */
    fun candidateOrigins(server: String): List<String> {
        val primary = normalizeOrigin(server)
        val out = LinkedHashSet<String>()
        out += primary
        runCatching {
            val uri = URI(primary)
            val scheme = (uri.scheme ?: "http").lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            if (host.isNotEmpty()) {
                if (uri.port == -1 && scheme == "http") out += "http://$host:8080"
                else if (uri.port == 8080 && scheme == "http") out += "http://$host"
            }
            if (host in STARSHARE_HOSTS) {
                SERVER_PRESETS.forEach { (_, presetUrl) -> out += presetUrl }
            }
        }
        return out.toList()
    }

    fun authenticate(
        account: XtreamAccount,
        transport: XtreamTransport = defaultTransport
    ): XtreamAuthInfo {
        require(account.isConfigured) { "Enter your StarShare server URL, username and password." }
        var lastError: Exception? = null
        for (origin in candidateOrigins(account.server)) {
            val url = apiUrl(origin, account.username, account.password, action = null)
            try {
                transport.openReader(url, emptyMap()).use { resp ->
                    if (resp.code in 401..403) {
                        throw XtreamException("Server refused request (HTTP ${resp.code})", resp.code)
                    }
                    if (resp.code !in 200..299) {
                        lastError = XtreamException("Server unavailable (HTTP ${resp.code})", resp.code)
                        return@use
                    }
                    val body = resp.reader.readText().trim()
                    if (!body.startsWith("{")) {
                        lastError = XtreamException("Server did not return Xtream JSON")
                        return@use
                    }
                    val root = JSONObject(body)
                    val user = root.optJSONObject("user_info")
                        ?: run {
                            lastError = XtreamException("Invalid Xtream response from $origin")
                            return@use
                        }
                    val authVal = user.opt("auth")
                    val authed = authVal == 1 || authVal == "1" || authVal == true ||
                        user.optString("status").equals("Active", ignoreCase = true) && !user.has("auth")
                    if (!authed) {
                        // Credentials themselves were rejected by a live Xtream panel — fail immediately.
                        val msg = user.optString("message").takeIf { it.isNotBlank() }
                            ?: "Invalid StarShare username or password."
                        throw XtreamAuthRejectedException(msg)
                    }
                    val status = user.optString("status", "Active")
                    if (status.isNotBlank() && !status.equals("Active", ignoreCase = true)) {
                        throw XtreamAuthRejectedException("StarShare account is $status.")
                    }
                    val expSec = user.optString("exp_date").toLongOrNull()
                        ?: user.optLong("exp_date", -1L).takeIf { it > 0L }
                    val activeCons = user.optString("active_cons").toIntOrNull() ?: user.optInt("active_cons", 0)
                    val maxCons = user.optString("max_connections").toIntOrNull() ?: user.optInt("max_connections", 0)
                    val formats = ArrayList<String>()
                    user.optJSONArray("allowed_output_formats")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optString(i)?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let { formats += it }
                        }
                    }
                    return XtreamAuthInfo(
                        workingOrigin = origin,
                        status = status.ifBlank { "Active" },
                        expDateSec = expSec,
                        activeConnections = activeCons,
                        maxConnections = maxCons,
                        allowedFormats = formats.ifEmpty { listOf("ts", "m3u8") },
                        canonicalUsername = user.optString("username").trim().ifBlank { account.username.trim() },
                        canonicalPassword = user.optString("password").trim().ifBlank { account.password.trim() }
                    )
                }
            } catch (rejected: XtreamAuthRejectedException) {
                throw XtreamException(rejected.message ?: "Authentication failed")
            } catch (e: Exception) {
                if (lastError == null) lastError = e
            }
        }
        val msg = lastError?.message?.let(::redactUrl) ?: "Could not reach StarShare server"
        throw XtreamException(msg, (lastError as? XtreamException)?.status)
    }

    private class XtreamAuthRejectedException(message: String) : IOException(message)

    fun fetchLiveCatalogue(
        account: XtreamAccount,
        workingOrigin: String = account.normalizedOrigin,
        transport: XtreamTransport = defaultTransport
    ): List<XtreamLiveChannel> {
        val categories = fetchCategoryMap(workingOrigin, account, "get_live_categories", transport)
        val channels = ArrayList<XtreamLiveChannel>(2048)
        val url = apiUrl(workingOrigin, account.username, account.password, "get_live_streams")
        transport.openReader(url, emptyMap()).use { resp ->
            if (resp.code !in 200..299) throw XtreamException("Live catalogue failed (HTTP ${resp.code})", resp.code)
            streamJsonObjects(resp.reader) { obj ->
                if (channels.size >= MAX_CATALOGUE_ITEMS) return@streamJsonObjects
                val id = obj.optString("stream_id").trim().ifEmpty { obj.optInt("stream_id", 0).takeIf { it > 0 }?.toString().orEmpty() }
                val name = cleanTitle(obj.optString("name"))
                if (id.isEmpty() || name.isEmpty()) return@streamJsonObjects
                val catId = obj.optString("category_id").trim()
                val catName = cleanCategory(categories[catId] ?: obj.optString("category_name").ifBlank { "Live TV" })
                val logo = obj.optString("stream_icon").trim().takeIf { it.startsWith("http", true) }.orEmpty()
                channels += XtreamLiveChannel(id, name, logo, catName)
            }
        }
        return channels
    }

    fun fetchVodCatalogue(
        account: XtreamAccount,
        workingOrigin: String = account.normalizedOrigin,
        transport: XtreamTransport = defaultTransport
    ): List<XtreamVodItem> {
        val categories = fetchCategoryMap(workingOrigin, account, "get_vod_categories", transport)
        val items = ArrayList<XtreamVodItem>(2048)
        val url = apiUrl(workingOrigin, account.username, account.password, "get_vod_streams")
        transport.openReader(url, emptyMap()).use { resp ->
            if (resp.code !in 200..299) throw XtreamException("VOD catalogue failed (HTTP ${resp.code})", resp.code)
            streamJsonObjects(resp.reader) { obj ->
                if (items.size >= MAX_CATALOGUE_ITEMS) return@streamJsonObjects
                val id = obj.optInt("stream_id", 0).takeIf { it > 0 }
                    ?: obj.optString("stream_id").trim().toIntOrNull() ?: return@streamJsonObjects
                val name = cleanTitle(obj.optString("name").ifBlank { obj.optString("title") })
                if (name.isEmpty()) return@streamJsonObjects
                val catId = obj.optString("category_id").trim()
                val catName = cleanCategory(categories[catId] ?: "Movies")
                val poster = obj.optString("stream_icon").ifBlank { obj.optString("cover") }.trim()
                    .takeIf { it.startsWith("http", true) }.orEmpty()
                val rating = obj.optString("rating").trim().takeIf { it.isNotEmpty() && it != "0" && it != "0.0" }.orEmpty()
                val year = extractYear(obj.optString("year").ifBlank { obj.optString("releasedate") }, name)
                val ext = obj.optString("container_extension", "mp4").trim().lowercase(Locale.ROOT)
                    .takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "mp4"
                items += XtreamVodItem(id, name, poster, catName, rating, year, ext)
            }
        }
        return items
    }

    fun fetchSeriesCatalogue(
        account: XtreamAccount,
        workingOrigin: String = account.normalizedOrigin,
        transport: XtreamTransport = defaultTransport
    ): List<XtreamSeriesItem> {
        val categories = fetchCategoryMap(workingOrigin, account, "get_series_categories", transport)
        val items = ArrayList<XtreamSeriesItem>(1024)
        val url = apiUrl(workingOrigin, account.username, account.password, "get_series")
        transport.openReader(url, emptyMap()).use { resp ->
            if (resp.code !in 200..299) throw XtreamException("Series catalogue failed (HTTP ${resp.code})", resp.code)
            streamJsonObjects(resp.reader) { obj ->
                if (items.size >= MAX_CATALOGUE_ITEMS) return@streamJsonObjects
                val id = obj.optInt("series_id", 0).takeIf { it > 0 }
                    ?: obj.optString("series_id").trim().toIntOrNull() ?: return@streamJsonObjects
                val name = cleanTitle(obj.optString("name").ifBlank { obj.optString("title") })
                if (name.isEmpty()) return@streamJsonObjects
                val catId = obj.optString("category_id").trim()
                val catName = cleanCategory(categories[catId] ?: "Series")
                val poster = obj.optString("cover").ifBlank { obj.optString("stream_icon") }.trim()
                    .takeIf { it.startsWith("http", true) }.orEmpty()
                val rating = obj.optString("rating").trim().takeIf { it.isNotEmpty() && it != "0" && it != "0.0" }.orEmpty()
                val year = extractYear(obj.optString("releaseDate").ifBlank { obj.optString("year") }, name)
                val plot = obj.optString("plot").trim()
                items += XtreamSeriesItem(id, name, poster, catName, rating, year, plot)
            }
        }
        return items
    }

    fun fetchSeriesEpisodes(
        account: XtreamAccount,
        seriesId: Int,
        workingOrigin: String = account.normalizedOrigin,
        transport: XtreamTransport = defaultTransport
    ): List<XtreamEpisodeItem> {
        val url = apiUrl(workingOrigin, account.username, account.password, "get_series_info") + "&series_id=$seriesId"
        transport.openReader(url, emptyMap()).use { resp ->
            if (resp.code !in 200..299) throw XtreamException("Episodes failed (HTTP ${resp.code})", resp.code)
            val root = JSONObject(resp.reader.readText())
            val out = ArrayList<XtreamEpisodeItem>()
            val episodesNode = root.opt("episodes")
            when (episodesNode) {
                is JSONObject -> {
                    val keys = episodesNode.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                    for (seasonKey in keys) {
                        val arr = episodesNode.optJSONArray(seasonKey) ?: continue
                        parseEpisodeArray(arr, seasonKey.toIntOrNull() ?: 1, out)
                    }
                }
                is JSONArray -> {
                    for (s in 0 until episodesNode.length()) {
                        when (val entry = episodesNode.opt(s)) {
                            is JSONArray -> parseEpisodeArray(entry, s + 1, out)
                            is JSONObject -> parseSingleEpisode(entry, entry.optInt("season", 1), out)
                        }
                    }
                }
            }
            return out.sortedWith(compareBy({ it.season }, { it.episode }))
        }
    }

    private fun parseEpisodeArray(arr: JSONArray, fallbackSeason: Int, out: MutableList<XtreamEpisodeItem>) {
        for (i in 0 until arr.length()) {
            val ep = arr.optJSONObject(i) ?: continue
            parseSingleEpisode(ep, fallbackSeason, out)
        }
    }

    private fun parseSingleEpisode(ep: JSONObject, fallbackSeason: Int, out: MutableList<XtreamEpisodeItem>) {
        val id = ep.optString("id").trim().ifEmpty { ep.optInt("id", 0).takeIf { it > 0 }?.toString().orEmpty() }
        if (id.isEmpty()) return
        val season = ep.optInt("season", fallbackSeason).coerceAtLeast(1)
        val epNum = ep.optInt("episode_num", 0).takeIf { it > 0 }
            ?: ep.optString("episode_num").toIntOrNull()?.takeIf { it > 0 } ?: (out.count { it.season == season } + 1)
        val title = cleanTitle(ep.optString("title")).ifBlank { "Episode $epNum" }
        val ext = ep.optString("container_extension", "mp4").trim().lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "mp4"
        out += XtreamEpisodeItem(id, season, epNum, title, ext)
    }

    private fun fetchCategoryMap(
        origin: String,
        account: XtreamAccount,
        action: String,
        transport: XtreamTransport
    ): Map<String, String> = runCatching {
        val map = LinkedHashMap<String, String>()
        val url = apiUrl(origin, account.username, account.password, action)
        transport.openReader(url, emptyMap()).use { resp ->
            if (resp.code !in 200..299) return emptyMap()
            streamJsonObjects(resp.reader) { obj ->
                val id = obj.optString("category_id").trim()
                val name = cleanCategory(obj.optString("category_name"))
                if (id.isNotEmpty() && name.isNotEmpty()) map[id] = name
            }
        }
        map
    }.getOrDefault(emptyMap())

    /**
     * Streams a JSON array `[{...}, {...}]` OR object map `{"1": {...}, "2": {...}}` one child object at a
     * time, keeping peak heap usage tiny even on 20 MB+ Xtream catalogues.
     */
    internal fun streamJsonObjects(reader: Reader, onObject: (JSONObject) -> Unit) {
        var firstContainer = 0.toChar()
        while (true) {
            val ch = reader.read()
            if (ch == -1) return
            val c = ch.toChar()
            if (!c.isWhitespace()) {
                firstContainer = c
                break
            }
        }
        if (firstContainer != '[' && firstContainer != '{') return

        val sb = StringBuilder(512)
        var depth = 0
        var inString = false
        var escape = false

        while (true) {
            val ch = reader.read()
            if (ch == -1) break
            val c = ch.toChar()

            if (depth == 0) {
                if (inString) {
                    if (escape) escape = false
                    else if (c == '\\') escape = true
                    else if (c == '"') inString = false
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> {
                        depth = 1
                        sb.setLength(0)
                        sb.append('{')
                    }
                    ']', '}' -> return
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
                if (sb.length > 512 * 1024) {
                    // Guard against malformed/unterminated objects.
                    depth = 0
                    sb.setLength(0)
                }
            }
        }
    }

    /**
     * Builds native `TgStream` candidates for a StarShare Live TV channel (`MPEG-TS` + `HLS` + `Direct`),
     * ordered according to the account's `allowed_output_formats`.
     */
    fun liveStreamsFor(
        account: XtreamAccount,
        streamId: String,
        allowedFormats: List<String> = listOf("ts", "m3u8"),
        workingOrigin: String = account.normalizedOrigin
    ): List<TgStream> {
        val origin = workingOrigin.ifBlank { account.normalizedOrigin }.trimEnd('/')
        val u = encodeSegment(account.username)
        val p = encodeSegment(account.password)
        val cleanId = streamId.trim()
        val headers = mapOf("User-Agent" to PLAYER_USER_AGENT)

        val tsStream = TgStream(
            label = "StarShare · MPEG-TS",
            url = "$origin/live/$u/$p/$cleanId.ts",
            type = "ts",
            headers = headers,
            requestOrigin = XTREAM_ORIGIN_TAG,
            omitReferrer = true,
            omitOrigin = true
        )
        val hlsStream = TgStream(
            label = "StarShare · HLS",
            url = "$origin/live/$u/$p/$cleanId.m3u8",
            type = "hls",
            headers = headers,
            requestOrigin = XTREAM_ORIGIN_TAG,
            omitReferrer = true,
            omitOrigin = true
        )
        val directStream = TgStream(
            label = "StarShare · Direct",
            url = "$origin/$u/$p/$cleanId",
            type = "ts",
            headers = headers,
            requestOrigin = XTREAM_ORIGIN_TAG,
            omitReferrer = true,
            omitOrigin = true
        )

        val hlsOnly = allowedFormats.isNotEmpty() && "m3u8" in allowedFormats && "ts" !in allowedFormats
        return if (hlsOnly) listOf(hlsStream, tsStream, directStream) else listOf(tsStream, hlsStream, directStream)
    }

    fun vodSourcesFor(
        account: XtreamAccount,
        streamId: Int,
        containerExtension: String,
        workingOrigin: String = account.normalizedOrigin
    ): Pair<ArrayList<String>, ArrayList<String>> {
        val origin = workingOrigin.ifBlank { account.normalizedOrigin }.trimEnd('/')
        val u = encodeSegment(account.username)
        val p = encodeSegment(account.password)
        val ext = containerExtension.trim().lowercase(Locale.ROOT).ifBlank { "mp4" }
        val urls = ArrayList<String>(3)
        val labels = ArrayList<String>(3)
        urls += "$origin/movie/$u/$p/$streamId.$ext"
        labels += "StarShare · Direct (${ext.uppercase(Locale.ROOT)})"
        val altExt = if (ext == "mkv") "mp4" else "mkv"
        urls += "$origin/movie/$u/$p/$streamId.$altExt"
        labels += "StarShare · Alternate (${altExt.uppercase(Locale.ROOT)})"
        return urls to labels
    }

    fun seriesEpisodeSourcesFor(
        account: XtreamAccount,
        episodeId: String,
        containerExtension: String,
        workingOrigin: String = account.normalizedOrigin
    ): Pair<ArrayList<String>, ArrayList<String>> {
        val origin = workingOrigin.ifBlank { account.normalizedOrigin }.trimEnd('/')
        val u = encodeSegment(account.username)
        val p = encodeSegment(account.password)
        val ext = containerExtension.trim().lowercase(Locale.ROOT).ifBlank { "mp4" }
        val urls = ArrayList<String>(2)
        val labels = ArrayList<String>(2)
        urls += "$origin/series/$u/$p/${episodeId.trim()}.$ext"
        labels += "StarShare · Direct (${ext.uppercase(Locale.ROOT)})"
        val altExt = if (ext == "mkv") "mp4" else "mkv"
        urls += "$origin/series/$u/$p/${episodeId.trim()}.$altExt"
        labels += "StarShare · Alternate (${altExt.uppercase(Locale.ROOT)})"
        return urls to labels
    }

    fun isXtreamStream(source: TgStream): Boolean = source.requestOrigin == XTREAM_ORIGIN_TAG

    fun isPortalStream(source: TgStream): Boolean = source.requestOrigin == PORTAL_ORIGIN_TAG

    fun dataSourceFactory(): DataSource.Factory =
        OkHttpDataSource.Factory(playbackClient).setUserAgent(PLAYER_USER_AGENT)

    fun stalkerDataSourceFactory(): DataSource.Factory =
        OkHttpDataSource.Factory(stalkerPlaybackClient).setUserAgent(StalkerProtocol.MAG_USER_AGENT)

    /**
     * Candidate native stream descriptors for a Stalker / maxTV `create_link` URL.
     * Stalker portals (`new.maxtv.be`, `livebox.pro/c`) commonly serve raw `MPEG-TS` (`extension=ts` or
     * `/play/live.php?...`) even without a `.ts` path suffix, while some channels serve HLS `.m3u8`.
     * Returning both in natural priority order lets ExoPlayer fall back automatically instead of failing
     * with `#EXTM3U` missing header.
     */
    fun portalStreamsFor(url: String): List<TgStream> {
        val clean = url.trim()
        val headers = mapOf(
            "User-Agent" to StalkerProtocol.MAG_USER_AGENT,
            "X-User-Agent" to StalkerProtocol.DEVICE_USER_AGENT
        )
        val pathPart = clean.substringBefore('?').lowercase(Locale.ROOT)
        val lower = clean.lowercase(Locale.ROOT)
        if (pathPart.endsWith(".mpd")) {
            return listOf(
                TgStream(label = "maxTV · DASH", url = clean, type = "dash", headers = headers, requestOrigin = PORTAL_ORIGIN_TAG, omitReferrer = true, omitOrigin = true)
            )
        }
        if (pathPart.endsWith(".mp4") || pathPart.endsWith(".mkv")) {
            return listOf(
                TgStream(label = "maxTV · Stream", url = clean, type = "mp4", headers = headers, requestOrigin = PORTAL_ORIGIN_TAG, omitReferrer = true, omitOrigin = true)
            )
        }
        val tsStream = TgStream(
            label = "maxTV · MPEG-TS",
            url = clean,
            type = "ts",
            headers = headers,
            requestOrigin = PORTAL_ORIGIN_TAG,
            omitReferrer = true,
            omitOrigin = true
        )
        val hlsStream = TgStream(
            label = "maxTV · HLS",
            url = clean,
            type = "hls",
            headers = headers,
            requestOrigin = PORTAL_ORIGIN_TAG,
            omitReferrer = true,
            omitOrigin = true
        )
        val looksLikeHls = pathPart.endsWith(".m3u8") || "extension=m3u8" in lower || "output=m3u8" in lower || "type=m3u8" in lower
        return if (looksLikeHls) listOf(hlsStream, tsStream) else listOf(tsStream, hlsStream)
    }

    /**
     * MediaSource for a StarShare or Stalker/maxTV Live TV candidate (`ts` with open-GOP non-IDR support, or `hls`).
     */
    fun liveMediaSource(source: TgStream, liveCfg: MediaItem.LiveConfiguration): MediaSource {
        val data = if (isPortalStream(source)) stalkerDataSourceFactory() else dataSourceFactory()
        return when (source.type) {
            "hls" -> {
                val item = MediaItem.Builder()
                    .setUri(source.url)
                    .setLiveConfiguration(liveCfg)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                HlsMediaSource.Factory(data)
                    .setAllowChunklessPreparation(true)
                    .setLoadErrorHandlingPolicy(NativeLoadErrorPolicy())
                    .createMediaSource(item)
            }
            "mp4" -> {
                val item = MediaItem.Builder()
                    .setUri(source.url)
                    .setLiveConfiguration(liveCfg)
                    .build()
                DefaultMediaSourceFactory(data)
                    .setLoadErrorHandlingPolicy(NativeLoadErrorPolicy())
                    .createMediaSource(item)
            }
            else -> {
                val extractors = DefaultExtractorsFactory()
                    .setTsExtractorFlags(
                        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                    )
                val item = MediaItem.Builder()
                    .setUri(source.url)
                    .setLiveConfiguration(liveCfg)
                    .setMimeType(MimeTypes.VIDEO_MP2T)
                    .build()
                ProgressiveMediaSource.Factory(data, extractors)
                    .setLoadErrorHandlingPolicy(NativeLoadErrorPolicy())
                    .createMediaSource(item)
            }
        }
    }

    /**
     * MediaSource for a StarShare VOD Movie or Series episode (`.mkv`, `.mp4`, `.avi`, `.ts`, or `.m3u8`),
     * leaving container detection to `DefaultExtractorsFactory` so Matroska Dual-Audio & MP4 both play natively.
     */
    fun vodMediaSource(url: String, mediaId: String): MediaSource {
        val data = dataSourceFactory()
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val itemBuilder = MediaItem.Builder().setMediaId(mediaId).setUri(url)
        if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return DefaultMediaSourceFactory(data, extractors)
            .setLoadErrorHandlingPolicy(VodLoadErrorPolicy())
            .createMediaSource(itemBuilder.build())
    }

    const val FILTER_INDIAN_HINDI = 0
    const val FILTER_ENGLISH_HINDI = 1
    const val FILTER_ALL = 2

    fun filterLabel(mode: Int): String = when (mode) {
        FILTER_INDIAN_HINDI -> "Indian Hindi"
        FILTER_ENGLISH_HINDI -> "India + English"
        else -> "All Channels"
    }

    /** Ranks Indian Hindi Entertainment, Movies, Sports/Cricket, News, Kids, Knowledge, Music, and Devotional shelves in natural order. */
    fun categoryRank(category: String): Int {
        val lower = displayCategoryName(category).lowercase(Locale.ROOT)
        val rawLower = category.lowercase(Locale.ROOT)
        val isIndia = INDIA_TAG_REGEX.containsMatchIn(rawLower) || lower.contains("hindi") || lower.contains("bollywood")
        val base = when {
            lower.contains("entertainment") || lower.contains("gec") || lower.contains("general") || lower.contains("serial") -> 0
            lower.contains("movie") || lower.contains("cinema") || lower.contains("bollywood") || lower.contains("film") || lower.contains("dual audio") -> 1
            lower.contains("cricket") || lower.contains("ipl") || lower.contains("sport") -> 2
            lower.contains("news") || lower.contains("business") -> 3
            lower.contains("kid") || lower.contains("cartoon") || lower.contains("anime") -> 4
            lower.contains("knowledge") || lower.contains("infotainment") || lower.contains("documentary") || lower.contains("discovery") || lower.contains("science") || lower.contains("factual") || lower.contains("lifestyle") -> 5
            lower.contains("music") || lower.contains("youth") -> 6
            lower.contains("spiritual") || lower.contains("devotional") || lower.contains("religious") || lower.contains("bhakti") -> 7
            else -> 9
        }
        return if (isIndia) base else base + 12
    }

    /** Cleans noisy IPTV panel country prefixes like `|IN| HINDI ENTERTAINMENT` -> `HINDI ENTERTAINMENT` while preserving clarity. */
    fun displayCategoryName(category: String): String {
        val stripped = category.trim()
            .replace(Regex("^[|\\[\\(\\{\\s]*(?:IN|IND|INDIA|INDIAN|VIP)[|\\]\\)\\}\\s:\\-–—]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^[|:\\-–—\\s]+|[|:\\-–—\\s]+$"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return stripped.ifBlank { category.trim().ifBlank { "Indian TV" } }
    }

    fun keepsByFilter(name: String, category: String, mode: Int): Boolean = when (mode) {
        FILTER_INDIAN_HINDI -> keepsIndianHindi(name, category)
        FILTER_ENGLISH_HINDI -> keepsEnglishOrHindi(name, category)
        else -> !ADULT_REGEX.containsMatchIn("$category $name")
    }

    /**
     * Default StarShare filter: keeps Indian Hindi channels/VOD across ALL genres (Entertainment,
     * Bollywood/Hindi Movies, News, Music, Kids, Devotional) PLUS Indian Sports/Cricket & Dual-Audio
     * Infotainment (Discovery/NatGeo/History/Cartoon Network/Nick), while hiding US/UK/foreign &
     * non-Hindi regional Indian feeds (Tamil, Telugu, Malayalam, Kannada, Bengali, Marathi, Gujarati,
     * Punjabi, Odia, Assamese, Urdu, etc. unless Hindi-dubbed).
     */
    fun keepsIndianHindi(name: String, category: String): Boolean {
        val combined = "$category $name"
        if (ADULT_REGEX.containsMatchIn(combined)) return false

        val explicitHindi = HINDI_EXPLICIT_REGEX.containsMatchIn(combined)

        // 1. Exclude non-Hindi regional Indian languages & regional-only channels unless explicitly Hindi / Hindi-dubbed.
        if (!explicitHindi && REGIONAL_NON_HINDI_REGEX.containsMatchIn(combined)) return false

        // 2. Exclude foreign countries/regions (US, UK, CA, PK, BD, AR, EU, etc.) unless explicitly India/Hindi or Cricket.
        val isCricket = CRICKET_REGEX.containsMatchIn(combined)
        if (!explicitHindi && !INDIA_TAG_REGEX.containsMatchIn(category) && !isCricket) {
            if (FOREIGN_STRICT_REGEX.containsMatchIn(category) || FOREIGN_STRICT_REGEX.containsMatchIn(name)) {
                return false
            }
        }
        // Even inside unmarked categories, drop channels explicitly prefixed PK / BD / UK / US / AR (unless Cricket).
        if (!explicitHindi && !isCricket && FOREIGN_CHANNEL_PREFIX_REGEX.containsMatchIn(name.trim())) {
            return false
        }

        // 3. Keep if tagged India / Hindi / Bollywood / Dual Audio, Indian DTH/OTT, Indian Sports/Cricket, or known pan-India Hindi/Dual-Audio network.
        return explicitHindi ||
            INDIA_TAG_REGEX.containsMatchIn(combined) ||
            isCricket ||
            INDIAN_SPORTS_REGEX.containsMatchIn(combined) ||
            INDIAN_BROADCASTER_REGEX.containsMatchIn(name)
    }

    /** Broader India + English filter for multi-country StarShare bouquets. */
    fun keepsEnglishOrHindi(name: String, category: String): Boolean {
        if (ADULT_REGEX.containsMatchIn("$category $name")) return false
        if (FOREIGN_PREFIX_REGEX.containsMatchIn(category.trim())) return false
        if (!PortalLanguages.keeps(name, category)) return false
        return categoryRank(category) < 25 || PortalLanguages.keeps(name, category)
    }

    private val ADULT_REGEX = Regex(
        "\\b(?:XXX|ADULT|ADULTS|FOR\\s+ADULTS|18\\+|PORN|PLAYBOY|BRAZZERS|EROTIC|ONLYFANS)\\b",
        RegexOption.IGNORE_CASE
    )

    private val HINDI_EXPLICIT_REGEX = Regex(
        "\\b(?:HINDI|BOLLYWOOD|HIN\\s*DUB|HINDI\\s*DUB(?:BED)?|DUAL\\s*AUDIO|MULTI\\s*LANGUAGE\\s*\\|?\\s*DUBBED|DESI|BIGG\\s*BOSS|" +
            "SHAH\\s*RUKH\\s*KHAN|SALMAN\\s*KHAN|AAMIR\\s*KHAN|AMITABH\\s*BACHCHAN|AKSHAY\\s*KUMAR|AJAY\\s*DEVGN|HRITHIK\\s*ROSHAN|RAJESH\\s*KHANNA|DILIP\\s*KUMAR|RAJ\\s*KAPOOR)\\b",
        RegexOption.IGNORE_CASE
    )

    private val INDIA_TAG_REGEX = Regex(
        "(?:^|[|\\[\\(\\{\\s:\\-])(?:IN|IND|INDIA|INDIAN|BHARAT|HINDUSTAN|TATA\\s*PLAY|TATA\\s*SKY|JIO(?:\\s*TV|\\s*CINEMA|\\s*HOTSTAR)?|AIRTEL|DISH\\s*TV|D2H|DOORDARSHAN|HOTSTAR|ZEE5|SONYLIV|SONY\\s*LIV|MX\\s*PLAYER|TVF)(?:$|[|\\]\\)\\}\\s:\\-])",
        RegexOption.IGNORE_CASE
    )

    private val CRICKET_REGEX = Regex(
        "\\b(?:CRICKET|IPL|BCCI|ICC|WPL|ASIA\\s*CUP|WILLOW|ASTRO\\s*CRICKET|STAR\\s*SPORTS|SPORTS\\s*18|SPORTS18|SONY\\s*(?:SPORTS|TEN)|TEN\\s*SPORTS|DD\\s*SPORTS|FANCODE|FAN\\s*CODE)\\b",
        RegexOption.IGNORE_CASE
    )

    private val INDIAN_SPORTS_REGEX = Regex(
        "\\b(?:PRO\\s*KABADDI|ISL|INDIAN\\s*SUPER\\s*LEAGUE|EUROSPORT\\s*INDIA)\\b",
        RegexOption.IGNORE_CASE
    )

    private val REGIONAL_NON_HINDI_REGEX = Regex(
        "\\b(?:TAMIL|TAMIZH|TELUGU|TELGU|MALAYALAM|MALYALAM|KERALAM|KANNADA|MARATHI|MARATHIBANA|BENGALI|BANGLA|PUNJABI|GUJARATI|GUJRATI|ODIA|ORIYA|ASSAMESE|ASSAM|ASOMIYA|BHOJPURI|RAJASTHANI|HARYANVI|URDU|NEPALI|SINHALA|KONKANI|KASHMIRI|MANIPURI|" +
            "SUN\\s*TV|SUN\\s*MUSIC|SUN\\s*NEWS|SUN\\s*LIFE|KTV|ADITHYA|UDAYA|CHINTU|KOCHU|SURYA\\s*TV|KIRAN\\s*TV|GEMINI|ETV\\s*(?:TELUGU|PLUS|CINEMA|ABHIRUCHI|LIFE|ANDHRA|TELANGANA|BAL\\s*BHARAT\\s*(?:TAMIL|TELUGU|MALAYALAM|KANNADA))|" +
            "STAR\\s*(?:MAA|VIJAY|JALSHA|SUVARNA|PRAVAH|KIRAN)|VIJAY\\s*(?:SUPER|TAKAKAR)|JALSHA\\s*MOVIES|PRAVAH\\s*PICTURE|SUVARNA\\s*PLUS|" +
            "ASIANET|MAZHAVIL|KAIRALI|MATHRUBHUMI|AMRITA\\s*TV|FLOWERS\\s*TV|REPORTER\\s*TV|MANORAMA|TWENTY\\s*FOUR|" +
            "KALAIGNAR|POLIMER|THANTHI|PUTHIYA|JAYA\\s*TV|RAJ\\s*TV|ZEE\\s*(?:TAMIL|TELUGU|KANNADA|KERALAM|MARATHI|BANGLA|PUNJABI|SARTHAK|TALKIES|PICCHAR|24\\s*TAAS|24\\s*KALAK|24\\s*GHANTA|YUVAA|THIRAI|CINEMALU|BISKOP|GANGA)|" +
            "COLORS\\s*(?:TAMIL|KANNADA|MARATHI|BANGLA|GUJARATI|ODIA|SUPER|ORIYA)|SONY\\s*(?:MARATHI|AATH)|" +
            "ABP\\s*(?:MAJHA|ANANDA|ASMITA|SANJHA|DESAM|NADU)|NEWS18\\s*(?:LOKMAT|BANGLA|TAMIL|KERALA|KANNADA|ODIA|ASSAM|PUNJAB|GUJARATI|URDU)|" +
            "TV9\\s*(?:MARATHI|TELUGU|KANNADA|GUJARATI|BANGLA)|PTC\\s*(?:PUNJABI|NEWS|CHAK|MUSIC|SIMRAN)|MH\\s*ONE\\s*(?:MUSIC|NEWS)|" +
            "TARANG|OTV|PRATIDIN|RENGONI|RAMDHENU|DY\\s*365)\\b",
        RegexOption.IGNORE_CASE
    )

    private val FOREIGN_STRICT_REGEX = Regex(
        "(?:^|[|\\[\\(\\{\\s:\\-])(?:US|USA|UNITED\\s*STATES|UK|UNITED\\s*KINGDOM|BRITAIN|CA|CAN|CANADA|AU|AUS|AUSTRALIA|NZ|" +
            "PK|PAK|PAKISTAN|BD|BAN|BANGLADESH|AF|AFG|AFGHAN|AFGHANISTAN|NP|NEPAL|LK|SL|SRI\\s*LANKA|" +
            "AR|ARA|ARAB|ARABIC|AE|UAE|SAUDI|QATAR|KUWAIT|OMAN|BAHRAIN|EGYPT|MOROCCO|ALGERIA|TUNISIA|IRAQ|SYRIA|LEBANON|JORDAN|OSN|SHAHID|MBC|ROTANA|" +
            "TR|TUR|TURKEY|TURKISH|IR|IRN|IRAN|PERSIAN|FARSI|KURD|KURDISH|IL|ISR|ISRAEL|HEBREW|" +
            "FR|FRA|FRANCE|FRENCH|DE|GER|GERMANY|GERMAN|AT|AUSTRIA|CH|SWISS|ES|ESP|SPAIN|SPANISH|LATAM|LATINO|MEXICO|ARGENTINA|COLOMBIA|CHILE|PERU|" +
            "PT|POR|PORTUGAL|PORTUGUESE|BR|BRA|BRAZIL|IT|ITA|ITALY|ITALIAN|NL|NED|NETHERLANDS|DUTCH|BE|BELGIUM|" +
            "PL|POL|POLAND|POLISH|RU|RUS|RUSSIA|RUSSIAN|UA|UKR|UKRAINE|RO|ROM|ROMANIA|BG|BULGARIA|GR|GRE|GREECE|AL|ALB|ALBANIA|EX[-\\s]*YU|SERBIA|CROATIA|BOSNIA|" +
            "SE|SWE|SWEDEN|NO|NOR|NORWAY|DK|DEN|DENMARK|FI|FIN|FINLAND|NORDIC|IE|IRL|IRELAND|" +
            "AFRICA|ZA|SOUTH\\s*AFRICA|DSTV|NIGERIA|KENYA|GHANA|ETHIOPIA|SOMALI|" +
            "PH|PHI|PHILIPPINES|MY|MALAYSIA|SG|SINGAPORE|ID|INDONESIA|TH|THAILAND|VN|VIETNAM|CN|CHINA|CHINESE|HK|HONG\\s*KONG|TW|TAIWAN|JP|JAPAN|KR|KOREA|" +
            "CARIBBEAN|SURINAME|NFL|NBA|NHL|MLB|MLS|NASCAR|SPFL|EFL|LOI|GAAGO)(?:$|[|\\]\\)\\}\\s:\\-])",
        RegexOption.IGNORE_CASE
    )

    private val FOREIGN_CHANNEL_PREFIX_REGEX = Regex(
        "^(?:US|USA|UK|CA|AU|NZ|PK|PAK|BD|AF|AR|AE|TR|IR|FR|DE|ES|PT|BR|IT|NL|PL|RU|RO|GR|AL|ZA|PH|MY|ID|TH|VN|CN|HK|JP|KR)\\b\\s*[:|\\-]",
        RegexOption.IGNORE_CASE
    )

    private val INDIAN_BROADCASTER_REGEX = Regex(
        "\\b(?:STAR\\s*(?:PLUS|BHARAT|UTSAV|GOLD|MOVIES|WORLD)|" +
            "SONY\\s*(?:SET|SAB|MAX|PIX|PAL|WAH|YAY|BBC\\s*EARTH|KAL)|SET\\s*(?:INDIA|HD)|SAB\\s*(?:TV|HD)|" +
            "ZEE\\s*(?:TV|CINEMA|BOLLYWOOD|ACTION|CLASSIC|ANMOL|CAFE|ZEST|NEWS|BUSINESS|BHARAT|HINDUSTAN)|" +
            "COLORS\\s*(?:HD|SD|TV|INFINITY|CINEPLEX|RISHTEY)?|RISHTEY\\s*CINEPLEX|&\\s*(?:TV|PICTURES|FLICKS|PRIVE|XPLOR)|AND\\s*(?:TV|PICTURES|FLICKS|PRIVE)|" +
            "DANGAL|SHEMAROO|GOLDMINES|MANORANJAN|B4U\\s*(?:MOVIES|MUSIC|KADAK|BHOJPURI)?|UTV\\s*(?:MOVIES|ACTION|BINDASS)|BINDASS|EPIC\\s*TV|ISHARA|NAZARA|CHUMBAK|ENTER10|MAHA\\s*MOVIE|ALL\\s*TIME\\s*MOVIES|" +
            "AAJ\\s*TAK|INDIA\\s*TODAY|NDTV\\s*(?:INDIA|24X7|PROFIT)|INDIA\\s*TV|ABP\\s*(?:NEWS|GANGA)|NEWS\\s*18\\s*INDIA|NEWS\\s*24|GOOD\\s*NEWS\\s*TODAY|BHARAT\\s*24|BHARAT\\s*EXPRESS|REPUBLIC\\s*(?:TV|BHARAT)|TIMES\\s*NOW|MIRROR\\s*NOW|ET\\s*NOW|CNBC\\s*(?:AWAAZ|TV18)|WION|DD\\s*(?:NATIONAL|NEWS|INDIA|BHARATI|KISAN|RETRO|SPORTS)|SANSAD\\s*TV|SUDARSHAN|TV9\\s*BHARATVARSH|NEWS\\s*NATION|INDIA\\s*NEWS|" +
            "DISCOVERY\\s*(?:INDIA|HD\\s*WORLD|SCIENCE|TURBO|KIDS)|ANIMAL\\s*PLANET\\s*(?:INDIA|HD\\s*WORLD)?|TLC\\s*(?:INDIA|HD)|INVESTIGATION\\s*DISCOVERY|HISTORY\\s*TV\\s*18|NAT\\s*GEO|NATIONAL\\s*GEOGRAPHIC|TRAVELXP|FOOD\\s*FOOD|" +
            "HUNGAMA|SUPER\\s*HUNGAMA|POGO|CARTOON\\s*NETWORK|NICKELODEON|NICK\\s*(?:HD|JR)?|SONIC\\s*NICKELODEON|GUBBARE|DISNEY\\s*(?:INDIA|INTERNATIONAL\\s*HD)|" +
            "9XM|9X\\s*JALWA|MASTIII|ZOOM\\s*TV|MTV\\s*(?:BEATS|INDIA)|SHOWBOX|E24|MUSIC\\s*INDIA|ZING|" +
            "AASTHA|SANSKAR|SADHNA|PARAS\\s*TV|SHUBH\\s*TV|ISHWAR\\s*TV|SATSANG|DIVYA\\s*TV|PEACE\\s*OF\\s*MIND)\\b",
        RegexOption.IGNORE_CASE
    )

    private val FOREIGN_PREFIX_REGEX = Regex(
        "^(?:AR|FR|DE|ES|PT|IT|TR|NL|PL|RO|GR|BG|AL|EX-YU|RU|UA|AF|PK|BD|LK|NP|IR|IQ|KU|VN|TH|ID|MY|PH|CN|JP|KR|BR|LATAM)\\b\\s*[:|\\-]",
        RegexOption.IGNORE_CASE
    )

    fun saveLiveCache(dir: File, key: String, items: List<XtreamLiveChannel>) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "live-$key.tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for (item in items) {
                    w.write(
                        JSONObject()
                            .put("i", item.streamId)
                            .put("n", item.name)
                            .put("l", item.logo)
                            .put("c", item.categoryName)
                            .toString()
                    )
                    w.newLine()
                }
            }
            tmp.renameTo(File(dir, "live-$key.jsonl"))
        }
    }

    fun loadLiveCache(dir: File, key: String): List<XtreamLiveChannel> = runCatching {
        val f = File(dir, "live-$key.jsonl")
        if (!f.exists()) return emptyList()
        val out = ArrayList<XtreamLiveChannel>()
        f.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank()) {
                    val o = JSONObject(line)
                    val id = o.optString("i")
                    val name = o.optString("n")
                    if (id.isNotEmpty() && name.isNotEmpty()) {
                        out += XtreamLiveChannel(id, name, o.optString("l"), o.optString("c", "Live TV"))
                    }
                }
            }
        }
        out
    }.getOrDefault(emptyList())

    /** Redacts Xtream credentials from any URL before displaying or logging errors. */
    fun redactUrl(text: String): String = text
        .replace(Regex("(/(?:live|movie|series)/)[^/\\s]+/[^/\\s]+/"), "$1***:***/")
        .replace(Regex("([?&](?:username|password|u|p)=)[^&\\s]+", RegexOption.IGNORE_CASE), "$1***")

    private fun apiUrl(origin: String, username: String, password: String, action: String?): String {
        val base = origin.trimEnd('/')
        val u = URLEncoder.encode(username.trim(), "UTF-8")
        val p = URLEncoder.encode(password.trim(), "UTF-8")
        return if (action.isNullOrBlank()) {
            "$base/player_api.php?username=$u&password=$p"
        } else {
            "$base/player_api.php?username=$u&password=$p&action=$action"
        }
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20")

    private fun cleanTitle(raw: String): String = raw.trim()
        .replace(Regex("^(?:[A-Z]{2,5}\\s*[|:\\-]\\s*)+"), "")
        .trim()
        .ifBlank { raw.trim() }

    private fun cleanCategory(raw: String): String = raw.trim()
        .replace(Regex("^\\|+\\s*|\\s*\\|+$"), "")
        .replace(Regex("\\s{2,}"), " ")
        .ifBlank { "StarShare" }

    private fun extractYear(rawDate: String, fallbackTitle: String): String {
        Regex("\\b(19\\d{2}|20\\d{2})\\b").find(rawDate)?.let { return it.groupValues[1] }
        Regex("\\((19\\d{2}|20\\d{2})\\)").find(fallbackTitle)?.let { return it.groupValues[1] }
        return ""
    }
}
