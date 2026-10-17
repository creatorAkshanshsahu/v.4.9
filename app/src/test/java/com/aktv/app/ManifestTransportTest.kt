package com.aktv.app

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class ManifestTransportTest {
    @Test fun `HTML returned with HTTP 200 fails as an invalid manifest not a decoder error`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>denied token=synthetic-secret</html>"))
            val source = TgStream("TG", server.url("/video.mpd").toString(), "dash")
            var observed: HttpReply? = null
            val error = assertThrows(ManifestReplyException::class.java) {
                StreamHttp.client(source) { observed = it }.newCall(Request.Builder().url(source.url).build()).execute().close()
            }
            assertEquals(200, error.reply.status); assertEquals(HttpReply.Body.HTML, observed!!.body)
            assertFalse(error.message.orEmpty().contains("synthetic-secret"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `an actual HTTP 403 remains available to credential recovery rather than being relabelled as parse failure`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "text/html").setBody("<html>denied</html>"))
            val source = TgStream("TG", server.url("/video.mpd").toString(), "dash")
            var observed: HttpReply? = null
            StreamHttp.client(source) { observed = it }.newCall(Request.Builder().url(source.url).build()).execute().use {
                assertEquals(403, it.code)
            }
            assertEquals(HttpReply.Body.HTML, observed!!.body)
        }
    }
    @Test fun `inspection does not consume or truncate valid manifest bytes`() {
        MockWebServer().use { server ->
            val body = "<MPD>" + " ".repeat(5000) + "</MPD>"
            server.enqueue(MockResponse().setHeader("Content-Type", "application/dash+xml").setBody(body))
            val source = TgStream("TG", server.url("/video.mpd").toString(), "dash")
            var observed: HttpReply? = null
            StreamHttp.client(source) { observed = it }.newCall(Request.Builder().url(source.url).build()).execute().use {
                assertEquals(body, it.body!!.string())
            }
            assertEquals(HttpReply.Body.DASH, observed!!.body); assertEquals(HttpReply.PREFIX, observed!!.prefixBytes)
        }
    }
    @Test fun `gzip is inspected after transparent decompression not mistaken for a media format`() {
        MockWebServer().use { server ->
            val body = "<MPD>fixture</MPD>"
            val compressed = Buffer()
            GzipSink(compressed).buffer().use { it.writeUtf8(body) }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/dash+xml").setHeader("Content-Encoding", "gzip").setBody(compressed))
            val source = TgStream("TG", server.url("/video.mpd").toString(), "dash")
            var observed: HttpReply? = null
            StreamHttp.client(source) { observed = it }.newCall(Request.Builder().url(source.url).build()).execute().use {
                assertEquals(body, it.body!!.string())
            }
            assertEquals(HttpReply.Body.DASH, observed!!.body)
        }
    }
    @Test fun `verified HLS response on a DASH-labelled URL permits a bounded native format correction`() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n#EXT-X-VERSION:3")) }
            val source = TgStream("TG", server.url("/extensionless").toString(), "dash")
            val error = assertThrows(ManifestReplyException::class.java) {
                StreamHttp.client(source).newCall(Request.Builder().url(source.url).build()).execute().close()
            }
            val corrected = ManifestTypeRecovery().correct(source, error.reply)!!
            assertEquals("hls", corrected.type)
            StreamHttp.client(corrected).newCall(Request.Builder().url(corrected.url).build()).execute().use { assertTrue(it.isSuccessful) }
        }
    }
    @Test fun `segments and progressive sources are not mistaken for root manifest fetches`() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("<html>fixture</html>")) }
            val source = TgStream("TG", server.url("/video.mpd").toString(), "dash")
            var observations = 0
            StreamHttp.client(source) { observations++ }.newCall(Request.Builder().url(server.url("/segment.m4s")).build()).execute().close()
            StreamHttp.client(source.copy(type = "mp4")) { observations++ }.newCall(Request.Builder().url(source.url).build()).execute().close()
            assertEquals(0, observations)
        }
    }
    @Test fun `browser-style same-origin relay uses the document origin on its first foreign redirect`() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/final.mpd")))
            second.enqueue(MockResponse().setBody("<MPD/>"))
            val origin = first.url("/").toString().removeSuffix("/")
            val source = TgStream("TG", first.url("/relay").toString(), "dash", requestOrigin = origin, omitReferrer = true)
            StreamHttp.client(source).newCall(Request.Builder().url(source.url).build()).execute().close()
            assertNull(first.takeRequest().getHeader("Origin"))
            assertEquals(origin, second.takeRequest().getHeader("Origin"))
        } }
    }

}
