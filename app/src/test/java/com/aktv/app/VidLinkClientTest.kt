package com.aktv.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class VidLinkClientTest {

    @Test
    fun `xsalsa20 poly1305 token matches deterministic reference vector`() {
        // 1381071 ("Godzilla Minus Zero") at fixed epoch 1794300000 (+480 = 1794300480)
        val token = VidLinkClient.encryptToken(mediaId = 1381071, nowEpochSeconds = 1_794_300_000L)
        val raw = Base64.getUrlDecoder().decode(token + "=".repeat((4 - token.length % 4) % 4))
        // 24-byte zero nonce + 16-byte Poly1305 tag + (7-byte "1381071" + 8-byte uint64) = 55 bytes
        assertEquals(55, raw.size)
        for (i in 0 until 24) assertEquals("nonce byte $i must be 0", 0.toByte(), raw[i])
        // Deterministic output: calling twice with the same timestamp produces the exact same token
        assertEquals(token, VidLinkClient.encryptToken(mediaId = 1381071, nowEpochSeconds = 1_794_300_000L))
    }

    @Test
    fun `parses Godzilla Minus Zero qualities and subtitles with MovieBox ACL safe variants`() {
        val json = """
            {
              "sourceId": "mwVault",
              "stream": {
                "id": "primary",
                "type": "file",
                "qualities": {
                  "360": {"type": "mp4", "url": "https://bcdn.hakunaymatata.com/bt/360.mp4?sign=a", "codecName": "hevc", "headers": {}},
                  "720": {"type": "mp4", "url": "https://bcdn.hakunaymatata.com/resource/h265/720.mp4?sign=b", "codecName": "hevc", "headers": {}},
                  "1080": {"type": "mp4", "url": "https://bcdn.hakunaymatata.com/resource/1080.mp4?sign=c", "codecName": "h264", "headers": {}}
                },
                "captions": [
                  {"id": "1", "url": "https://cacdn.hakunaymatata.com/sub/hi.srt", "language": "हिन्दी", "type": "srt"},
                  {"id": "2", "url": "https://cacdn.hakunaymatata.com/sub/en.srt", "language": "English", "type": "srt"}
                ]
              }
            }
        """.trimIndent()

        val sources = VidLinkClient.parseResponse(json)
        assertTrue("Expected MovieBox variants for each quality", sources.size >= 6)
        // H.264 1080p must rank ahead of HEVC 720p/360p, starting with the bcdnxw mirror
        val first = sources.first()
        assertEquals(FrostProvider.OG, first.provider)
        assertEquals("1080p", first.quality)
        assertEquals(false, first.hevc)
        assertTrue(first.url.startsWith("https://bcdnxw.hakunaymatata.com/resource/1080.mp4"))
        assertEquals(VidLinkClient.MOVIEBOX_REFERER, first.headers["referer"])
        // Never leak vidlink.pro or tgfrost Referer onto *.hakunaymatata.com
        sources.forEach { src ->
            val ref = src.headers["referer"].orEmpty()
            assertFalse("Forbidden Referer on MovieBox CDN: $ref", ref.contains("vidlink.pro") || ref.contains("tgfrost"))
        }
        // Subtitles parsed with English marked default and Hindi tagged 'hi'
        assertEquals(2, first.subtitles.size)
        val en = first.subtitles.first { it.language == "en" }
        val hi = first.subtitles.first { it.language == "hi" }
        assertTrue(en.default)
        assertEquals("application/x-subrip", en.mime)
        assertFalse(hi.default)
    }

    @Test
    fun `falls back to enc-dec token endpoint when primary token is rejected`() {
        MockWebServer().use { vidlink ->
            MockWebServer().use { encDec ->
                vidlink.enqueue(MockResponse().setResponseCode(400))
                encDec.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"status":200,"result":"rotated-server-token"}""")
                )
                vidlink.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"sourceId":"storm","stream":{"type":"hls","playlist":"https://storm.vodvidl.site/proxy/master.m3u8","captions":[]}}"""
                        )
                )

                val client = VidLinkClient(baseUrl = vidlink.url("/"), encDecUrl = encDec.url("/api/enc-vidlink"))
                val sources = client.resolve(FrostTitle(1381071, "movie"))
                assertEquals(1, sources.size)
                assertEquals(VodFormat.HLS, sources.single().format)
                assertEquals("https://storm.vodvidl.site/proxy/master.m3u8", sources.single().url)

                val firstReq = vidlink.takeRequest(2, TimeUnit.SECONDS)!!
                assertTrue(firstReq.path!!.startsWith("/api/b/movie/"))
                val encReq = encDec.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/api/enc-vidlink?text=1381071", encReq.path)
                val secondReq = vidlink.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/api/b/movie/rotated-server-token?multiLang=1", secondReq.path)
            }
        }
    }

    @Test
    fun `platform shelves cover major streaming services and regional catalogues`() {
        val titles = Movies.PLATFORM_SHELVES.map { it.title }
        assertTrue(titles.any { it.contains("Netflix") })
        assertTrue(titles.any { it.contains("Prime Video") })
        assertTrue(titles.any { it.contains("Disney+ Hotstar") })
        assertTrue(titles.any { it.contains("Crunchyroll") })
        assertTrue(titles.any { it.contains("Indian Cinema") })
    }

    @Test
    fun `1080p H264 ranks ahead of multi-gigabyte 2160p progressive MP4 while keeping 2160p selectable`() {
        val json = """
            {
              "sourceId": "mwVault",
              "stream": {
                "id": "primary",
                "type": "file",
                "qualities": {
                  "2160": {"type": "mp4", "url": "https://bcdn.hakunaymatata.com/resource/2160.mp4?sign=4k", "codecName": "h264", "headers": {}},
                  "1080": {"type": "mp4", "url": "https://bcdn.hakunaymatata.com/resource/1080.mp4?sign=fhd", "codecName": "h264", "headers": {}},
                  "480": {"type": "mp4", "url": "https://bcdn.hakunaymatata.com/resource/480.mp4?sign=sd", "codecName": "h264", "headers": {}}
                },
                "captions": []
              }
            }
        """.trimIndent()

        val sources = VidLinkClient.parseResponse(json)
        assertEquals("1080p", sources.first().quality)
        assertTrue("2160p must remain selectable in sources list", sources.any { it.quality == "2160p" })
    }

    @Test
    fun `Look at Me XXXTentacion prefers faststart resource 720p native UA and trims 12 captions`() {
        val json = """
            {
              "sourceId": "mwVault",
              "stream": {
                "id": "primary",
                "type": "file",
                "qualities": {
                  "480": {"type": "mp4", "url": "https://bcdn.hakunaymatata.com/resource/705d1630.mp4?sign=sd", "size": "535830875", "codecName": "h264"},
                  "720": {"type": "mp4", "url": "https://bcdn.hakunaymatata.com/resource/dfd32731.mp4?sign=hd", "size": "722381126", "codecName": "h264"},
                  "1080": {"type": "mp4", "url": "https://bcdn.hakunaymatata.com/extto/adf88ecd.mp4?sign=fhd", "size": "2245442970", "codecName": "h264"}
                },
                "captions": [
                  {"id": "1", "url": "https://cacdn.hakunaymatata.com/sub/ar.srt", "language": "Arabic", "type": "srt"},
                  {"id": "2", "url": "https://cacdn.hakunaymatata.com/sub/bn.srt", "language": "Bengali", "type": "srt"},
                  {"id": "3", "url": "https://cacdn.hakunaymatata.com/sub/en.srt", "language": "English", "type": "srt"},
                  {"id": "4", "url": "https://cacdn.hakunaymatata.com/sub/fil.srt", "language": "Filipino", "type": "srt"},
                  {"id": "5", "url": "https://cacdn.hakunaymatata.com/sub/fr.srt", "language": "French", "type": "srt"},
                  {"id": "6", "url": "https://cacdn.hakunaymatata.com/sub/ha.srt", "language": "Hausa", "type": "srt"},
                  {"id": "7", "url": "https://cacdn.hakunaymatata.com/sub/id.srt", "language": "Indonesian", "type": "srt"},
                  {"id": "8", "url": "https://cacdn.hakunaymatata.com/sub/pa.srt", "language": "Punjabi", "type": "srt"},
                  {"id": "9", "url": "https://cacdn.hakunaymatata.com/sub/pt.srt", "language": "Portuguese", "type": "srt"},
                  {"id": "10", "url": "https://cacdn.hakunaymatata.com/sub/ru.srt", "language": "Russian", "type": "srt"},
                  {"id": "11", "url": "https://cacdn.hakunaymatata.com/sub/sw.srt", "language": "Swahili", "type": "srt"},
                  {"id": "12", "url": "https://cacdn.hakunaymatata.com/sub/ur.srt", "language": "Urdu", "type": "srt"}
                ]
              }
            }
        """.trimIndent()

        val sources = VidLinkClient.parseResponse(json)
        val first = sources.first()
        assertEquals("720p", first.quality)
        assertTrue(first.url.startsWith("https://bcdnxw.hakunaymatata.com/resource/dfd32731.mp4"))
        assertEquals(VidLinkClient.MOVIEBOX_USER_AGENT, first.headers["User-Agent"])
        assertEquals(1, first.subtitles.size)
        assertEquals("en", first.subtitles.single().language)
        assertTrue("Heavy 2.25 GB 1080p /extto/ remains selectable", sources.any { it.quality == "1080p" })
    }
}
