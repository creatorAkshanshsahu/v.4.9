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
}

internal data class XtreamAuthInfo(
    val workingOrigin: String,
    val status: String,
    val expDateSec: Long?,
    val activeConnections: Int,
    val maxConnections: Int,
    val allowedFormats: List<String>
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
    const val PLAYER_USER_AGENT = "IPTVSmartersPlayer"
    const val XTREAM_ORIGIN_TAG = "https://xtream.local"
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
                        allowedFormats = formats.ifEmpty { listOf("ts", "m3u8") }
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

    fun dataSourceFactory(): DataSource.Factory =
        OkHttpDataSource.Factory(playbackClient).setUserAgent(PLAYER_USER_AGENT)

    /**
     * MediaSource for a StarShare Live TV candidate (`ts` with open-GOP non-IDR support, or `hls`).
     */
    fun liveMediaSource(source: TgStream, liveCfg: MediaItem.LiveConfiguration): MediaSource {
        val data = dataSourceFactory()
        return if (source.type == "hls") {
            val item = MediaItem.Builder()
                .setUri(source.url)
                .setLiveConfiguration(liveCfg)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            HlsMediaSource.Factory(data)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(NativeLoadErrorPolicy())
                .createMediaSource(item)
        } else {
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

    /** Ranks Indian, Sports, Cricket, Hindi, and English shelves first so the viewer sees relevant rows immediately. */
    fun categoryRank(category: String): Int {
        val lower = category.lowercase(Locale.ROOT)
        return when {
            lower.contains("india") || lower.contains("indian") || lower.startsWith("in ") || lower.startsWith("in|") || lower.startsWith("in:") || lower.startsWith("ind ") -> 0
            lower.contains("cricket") || lower.contains("ipl") -> 1
            lower.contains("hindi") || lower.contains("bollywood") -> 2
            lower.contains("sport") -> 3
            lower.contains("entertainment") -> 4
            lower.contains("movie") || lower.contains("cinema") || lower.contains("hollywood") || lower.contains("dual audio") -> 5
            lower.contains("english") || lower.startsWith("uk ") || lower.startsWith("uk|") || lower.startsWith("us ") || lower.startsWith("usa") -> 6
            lower.contains("news") -> 7
            lower.contains("kids") || lower.contains("cartoon") -> 8
            lower.contains("documentary") || lower.contains("infotainment") || lower.contains("discovery") -> 9
            lower.contains("music") -> 10
            else -> 20
        }
    }

    /** Optional strict English + Hindi / India / Sports filter for huge multi-country StarShare bouquets. */
    fun keepsEnglishOrHindi(name: String, category: String): Boolean {
        val combined = "$category $name".lowercase(Locale.ROOT)
        if (FOREIGN_PREFIX_REGEX.containsMatchIn(category.trim())) return false
        if (!PortalLanguages.keeps(name, category)) return false
        return categoryRank(category) < 20 || PortalLanguages.keeps(name, category)
    }

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
