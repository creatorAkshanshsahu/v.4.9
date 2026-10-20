package com.aktv.app

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Fallback JioTV XMLTV schedule source. The primary TG guide endpoint is preferred; this feed is
 * schedule metadata only and is never used for channel playback, URLs, tokens, or DRM.
 * Public XMLTV source: mitthu786/tvepg, updated by its maintainer's daily workflow.
 */
object ExternalEpg {
    const val SOURCE = "Community JioTV XMLTV"
    const val FEED_URL = "https://raw.githubusercontent.com/mitthu786/tvepg/main/jiotv/epg.xml.gz"
    private const val MAX_COMPRESSED_BYTES = 8L * 1024 * 1024
    private const val REFRESH_MS = 8 * 60 * 60 * 1000L
    private const val OFFLINE_MAX_AGE_MS = 72 * 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L
    private val lock = Any()
    @Volatile private var index: Map<String, List<Programme>>? = null
    @Volatile private var loadedAt = 0L
    @Volatile private var retryAt = 0L

    fun guide(context: Context, channelId: String, nowSec: Long = System.currentTimeMillis() / 1000): Guide? {
        if (channelId.isBlank() || channelId.length > 16 || !channelId.all(Char::isDigit)) return null
        val schedules = synchronized(lock) {
            val now = System.currentTimeMillis()
            val current = index
            if (current != null && now - loadedAt < REFRESH_MS) return@synchronized current
            if (now < retryAt) return@synchronized current
            retryAt = now + RETRY_MS
            val file = File(context.cacheDir, "epg/jiotv-xmltv.xml.gz")
            file.parentFile?.mkdirs()
            val age = now - file.lastModified()
            if (!file.isFile || age >= REFRESH_MS) {
                try { download(file) } catch (_: Exception) {
                    if (!file.isFile || now - file.lastModified() >= OFFLINE_MAX_AGE_MS) return@synchronized current
                }
            }
            try {
                val parsed = EpgXmlTv.parse(file, nowSec)
                index = parsed
                val fileAge = (System.currentTimeMillis() - file.lastModified()).coerceAtLeast(0L)
                loadedAt = System.currentTimeMillis() - fileAge.coerceAtMost(REFRESH_MS)
                parsed
            } catch (_: Exception) { current }
        } ?: return null
        return EpgXmlTv.guide(schedules[channelId].orEmpty(), nowSec)
    }

    private fun download(destination: File) {
        val connection = (URL(FEED_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/gzip, application/octet-stream;q=0.9, */*;q=0.5")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "AK-TV-EPG/1.0 (Android)")
        }
        val temporary = File(destination.parentFile, destination.name + ".download")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("EPG feed returned HTTP ${connection.responseCode}")
            val length = connection.contentLengthLong
            if (length > MAX_COMPRESSED_BYTES) throw IOException("EPG feed exceeds size limit")
            var copied = 0L
            connection.inputStream.use { input ->
                BufferedInputStream(input).use { source ->
                    BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = source.read(buffer)
                            if (n < 0) break
                            copied += n
                            if (copied > MAX_COMPRESSED_BYTES) throw IOException("EPG feed exceeds size limit")
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
            // Validate gzip before replacing a previously good offline cache.
            GZIPInputStream(BufferedInputStream(FileInputStream(temporary))).use { gzip ->
                val buffer = ByteArray(16 * 1024)
                while (gzip.read(buffer) >= 0) { /* exhaust to validate the complete gzip member and checksum */ }
            }
            if (!temporary.renameTo(destination)) {
                destination.delete()
                if (!temporary.renameTo(destination)) throw IOException("Unable to save EPG cache")
            }
            destination.setLastModified(System.currentTimeMillis())
        } finally {
            temporary.delete()
            connection.disconnect()
        }
    }
}

/** Streaming XMLTV parser: retains only current and near-future JioTV channel schedules. */
internal object EpgXmlTv {
    private const val KEEP_PAST_SECONDS = 2 * 60 * 60L
    private const val KEEP_FUTURE_SECONDS = 48 * 60 * 60L

    fun parse(file: File, nowSec: Long): Map<String, List<Programme>> {
        val result = HashMap<String, MutableList<Programme>>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        GZIPInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            var channel = ""
            var start = 0L
            var stop = 0L
            var title = ""
            var desc = ""
            var category = ""
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "programme" -> {
                            channel = parser.getAttributeValue(null, "channel").orEmpty()
                            start = parseXmlTvTime(parser.getAttributeValue(null, "start").orEmpty())
                            stop = parseXmlTvTime(parser.getAttributeValue(null, "stop").orEmpty())
                            title = ""; desc = ""; category = ""
                        }
                        "title" -> title = parser.nextText().trim().take(180)
                        "desc" -> desc = parser.nextText().trim().take(480)
                        "category" -> if (category.isEmpty()) category = parser.nextText().trim().take(80) else parser.nextText()
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "programme") {
                        if (channel.isNotBlank() && channel.length <= 32 && title.isNotBlank() && stop > start &&
                            stop >= nowSec - KEEP_PAST_SECONDS && start <= nowSec + KEEP_FUTURE_SECONDS) {
                            result.getOrPut(channel) { ArrayList() }.add(Programme(
                                title, start, stop, Epg.time(start), Epg.time(stop), desc, category, ""
                            ))
                        }
                        channel = ""
                    }
                }
                event = parser.next()
            }
        }
        return result.mapValues { (_, items) -> items.sortedBy { it.start } }
    }

    fun guide(programmes: List<Programme>, nowSec: Long): Guide? {
        if (programmes.isEmpty()) return null
        val now = programmes.firstOrNull { it.start <= nowSec && nowSec < it.stop }
        val next = programmes.firstOrNull { it.start >= (now?.stop ?: nowSec) }
        val upcoming = programmes.asSequence().filter { it.start >= (next?.stop ?: now?.stop ?: nowSec) }
            .take(4).toList()
        if (now == null && next == null && upcoming.isEmpty()) return null
        return Guide(now, next, upcoming, ExternalEpg.SOURCE)
    }

    private fun parseXmlTvTime(raw: String): Long {
        if (raw.isBlank()) return 0L
        val formats = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmmss")
        for (pattern in formats) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val position = ParsePosition(0)
            val parsed: Date? = parser.parse(raw.trim(), position)
            if (parsed != null && position.index == raw.trim().length) return parsed.time / 1000
        }
        return 0L
    }
}
