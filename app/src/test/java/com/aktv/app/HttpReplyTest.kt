package com.aktv.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HttpReplyTest {
    private fun reply(body: String, status: Int = 200, type: String = "application/dash+xml") = HttpReply.of(status, type, body.toByteArray())

    @Test fun `DASH and HLS are identified from their document signature not only MIME or file extension`() {
        assertEquals(HttpReply.Body.DASH, reply("<?xml version='1.0'?><!-- metadata --><MPD xmlns='urn:mpeg:dash:schema:mpd:2011'/>").body)
        assertEquals(HttpReply.Body.HLS, reply("\uFEFF  #EXTM3U\n#EXT-X-VERSION:3").body)
        assertFalse(ManifestReplies.invalid("dash", reply("<MPD/>", type = "text/html")))
    }
    @Test fun `HTML and JSON containing MPD-looking text are not accepted as a manifest`() {
        val html = reply("<!DOCTYPE html><html>denied <MPD token='private'/></html>")
        assertEquals(HttpReply.Body.HTML, html.body)
        assertTrue(ManifestReplies.invalid("dash", html))
        assertEquals(HttpReply.Body.JSON, reply("{\"error\":\"<MPD/>\"}").body)
        assertEquals(HttpReply.Body.HTML, reply("<!-- <MPD/> --><html>blocked</html>").body)
    }
    @Test fun `XML denial is distinguished from other XML without printing its message`() {
        val error = reply("<?xml version='1.0'?><Error><Message>signed-secret</Message></Error>")
        assertEquals(HttpReply.Body.XML_ERROR, error.body)
        assertFalse(error.describe().contains("signed-secret"))
        assertEquals(HttpReply.Body.XML, reply("<document/>").body)
    }
    @Test fun `opaque compression or unrecognized text is not guessed into an error or another format`() {
        val gzip = HttpReply.of(200, "application/dash+xml", byteArrayOf(0x1f, 0x8b.toByte(), 0))
        assertEquals(HttpReply.Body.GZIP, gzip.body)
        assertFalse(ManifestReplies.invalid("dash", gzip))
        assertFalse(ManifestReplies.invalid("dash", reply("unrecognized fixture text")))
    }
    @Test fun `UTF16 XML and short complete MPDs are recognized without needing the whole document`() {
        assertEquals(HttpReply.Body.DASH, HttpReply.kind("\uFEFF<MPD/>".toByteArray(Charsets.UTF_16LE)))
        assertEquals(HttpReply.Body.DASH, HttpReply.kind("<MPD/>".toByteArray()))
    }
    @Test fun `response description cannot echo a URL header or body credential`() {
        val secret = "https://private.example/media?__hdnea__=secret"
        val result = reply("<html>$secret</html>", 403, secret)
        assertEquals(HttpReply.Mime.OTHER, result.mime)
        assertFalse(result.toString().contains(secret)); assertFalse(result.describe().contains("__hdnea__"))
        assertFalse(result.describe().contains("challenge"))
    }
    @Test fun `inspection is bounded and a remote document prefix is not stored`() {
        val result = reply("<html>" + "x".repeat(HttpReply.PREFIX * 2) + "secret")
        assertEquals(HttpReply.PREFIX, result.prefixBytes)
        assertFalse(result.toString().contains("secret"))
        assertFalse(HttpReply::class.java.declaredFields.any { it.type == ByteArray::class.java })
    }
    @Test fun `HTTP refusal keeps its real status and is not converted to a format failure`() {
        val result = reply("<html>denied</html>", 403, "text/html")
        assertFalse(ManifestReplies.invalid("dash", result))
        assertTrue(ManifestReplies.invalid("dash", reply("<html>denied</html>", 200, "text/html")))
        assertTrue(ManifestReplies.invalid("hls", reply("")))
    }
    @Test fun `a verified format correction retains exact URL headers cookie and source-owned DRM`() {
        val drm = JSONObject().put("type", "clearkey")
        val source = TgStream("Source", "https://media.example/stream?token=synthetic", "dash", drm,
            mapOf("Origin" to TgResolver.SITE), "__hdnea__=synthetic")
        val recovery = ManifestTypeRecovery()
        val corrected = recovery.correct(source, reply("#EXTM3U\n"))!!
        assertEquals("hls", corrected.type)
        assertEquals(source.url, corrected.url); assertEquals(source.cookie, corrected.cookie)
        assertSame(source.drm, corrected.drm); assertEquals(source.headers, corrected.headers)
        assertNull(recovery.correct(corrected, reply("<MPD/>")))
        recovery.reset(); assertNotNull(recovery.correct(corrected, reply("<MPD/>")))
    }
    @Test fun `error pages and MP4 prefixes never become guessed HLS or borrowed media`() {
        val recovery = ManifestTypeRecovery()
        val source = TgStream("Source", "https://media.example/stream", "dash")
        for (body in listOf("<html>denied</html>", "{\"url\":\"https://other.example/other.m3u8\"}"))
            assertNull(recovery.correct(source, reply(body)))
        assertNull(recovery.correct(source, reply("#EXTM3U", 403)))
    }
    @Test fun `wrapped wrong-manifest responses are found without reading exception messages`() {
        val original = ManifestReplyException(reply("<html>secret</html>"), "dash")
        assertSame(original, ManifestReplies.error(IOException("unsafe-message", original)))
        assertFalse(original.message.orEmpty().contains("secret"))
    }
    @Test fun `header variants have separate one-shot format correction budgets without changing their credentials`() {
        val recovery = ManifestTypeRecovery()
        val source = TgStream("Source", "https://media.example/stream", "dash", headers = mapOf("Origin" to TgResolver.SITE))
        val native = source.copy(headers = source.headers + ("User-Agent" to "declared-native"))
        assertNotNull(recovery.correct(source, reply("#EXTM3U")))
        assertNull(recovery.correct(source, reply("#EXTM3U")))
        assertNotNull(recovery.correct(native, reply("#EXTM3U")))
        assertNull(recovery.correct(native, reply("#EXTM3U")))
    }

    @Test fun `Retry-After accepts seconds or a server-relative date without relying on the device clock`() {
        assertEquals(90_000L, HttpReply.retryAfter("90", null, 0))
        assertEquals(60_000L, HttpReply.retryAfter("Mon, 19 Oct 2026 06:18:00 GMT", "Mon, 19 Oct 2026 06:17:00 GMT", 1))
        assertEquals(Long.MAX_VALUE, HttpReply.retryAfter(Long.MAX_VALUE.toString(), null, 0))
        assertNull(HttpReply.retryAfter("not-a-time", null, 0))
    }

}
