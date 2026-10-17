package com.aktv.app

import okhttp3.Response
import java.io.IOException

/** Deliberately data-poor: never holds a URL, header value, token, key or response text. */
internal data class HttpReply(
    val status: Int, val mime: Mime, val body: Body, val prefixBytes: Int,
    val challenge: Boolean = false, val redirects: Int = 0
) {
    enum class Mime { DASH, HLS, HTML, JSON, TEXT, SCRIPT, MP4, OTHER, MISSING }
    enum class Body { DASH, HLS, HTML, JSON, XML_ERROR, XML, MP4, GZIP, OTHER, EMPTY, UNREAD }
    fun describe() = "HTTP $status; MIME ${mime.name}; body ${body.name}; prefix $prefixBytes B; redirects $redirects" +
        if (challenge) "; server challenge indicated" else ""

    companion object {
        const val PREFIX = 2048
        fun mime(raw: String): Mime = when (raw.substringBefore(';').trim().lowercase()) {
            "application/dash+xml" -> Mime.DASH
            "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> Mime.HLS
            "text/html", "application/xhtml+xml" -> Mime.HTML
            "application/json", "text/json" -> Mime.JSON
            "text/plain" -> Mime.TEXT
            "application/javascript", "text/javascript" -> Mime.SCRIPT
            "video/mp4", "audio/mp4" -> Mime.MP4
            "" -> Mime.MISSING
            else -> Mime.OTHER
        }
        fun kind(bytes: ByteArray): Body {
            if (bytes.isEmpty()) return Body.EMPTY
            val prefix = bytes.copyOfRange(0, minOf(bytes.size, PREFIX))
            if (prefix.size >= 2 && prefix[0] == 0x1f.toByte() && prefix[1] == 0x8b.toByte()) return Body.GZIP
            if (prefix.size >= 8 && prefix.copyOfRange(4, 8).toString(Charsets.US_ASCII) in setOf("ftyp", "styp", "moof", "moov")) return Body.MP4
            val charset = when {
                prefix.size >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                prefix.size >= 2 && prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte() -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            var text = prefix.toString(charset).trimStart().removePrefix("\uFEFF").trimStart()
            if (text.startsWith("#EXTM3U")) return Body.HLS
            if (text.startsWith('{') || text.startsWith('[')) return Body.JSON
            // Inspect the document root, not an MPD-looking string inside an HTML error or comment.
            repeat(8) {
                text = when {
                    text.startsWith("<?xml", true) && text.contains("?>") -> text.substringAfter("?>").trimStart()
                    text.startsWith("<!--") && text.contains("-->") -> text.substringAfter("-->").trimStart()
                    else -> text
                }
            }
            if (text.startsWith("<!doctype html", true)) return Body.HTML
            val root = Regex("^<(?:[A-Za-z_][A-Za-z0-9_.-]*:)?([A-Za-z_][A-Za-z0-9_.-]*)(?=[\\s/>])").find(text)?.groupValues?.get(1)
            return when (root?.lowercase()) {
                "mpd" -> Body.DASH
                "html", "head", "body" -> Body.HTML
                "error", "errorresponse" -> Body.XML_ERROR
                null -> Body.OTHER
                else -> Body.XML
            }
        }
        fun of(status: Int, type: String, prefix: ByteArray, challenge: Boolean = false, redirects: Int = 0) =
            HttpReply(status, mime(type), kind(prefix), minOf(prefix.size, PREFIX), challenge, redirects.coerceIn(0, 20))
        /** Called after OkHttp's bridge has decompressed normal gzip responses. Peek does not consume media. */
        fun inspect(response: Response): HttpReply {
            var hops = 0; var prior = response.priorResponse
            while (prior != null && hops < 20) { hops++; prior = prior.priorResponse }
            val type = mime(response.header("Content-Type").orEmpty())
            val challenged = response.header("cf-mitigated").equals("challenge", true)
            val prefix = try { response.peekBody(PREFIX.toLong()).bytes() } catch (_: IOException) { null }
            return if (prefix == null) HttpReply(response.code, type, Body.UNREAD, 0, challenged, hops)
            else HttpReply(response.code, type, kind(prefix), prefix.size, challenged, hops)
        }
    }
}

internal enum class JjtvLookupStage(val label: String) {
    PAGE("player page"), SCRIPT("player script"), CATALOGUE("channel catalogue"),
    PRIMARY_COOKIE("general cookie"), FALLBACK_COOKIE("fallback cookie"), STAR_COOKIE("Star/PL cookie"), UNKNOWN("metadata")
}

/** Not an HTTP denial: retain HTTP 200 when an error page was returned with a success status. */
internal class ManifestReplyException(val reply: HttpReply, val expected: String) : IOException(
    "Expected ${if (expected == "hls") "HLS" else "DASH"} manifest; ${reply.describe()}"
)

internal object ManifestReplies {
    fun invalid(expected: String, reply: HttpReply): Boolean {
        if (expected !in setOf("dash", "hls") || reply.status !in 200..299) return false
        return when (reply.body) {
            HttpReply.Body.HTML, HttpReply.Body.JSON, HttpReply.Body.XML_ERROR, HttpReply.Body.EMPTY, HttpReply.Body.MP4 -> true
            HttpReply.Body.DASH -> expected != "dash"
            HttpReply.Body.HLS -> expected != "hls"
            else -> false // An unknown/binary prefix is not proof of an error page. Leave parsing to Media3.
        }
    }
    fun error(error: Throwable): ManifestReplyException? {
        var cause: Throwable? = error
        repeat(8) { val item = cause ?: return null; if (item is ManifestReplyException) return item; cause = item.cause }
        return null
    }
}

/** One signature-backed correction per exact URL/request profile. Never HTML -> guessed media. */
internal class ManifestTypeRecovery {
    private val corrected = HashSet<String>()
    fun reset() = corrected.clear()
    fun correct(source: TgStream, reply: HttpReply): TgStream? {
        val actual = when (reply.body) { HttpReply.Body.DASH -> "dash"; HttpReply.Body.HLS -> "hls"; else -> return null }
        if (reply.status !in 200..299 || source.type !in setOf("dash", "hls") || actual == source.type) return null
        // Browser/native header variants may legitimately negotiate different formats on one URL.
        val identity = source.url + "\n" + source.requestOrigin + "\n" + source.omitReferrer + "\n" +
            source.cookie + "\n" + source.headers.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (!corrected.add(fingerprint)) return null
        return source.copy(type = actual)
    }
}
