package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The portal's answers, as the shapes they actually arrive in. */
class StalkerProtocolTest {

    @Test
    fun `a handshake token is read from the js envelope`() {
        assertEquals("A1B2", StalkerProtocol.token("""{"js":{"token":"A1B2","random":"7"}}"""))
        assertEquals("A1B2", StalkerProtocol.token("""/**/{"js":{"token":"A1B2"}}"""))
        assertNull(StalkerProtocol.token("""{"js":{"error":"Authorization failed."}}"""))
    }

    @Test
    fun `an expired session is recognised so the caller can handshake again`() {
        assertTrue(StalkerProtocol.expired("""{"js":{"not_valid":1}}"""))
        assertTrue(StalkerProtocol.expired("""{"js":{"error":"Authorization failed."}}"""))
        assertFalse(StalkerProtocol.expired("""{"js":{"token":"A1B2"}}"""))
        assertFalse(StalkerProtocol.expired("""{"js":{"data":[]}}"""))
    }

    @Test
    fun `the channel list keeps playable channels and drops rows without a handle`() {
        val body = """{"js":{"total_items":3,"data":[
            {"id":"101","name":"Arena Sports","cmd":"ffmpeg http://cdn/101","tv_genre_id":"5","logo":"/logos/101.png"},
            {"id":"102","name":"Nova News","cmd":"ffmpeg http://cdn/102","tv_genre_id":"6"},
            {"id":"103","name":"Broken","cmd":"","tv_genre_id":"6"}
        ]}}"""
        val list = StalkerProtocol.channels(body)
        assertEquals(2, list.size)
        assertEquals("Arena Sports", list[0].name)
        assertEquals("ffmpeg http://cdn/101", list[0].cmd)
        assertEquals("5", list[0].genre)
        assertEquals("/logos/101.png", list[0].logo)
        assertEquals("102", list[1].id)
    }

    @Test
    fun `genres arrive either bare or under js`() {
        val bare = """[{"id":"5","title":"Sports"},{"id":"6","title":"News"}]"""
        assertEquals(mapOf("5" to "Sports", "6" to "News"), StalkerProtocol.genres(bare))
        val wrapped = """{"js":[{"id":"5","title":"Sports"}]}"""
        assertEquals(mapOf("5" to "Sports"), StalkerProtocol.genres(wrapped))
        assertEquals(emptyMap<String, String>(), StalkerProtocol.genres("""{"js":{"error":"nope"}}"""))
    }

    @Test
    fun `a playable url is cut out of the portal command`() {
        assertEquals("http://cdn/101/index.m3u8",
            StalkerProtocol.streamUrl("ffmpeg http://cdn/101/index.m3u8"))
        assertEquals("http://cdn/101/index.m3u8",
            StalkerProtocol.streamUrl("  ffmpeg http://cdn/101/index.m3u8  "))
        // Kodi-style media parameters are not something the native player can use.
        assertEquals("http://cdn/101/index.m3u8",
            StalkerProtocol.streamUrl("ffmpeg http://cdn/101/index.m3u8|User-Agent=MAG"))
        assertNull(StalkerProtocol.streamUrl("ffmpeg /media/101"))
    }

    @Test
    fun `create_link is unwrapped and its refusal text is readable`() {
        assertEquals("ffmpeg http://cdn/101/index.m3u8",
            StalkerProtocol.link("""{"js":{"cmd":"ffmpeg http://cdn/101/index.m3u8","id":"101"}}"""))
        assertEquals("stream limit reached", StalkerProtocol.error("""{"js":{"error":"stream limit reached"}}"""))
    }
}
