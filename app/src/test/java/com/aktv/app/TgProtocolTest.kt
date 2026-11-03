package com.aktv.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class TgProtocolTest {
    private val page = "https://livetgtv.lovable.app/watch/872"
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/tg/$name")).use { it.readBytes() }
    private fun parse(json: String) = TgProtocol.streams(json.toByteArray(), "application/json", page)

    @Test fun `plain player configuration keeps server order and isolated credentials`() {
        val streams = TgProtocol.streams(fixture("player-config.json"), "application/json", page)
        assertEquals(3, streams.size)
        assertEquals(listOf("Server 1", "Server 2", "Server 3"), streams.map { it.label })
        assertTrue(streams[0].cookie.contains("__hdnea__="))
        assertEquals("", streams[1].cookie)
        assertEquals("https://relay.example/play?channel=872&signature=a%2Bb", streams[1].url)
        assertNull(streams[2].drm)
        assertEquals("hls", streams[2].type)
    }

    @Test fun `real Seroval wrapper and cross JSON decode shared DRM references`() {
        for (name in listOf("player-config.seroval.json", "player-config.cross.json")) {
            val streams = TgProtocol.streams(fixture(name), "application/json", page)
            assertEquals(name, 3, streams.size)
            val primary = requireNotNull(streams[0].drm)
            assertEquals(2, ClearKey.pairs(primary).size)
            assertEquals(ClearKey.pairs(primary), ClearKey.pairs(requireNotNull(streams[1].drm)))
            assertNull(streams[2].drm)
        }
    }

    @Test fun `public sources inherit their own root DRM and never artwork`() {
        val streams = parse("""{
          "id":"872", "name":"Sony Yay Hindi", "logo":"https://media.example/logo.png",
          "watch":"https://livetgtv.lovable.app/watch/872", "embed":"https://livetgtv.lovable.app/embed/872",
          "sources":["https://media.example/a.mpd", "https://media.example/b.m3u8"],
          "drm":{"type":"clearkey","keyId":"00112233445566778899aabbccddeeff","key":"102132435465768798a9bacbdcedfe0f"}
        }""")
        assertEquals(2, streams.size)
        assertEquals(listOf("dash", "hls"), streams.map { it.type })
        assertTrue(streams.all { ClearKey.license(requireNotNull(it.drm)) != null })
    }

    @Test fun `missing DRM stays missing rather than borrowing keys from another server`() {
        val streams = parse("""{"primary":{"url":"https://media.example/a.mpd","drm":null},
          "backups":[{"url":"https://relay.example/b.mpd","keyId":"00112233445566778899aabbccddeeff","key":"102132435465768798a9bacbdcedfe0f"}]}""")
        assertNull(streams[0].drm)
        assertNotNull(streams[1].drm)
    }

    @Test fun `top level primary auth does not become backup auth`() {
        val streams = parse("""{"url":"https://media.example/a.mpd", "cookie":"__hdnea__=primary",
          "headers":{"Authorization":"primary-only"},
          "backups":[{"url":"https://relay.example/b.mpd"}]}""")
        assertEquals("", streams[1].cookie)
        assertTrue(streams[1].headers.isEmpty())
    }

    @Test fun `explicit common configuration can apply to a server group without borrowing sibling keys`() {
        val streams = parse("""{"drm":{"type":"clearkey","keyId":"00112233445566778899aabbccddeeff","key":"102132435465768798a9bacbdcedfe0f"},
          "headers":{"User-Agent":"TG current player"},
          "servers":[{"url":"https://media.example/a.mpd"},{"url":"https://relay.example/b.m3u8","drm":null}]}""")
        assertNotNull(streams[0].drm)
        assertNull(streams[1].drm)
        assertTrue(streams.all { it.headers["User-Agent"] == "TG current player" })
    }

    @Test fun `null source DRM overrides a shared root DRM`() {
        val streams = parse("""{"drm":{"type":"clearkey","keyId":"00112233445566778899aabbccddeeff","key":"102132435465768798a9bacbdcedfe0f"},
          "sources":[{"url":"https://media.example/a.mpd","drm":null}]}""")
        assertNull(streams.single().drm)
    }

    @Test fun `explicit format wins over a URL or a misleading relay query`() {
        val streams = parse("""{"servers":[
          {"playable":"https://relay.example/proxy?u=video.mpd", "type":"hls"},
          {"src":"/media/a", "mimeType":"application/dash+xml"},
          {"src":"https://media.example/movie.mp4"}]}""")
        assertEquals(listOf("hls", "dash", "mp4"), streams.map { it.type })
        assertEquals("https://livetgtv.lovable.app/media/a", streams[1].url)
    }

    @Test fun `framed TanStack response uses the binary frame length not text lines`() {
        val json = fixture("player-config.cross.json")
        val frame = ByteBuffer.allocate(json.size + 9).put(0).putInt(0).putInt(json.size).put(json).array()
        assertEquals(3, TgProtocol.streams(frame, "application/x-tss-framed; v=1", page).size)
    }

    @Test(expected = IllegalArgumentException::class) fun `truncated frame is rejected`() {
        TgProtocol.streams(ByteBuffer.allocate(9).put(0).putInt(0).putInt(500).array(), "application/x-tss-framed", page)
    }

    @Test(expected = IllegalArgumentException::class) fun `unknown framed protocol version is not silently guessed`() {
        TgProtocol.streams(byteArrayOf(), "application/x-tss-framed; v=2", page)
    }

    @Test fun `all NDJSON records are consumed including a deferred result`() {
        val data = JSONObject(fixture("player-config.cross.json").toString(Charsets.UTF_8))
        val body = """{"t":22,"i":0,"s":1}
          {"t":23,"i":1,"a":[{"t":2,"s":0},$data]}
        """.trimIndent()
        assertEquals(3, TgProtocol.streams(body.toByteArray(), "application/x-ndjson", page).size)
    }

    @Test fun `plain envelope containing a serialized result is supported`() {
        val serialized = fixture("player-config.cross.json").toString(Charsets.UTF_8)
        assertEquals(3, parse("""{"data":$serialized}""").size)
    }

    @Test fun `resolved Seroval promise and relative media URLs are accepted`() {
        val serialized = fixture("player-config.cross.json").toString(Charsets.UTF_8)
        assertEquals(3, parse("""{"t":12,"s":1,"f":$serialized}""").size)
        assertEquals("https://livetgtv.lovable.app/media/live.m3u8", parse("""{"url":"../media/live.m3u8"}""").single().url)
    }

    @Test fun `embedded pages and unsafe URL schemes are not native sources`() {
        val streams = parse("""{"servers":[
          {"type":"iframe","url":"https://player.example/live"},
          {"url":"javascript:alert(1)"}, {"url":"file:///private/file"},
          {"url":"https://media.example/embed/872"}, {"url":"https://media.example/logo.png"},
          {"url":"https://user:pass@media.example/live.mpd"},
          {"url":"https://media.example/real.mpd"}]}""")
        assertEquals(listOf("https://media.example/real.mpd"), streams.map { it.url })
    }

    @Test fun `headers and signed URL bytes survive parsing`() {
        val stream = parse("""{"url":"https://media.example//live/a.mpd?x=%2f%2B&sig=a+b", "headers":{"User-Agent":"TG current player","X-Channel":"872"}}""").single()
        assertEquals("https://media.example//live/a.mpd?x=%2f%2B&sig=a+b", stream.url)
        assertEquals("TG current player", stream.headers["User-Agent"])
    }

    @Test fun `unsupported DRM remains explicit for native error handling`() {
        val stream = parse("""{"url":"https://media.example/a.mpd", "drm":{"type":"widevine","licenseUrl":"https://license.example/acquire"}}""").single()
        assertEquals("widevine", requireNotNull(stream.drm).getString("type"))
        assertNull(ClearKey.license(requireNotNull(stream.drm)))
    }

    @Test fun `ClearKey normalizes UUID hex and base64url JWK without losing keys`() {
        val drm = JSONObject("""{"type":"clearkey","keys":[
          {"keyId":"00112233-4455-6677-8899-aabbccddeeff", "key":"102132435465768798a9bacbdcedfe0f"},
          {"kid":"_-7dzLuqmYh3ZlVEMyIRAA", "k":"ABEiM0RVZneImaq7zN3u_w"}]}""")
        val license = JSONObject(String(requireNotNull(ClearKey.license(drm))))
        assertEquals("temporary", license.getString("type"))
        assertEquals(2, license.getJSONArray("keys").length())
        assertEquals("ABEiM0RVZneImaq7zN3u_w", license.getJSONArray("keys").getJSONObject(0).getString("kid"))
    }

    @Test fun `invalid ClearKey is reported without attempting to hex decode arbitrary text`() {
        assertNull(ClearKey.license(JSONObject("""{"type":"clearkey","keyId":"bad","key":"also bad"}""")))
    }

    @Test fun `deployed flat config keeps URL alternatives together then renumbers advertised servers`() {
        val streams = parse("""{"urls":["https://media.example/first.mpd","https://media.example/second.mpd"],
            "cookie":"__hdnea__=primary", "headers":{"Authorization":"primary-only"},
            "backups":[{"label":"Server 3","urls":["https://relay.example/third.mpd"],"cookie":"__hdnea__=relay","clearKeys":{}},
                {"label":"Server 4","urls":["https://backup.example/fourth.mpd"],"cookie":"__hdnea__=backup","clearKeys":{}}]}""")
        assertEquals(listOf("https://media.example/first.mpd", "https://media.example/second.mpd",
            "https://relay.example/third.mpd", "https://backup.example/fourth.mpd"), streams.map { it.url })
        assertEquals(listOf("Server 1", "Server 1", "Server 2", "Server 3"), streams.map { it.label })
        assertEquals("__hdnea__=relay", streams[2].cookie)
        assertTrue(streams[2].headers.isEmpty())
        assertNull(streams[2].drm)
        assertEquals("__hdnea__=backup", streams[3].cookie)
        assertTrue(streams[3].headers.isEmpty())
        assertNull(streams[3].drm)
    }

    @Test fun `an empty primary list starts at the first advertised native server`() {
        val streams = parse("""{"urls":[], "backups":[{"label":"Server 3","urls":["https://relay.example/live.m3u8"]}]}""")
        assertEquals("Server 1", streams.single().label)
        assertEquals("hls", streams.single().type)
    }

}
