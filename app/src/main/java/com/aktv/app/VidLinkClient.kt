package com.aktv.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Native client for VidLink (`vidlink.pro`), one of TheOGPirateBot's primary multi-language VOD
 * servers alongside Elfura (`embed.theogpiratebot.online`).
 *
 * Protocol:
 * 1. Encrypt `"{tmdbId}" + bigEndianUint64(epochSeconds + 480)` with NaCl `crypto_secretbox`
 *    (`XSalsa20-Poly1305`) using the public 32-byte VidLink transport key and a 24-byte zero
 *    nonce; URL-safe base64 encode `nonce || tag || ciphertext` without `=` padding.
 *    If VidLink rotates its transport key and rejects the local token, fall back once to
 *    `https://enc-dec.app/api/enc-vidlink?text={tmdbId}`.
 * 2. Request `GET /api/b/movie/{token}?multiLang=1` or `/api/b/tv/{token}/{season}/{episode}?multiLang=1`
 *    with `Referer: https://vidlink.pro/` and `Origin: https://vidlink.pro`.
 * 3. Convert adaptive HLS playlists (`stream.playlist`) and direct multi-quality MP4 files
 *    (`stream.qualities`) plus subtitle tracks (`stream.captions`) into native [FrostSource] items
 *    for Media3/ExoPlayer — no WebView or JS engine required.
 */
internal class VidLinkClient(
    private val baseUrl: HttpUrl = SITE.toHttpUrl(),
    private val encDecUrl: HttpUrl? = ENC_DEC_API.toHttpUrl(),
    httpClient: OkHttpClient = defaultHttpClient
) {
    private val client: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun resolve(title: FrostTitle): List<FrostSource> {
        val localToken = runCatching { encryptToken(title.id) }.getOrNull()
        if (!localToken.isNullOrBlank()) {
            val fromLocal = fetchStream(title, localToken)
            if (fromLocal != null) {
                val parsed = parseResponse(fromLocal, baseUrl.toString().trimEnd('/'))
                if (parsed.isNotEmpty()) return parsed
            }
        }
        val fallbackToken = fetchRemoteToken(title.id) ?: return emptyList()
        if (fallbackToken == localToken) return emptyList()
        val fromRemote = fetchStream(title, fallbackToken) ?: return emptyList()
        return parseResponse(fromRemote, baseUrl.toString().trimEnd('/'))
    }

    private fun fetchStream(title: FrostTitle, token: String): String? {
        val origin = "${baseUrl.scheme}://${baseUrl.host}" +
            if (baseUrl.port != HttpUrl.defaultPort(baseUrl.scheme)) ":${baseUrl.port}" else ""
        val url = baseUrl.newBuilder().apply {
            addPathSegments("api/b")
            if (title.isSeries) {
                addPathSegment("tv")
                addPathSegment(token)
                addPathSegment(title.season.coerceAtLeast(1).toString())
                addPathSegment(title.episode.coerceAtLeast(1).toString())
            } else {
                addPathSegment("movie")
                addPathSegment(token)
            }
            addQueryParameter("multiLang", "1")
        }.build()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", TgHttp.USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", origin)
            .header("Referer", "$origin/")
            .header("x-playback-environment", "webkit")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                FrostProtocol.readBounded(resp.body?.byteStream() ?: return@use null, MAX_JSON_BYTES)
                    .toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun fetchRemoteToken(tmdbId: Int): String? {
        val endpoint = encDecUrl ?: return null
        val url = endpoint.newBuilder().addQueryParameter("text", tmdbId.toString()).build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", TgHttp.USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val body = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                FrostProtocol.readBounded(resp.body?.byteStream() ?: return@use null, 8_192)
                    .toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        return runCatching {
            JSONObject(body).optString("result").trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    companion object {
        const val SITE = "https://vidlink.pro"
        const val ENC_DEC_API = "https://enc-dec.app/api/enc-vidlink"
        const val MOVIEBOX_REFERER = "https://movieboxonline.net/"
        const val MOVIEBOX_ORIGIN = "https://movieboxonline.net"
        const val MOVIEBOX_USER_AGENT = "okhttp/4.12.0"
        private const val MAX_CAPTIONS = 3
        private const val HEAVY_MP4_BYTES = 1_500_000_000L
        private const val MAX_JSON_BYTES = 512_000
        private const val TOKEN_TTL_SECONDS = 480L

        private const val VIDLINK_KEY_HEX = "c75136c5668bbfe65a7ecad431a745db68b5f381555b38d8f6c699449cf11fcd"
        private val VIDLINK_KEY: ByteArray = hexToBytes(VIDLINK_KEY_HEX)
        private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574) // "expand 32-byte k"
        private val POLY_P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
        private val MOVIEBOX_HOSTS = listOf("hakunaymatata.com", "inmoviebox.com", "aoneroom.com")

        @Volatile private var defaultHttpClientRef: OkHttpClient? = null
        private val defaultHttpClient: OkHttpClient
            get() = defaultHttpClientRef ?: synchronized(this) {
                defaultHttpClientRef ?: ElfuraClient.buildSafeHttpClient(7, 10, 14, followSslRedirects = false)
                    .also { defaultHttpClientRef = it }
            }

        @Volatile private var defaultInstanceRef: VidLinkClient? = null
        private val defaultInstance: VidLinkClient
            get() = defaultInstanceRef ?: synchronized(this) {
                defaultInstanceRef ?: VidLinkClient().also { defaultInstanceRef = it }
            }

        fun resolveDefault(title: FrostTitle): List<FrostSource> = defaultInstance.resolve(title)

        fun cancel() {
            defaultHttpClientRef?.dispatcher?.cancelAll()
        }

        /**
         * MovieBox edge CDNs (`*.hakunaymatata.com`, `*.inmoviebox.com`, `*.aoneroom.com`) enforce
         * a strict Tengine Referer ACL: sending foreign site Referers like `https://tgfrost.lovable.app/`
         * or `https://vidlink.pro/` triggers immediate `403 denied by Referer ACL` or `429` blocks.
         */
        fun isMovieBoxCdn(rawUrl: String): Boolean {
            val host = rawUrl.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
            return MOVIEBOX_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        /**
         * Expands a MovieBox MP4 source into safe CDN header/mirror variants so neither `TG FROST`
         * (`MovieBoxV2`) nor `OG` (`VidLink mwVault`) sends a forbidden third-party `Referer`.
         */
        fun movieBoxVariants(source: FrostSource): List<FrostSource> {
            val parsed = source.url.toHttpUrlOrNull() ?: return listOf(source)
            val cleanHeaders = source.headers.filterKeys {
                !it.equals("referer", ignoreCase = true) &&
                    !it.equals("origin", ignoreCase = true) &&
                    !it.equals("user-agent", ignoreCase = true)
            } + mapOf("User-Agent" to MOVIEBOX_USER_AGENT)
            val mbHeaders = cleanHeaders + mapOf(
                "referer" to MOVIEBOX_REFERER,
                "origin" to MOVIEBOX_ORIGIN
            )
            val out = ArrayList<FrostSource>(3)
            // `bcdn.hakunaymatata.com` throttles burst Range probes or desktop browser UAs on some edges;
            // `bcdnxw` is its unthrottled edge mirror with identical signed paths.
            if (parsed.host.equals("bcdn.hakunaymatata.com", ignoreCase = true)) {
                val mirrorUrl = parsed.newBuilder().host("bcdnxw.hakunaymatata.com").build().toString()
                out += FrostSource(
                    provider = source.provider,
                    identity = "${source.identity}:mb-xw",
                    url = mirrorUrl,
                    format = source.format,
                    quality = source.quality,
                    language = source.language,
                    hevc = source.hevc,
                    headers = mbHeaders,
                    subtitles = source.subtitles,
                    credentialOrigin = mirrorUrl,
                    scraperName = source.scraperName
                )
            }
            // Referer-less native UA variant next: `bcdn.hakunaymatata.com` detector edges reject
            // browser Referer headers with HTTP 429 while serving HTTP 206 to `okhttp/4.12.0`.
            out += FrostSource(
                provider = source.provider,
                identity = "${source.identity}:mb-noref",
                url = source.url,
                format = source.format,
                quality = source.quality,
                language = source.language,
                hevc = source.hevc,
                headers = cleanHeaders,
                subtitles = source.subtitles,
                credentialOrigin = source.url,
                scraperName = source.scraperName
            )
            out += FrostSource(
                provider = source.provider,
                identity = "${source.identity}:mb-ref",
                url = source.url,
                format = source.format,
                quality = source.quality,
                language = source.language,
                hevc = source.hevc,
                headers = mbHeaders,
                subtitles = source.subtitles,
                credentialOrigin = source.url,
                scraperName = source.scraperName
            )
            return out
        }

        fun parseResponse(raw: String, siteOrigin: String = SITE): List<FrostSource> {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
            val stream = root.optJSONObject("stream") ?: root
            val sourceId = root.optString("sourceId").trim().ifBlank { "VidLink" }
            val captions = parseCaptions(stream.optJSONArray("captions"))
            val streamHeaders = parseHeaders(stream.optJSONObject("headers"))
            val defaultWebHeaders = mapOf(
                "referer" to "${siteOrigin.trimEnd('/')}/",
                "origin" to siteOrigin.trimEnd('/')
            )

            val out = ArrayList<FrostSource>()

            // 1. Adaptive HLS stream (`stream.playlist`)
            val playlist = stream.optString("playlist").trim()
            if (playlist.startsWith("https://", ignoreCase = true) || playlist.startsWith("http://", ignoreCase = true)) {
                val headers = if (streamHeaders.isNotEmpty()) streamHeaders
                else if (isMovieBoxCdn(playlist)) emptyMap()
                else defaultWebHeaders
                val base = FrostSource(
                    provider = FrostProvider.OG,
                    identity = "og:vidlink:$sourceId:hls:${playlist.hashCode()}",
                    url = playlist,
                    format = VodFormat.HLS,
                    quality = "Auto",
                    language = "Original",
                    hevc = null,
                    headers = headers,
                    subtitles = captions,
                    scraperName = "VidLink"
                )
                if (isMovieBoxCdn(playlist) && streamHeaders.isEmpty()) out += movieBoxVariants(base)
                else out += base
            }

            // 2. Multi-quality direct files (`stream.qualities`)
            val qualities = stream.optJSONObject("qualities")
            if (qualities != null) {
                data class QualityEntry(
                    val rank: Int,
                    val label: String,
                    val url: String,
                    val format: VodFormat,
                    val hevc: Boolean?,
                    val heavyProgressive: Boolean,
                    val headers: Map<String, String>
                )
                val entries = ArrayList<QualityEntry>()
                val keys = qualities.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val qObj = qualities.optJSONObject(key) ?: continue
                    val url = qObj.optString("url").trim()
                    if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) continue
                    val rank = key.filter { it.isDigit() }.toIntOrNull() ?: 0
                    val label = if (rank > 0) "${rank}p" else key.ifBlank { "HD" }
                    val codec = qObj.optString("codecName").lowercase(Locale.US)
                    val hevc = when {
                        codec.contains("hevc") || codec.contains("h265") || codec.contains("265") -> true
                        codec.contains("h264") || codec.contains("avc") || codec.contains("264") -> false
                        url.contains("/h265/", ignoreCase = true) -> true
                        else -> null
                    }
                    val typeHint = qObj.optString("type").lowercase(Locale.US)
                    val format = when {
                        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) || typeHint == "hls" -> VodFormat.HLS
                        url.substringBefore('?').endsWith(".mpd", ignoreCase = true) || typeHint == "dash" -> VodFormat.DASH
                        else -> VodFormat.MP4
                    }
                    val sizeBytes = qObj.optString("size").trim().toLongOrNull() ?: qObj.optLong("size", 0L)
                    // Raw `/extto/` torrent remuxes and >1.5 GB progressive MP4s often decode frame 1 and
                    // stall buffering on mobile/TV; rank web-faststart `/resource/` encodes first while
                    // keeping heavy 1080p/4K files selectable in Change source.
                    val heavyProgressive = format == VodFormat.MP4 && (
                        url.contains("/extto/", ignoreCase = true) || sizeBytes > HEAVY_MP4_BYTES
                    )
                    val qHeaders = parseHeaders(qObj.optJSONObject("headers"))
                    entries += QualityEntry(rank, label, url, format, hevc, heavyProgressive, qHeaders)
                }
                entries.sortWith(
                    compareByDescending<QualityEntry> { it.hevc != true }
                        .thenByDescending { !it.heavyProgressive }
                        .thenByDescending { if (it.rank in 720..1080) it.rank + 2000 else it.rank }
                )
                for (entry in entries) {
                    val headers = if (entry.headers.isNotEmpty()) entry.headers
                    else if (isMovieBoxCdn(entry.url)) emptyMap()
                    else defaultWebHeaders
                    val base = FrostSource(
                        provider = FrostProvider.OG,
                        identity = "og:vidlink:$sourceId:${entry.label}:${entry.url.hashCode()}",
                        url = entry.url,
                        format = entry.format,
                        quality = entry.label,
                        language = "Original",
                        hevc = entry.hevc,
                        headers = headers,
                        subtitles = captions,
                        scraperName = "VidLink"
                    )
                    if (isMovieBoxCdn(entry.url) && entry.headers.isEmpty()) out += movieBoxVariants(base)
                    else out += base
                }
            }
            return out.distinctBy { "${it.url}|${it.headers["referer"].orEmpty()}" }
        }

        private fun parseCaptions(arr: JSONArray?): List<FrostSubtitle> {
            if (arr == null) return emptyList()
            data class RawCaption(val url: String, val label: String, val mime: String, val langCode: String)
            val raw = ArrayList<RawCaption>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) continue
                val label = obj.optString("language").trim().ifBlank { "Subtitle ${i + 1}" }
                val type = obj.optString("type").trim().lowercase(Locale.US)
                val path = url.substringBefore('?').lowercase(Locale.US)
                val mime = when {
                    type == "srt" || path.endsWith(".srt") -> "application/x-subrip"
                    type == "ass" || type == "ssa" || path.endsWith(".ass") || path.endsWith(".ssa") -> "text/x-ssa"
                    else -> "text/vtt"
                }
                val isEng = label.equals("English", ignoreCase = true) || label.startsWith("en", ignoreCase = true)
                val isHin = label.contains("हिन्दी") || label.equals("Hindi", ignoreCase = true) || label.startsWith("hi", ignoreCase = true)
                val langCode = when {
                    isEng -> "en"
                    isHin -> "hi"
                    else -> label.take(12)
                }
                raw += RawCaption(url, label, mime, langCode)
            }
            val unique = raw.distinctBy { it.url }
            // Media3 MergingMediaSource downloads every attached sidecar subtitle in parallel at prepare()
            // and aborts playback if any one returns 403/404/429. Keep only English & Hindi tracks when
            // present (capped at MAX_CAPTIONS) so 12+ foreign SRTs never burst-throttle `cacdn`.
            val preferred = unique.filter { it.langCode == "en" || it.langCode == "hi" }
            val chosen = (if (preferred.isNotEmpty()) preferred else unique).take(MAX_CAPTIONS)
            val hasEnglish = chosen.any { it.langCode == "en" }
            var defaultAssigned = false
            return chosen.mapIndexed { idx, cap ->
                val isDef = !defaultAssigned && (if (hasEnglish) cap.langCode == "en" else idx == 0)
                if (isDef) defaultAssigned = true
                FrostSubtitle(url = cap.url, label = cap.label, language = cap.langCode, mime = cap.mime, default = isDef)
            }
        }

        private fun parseHeaders(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val lower = k.lowercase(Locale.US)
                if (lower in setOf("referer", "origin", "authorization", "cookie") || lower.startsWith("x-")) {
                    val v = obj.optString(k).trim()
                    if (v.isNotEmpty()) out[lower] = v
                }
            }
            return out
        }

        /**
         * NaCl `crypto_secretbox` (`XSalsa20-Poly1305`) token generator matching VidLink's
         * client-side token envelope (`nonce[24] || poly1305Tag[16] || ciphertext`).
         */
        fun encryptToken(
            mediaId: Int,
            nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
            nonce24: ByteArray = ByteArray(24),
            key32: ByteArray = VIDLINK_KEY
        ): String {
            require(nonce24.size == 24) { "XSalsa20 nonce must be 24 bytes" }
            require(key32.size == 32) { "XSalsa20 key must be 32 bytes" }
            val idBytes = mediaId.toString().toByteArray(Charsets.UTF_8)
            val plaintext = ByteArray(idBytes.size + 8)
            System.arraycopy(idBytes, 0, plaintext, 0, idBytes.size)
            ByteBuffer.wrap(plaintext, idBytes.size, 8)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(nowEpochSeconds + TOKEN_TTL_SECONDS)

            val boxed = secretBox(plaintext, nonce24, key32)
            val combined = ByteArray(nonce24.size + boxed.size)
            System.arraycopy(nonce24, 0, combined, 0, nonce24.size)
            System.arraycopy(boxed, 0, combined, nonce24.size, boxed.size)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
        }

        internal fun secretBox(plaintext: ByteArray, nonce24: ByteArray, key32: ByteArray): ByteArray {
            val padded = ByteArray(32 + plaintext.size)
            System.arraycopy(plaintext, 0, padded, 32, plaintext.size)
            val stream = xsalsa20Xor(padded, nonce24, key32)
            val polyKey = stream.copyOfRange(0, 32)
            val ciphertext = stream.copyOfRange(32, stream.size)
            val tag = poly1305Mac(ciphertext, polyKey)
            val out = ByteArray(16 + ciphertext.size)
            System.arraycopy(tag, 0, out, 0, 16)
            System.arraycopy(ciphertext, 0, out, 16, ciphertext.size)
            return out
        }

        private fun xsalsa20Xor(msg: ByteArray, nonce24: ByteArray, key32: ByteArray): ByteArray {
            val subkey = hsalsa20(key32, nonce24.copyOfRange(0, 16))
            val k = IntArray(8)
            ByteBuffer.wrap(subkey).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(k)
            val n = IntArray(2)
            ByteBuffer.wrap(nonce24, 16, 8).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(n)

            val out = ByteArray(msg.size)
            var counter = 0L
            var pos = 0
            val blockBytes = ByteArray(64)
            while (pos < msg.size) {
                val state = intArrayOf(
                    SIGMA[0], k[0], k[1], k[2],
                    k[3], SIGMA[1], n[0], n[1],
                    counter.toInt(), (counter ushr 32).toInt(), SIGMA[2], k[4],
                    k[5], k[6], k[7], SIGMA[3]
                )
                val core = salsa20Core(state, addInput = true)
                ByteBuffer.wrap(blockBytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(core)
                val take = minOf(64, msg.size - pos)
                for (i in 0 until take) {
                    out[pos + i] = (msg[pos + i].toInt() xor blockBytes[i].toInt()).toByte()
                }
                pos += take
                counter++
            }
            return out
        }

        private fun hsalsa20(key32: ByteArray, nonce16: ByteArray): ByteArray {
            val k = IntArray(8)
            ByteBuffer.wrap(key32).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(k)
            val n = IntArray(4)
            ByteBuffer.wrap(nonce16).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(n)
            val state = intArrayOf(
                SIGMA[0], k[0], k[1], k[2],
                k[3], SIGMA[1], n[0], n[1],
                n[2], n[3], SIGMA[2], k[4],
                k[5], k[6], k[7], SIGMA[3]
            )
            val x = salsa20Core(state, addInput = false)
            val out = ByteArray(32)
            ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                .put(intArrayOf(x[0], x[5], x[10], x[15], x[6], x[7], x[8], x[9]))
            return out
        }

        private fun salsa20Core(input: IntArray, addInput: Boolean): IntArray {
            val x = input.copyOf()
            repeat(10) {
                quarterRound(x, 0, 4, 8, 12)
                quarterRound(x, 5, 9, 13, 1)
                quarterRound(x, 10, 14, 2, 6)
                quarterRound(x, 15, 3, 7, 11)
                quarterRound(x, 0, 1, 2, 3)
                quarterRound(x, 5, 6, 7, 4)
                quarterRound(x, 10, 11, 8, 9)
                quarterRound(x, 15, 12, 13, 14)
            }
            if (addInput) {
                for (i in 0 until 16) x[i] += input[i]
            }
            return x
        }

        private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
            s[b] = s[b] xor (s[a] + s[d]).rotateLeft(7)
            s[c] = s[c] xor (s[b] + s[a]).rotateLeft(9)
            s[d] = s[d] xor (s[c] + s[b]).rotateLeft(13)
            s[a] = s[a] xor (s[d] + s[c]).rotateLeft(18)
        }

        private fun poly1305Mac(msg: ByteArray, key32: ByteArray): ByteArray {
            val rBytes = key32.copyOfRange(0, 16)
            rBytes[3] = (rBytes[3].toInt() and 15).toByte()
            rBytes[7] = (rBytes[7].toInt() and 15).toByte()
            rBytes[11] = (rBytes[11].toInt() and 15).toByte()
            rBytes[15] = (rBytes[15].toInt() and 15).toByte()
            rBytes[4] = (rBytes[4].toInt() and 252).toByte()
            rBytes[8] = (rBytes[8].toInt() and 252).toByte()
            rBytes[12] = (rBytes[12].toInt() and 252).toByte()

            val r = littleEndianUnsigned(rBytes)
            val s = littleEndianUnsigned(key32.copyOfRange(16, 32))
            var acc = BigInteger.ZERO
            var offset = 0
            while (offset < msg.size) {
                val len = minOf(16, msg.size - offset)
                val chunk = ByteArray(len + 1)
                System.arraycopy(msg, offset, chunk, 0, len)
                chunk[len] = 1
                val n = littleEndianUnsigned(chunk)
                acc = acc.add(n).multiply(r).mod(POLY_P)
                offset += 16
            }
            acc = acc.add(s)
            return ByteArray(16) { i -> acc.shiftRight(i * 8).toByte() }
        }

        private fun littleEndianUnsigned(le: ByteArray): BigInteger {
            val be = ByteArray(le.size) { i -> le[le.size - 1 - i] }
            return BigInteger(1, be)
        }

        private fun hexToBytes(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return out
        }
    }
}
