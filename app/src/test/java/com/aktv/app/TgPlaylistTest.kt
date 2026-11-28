package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A playlist tune is only as good as the stream it builds: type, identity, cookie and DRM. */
class TgPlaylistTest {

    private fun entry(
        url: String = "https://cdn.tgtv.example/live/143/index.mpd",
        tvgId: String = "143",
        userAgent: String = "",
        cookie: String = "",
        licenseType: String = "",
        licenseKey: String = ""
    ) = M3uChannel(
        id = M3uPlaylist.id(url), name = "Colors HD", group = "Hindi",
        logo = "", url = url, tvgId = tvgId, userAgent = userAgent,
        cookie = cookie, licenseType = licenseType, licenseKey = licenseKey
    )

    @Test
    fun `media type comes from the address extension, query aside`() {
        assertEquals("hls", TgPlaylist.mediaType("https://cdn/a/index.m3u8"))
        assertEquals("hls", TgPlaylist.mediaType("https://cdn/a/index.m3u8?token=x"))
        assertEquals("mp4", TgPlaylist.mediaType("https://cdn/a/clip.mp4"))
        assertEquals("dash", TgPlaylist.mediaType("https://cdn/a/index.mpd"))
        assertEquals("dash", TgPlaylist.mediaType("https://cdn/a/live?id=5"))
    }

    @Test
    fun `only a same-host https key document is followed`() {
        val same = entry(licenseKey = "https://livetgtv.lovable.app/api/key/143")
        assertEquals("https://livetgtv.lovable.app/api/key/143", TgPlaylist.licenseUrl(same))
        assertNull(TgPlaylist.licenseUrl(entry(licenseKey = "https://keys.evil.example/k")))
        assertNull(TgPlaylist.licenseUrl(entry(licenseKey = "http://livetgtv.lovable.app/api/key/143")))
        assertNull(TgPlaylist.licenseUrl(entry(licenseKey = "00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100")))
        assertNull(TgPlaylist.licenseUrl(entry(licenseKey = "https://livetgtv.lovable.app/api/key/143#frag")))
        assertNull(TgPlaylist.licenseUrl(entry()))
    }

    @Test
    fun `inline keys become a clearkey document the player can license`() {
        val drm = TgPlaylist.buildDrm(
            entry(
                licenseType = "clearkey",
                licenseKey = "00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100"
            )
        )
        val keyed = requireNotNull(drm)
        assertEquals("clearkey", keyed.optString("type"))
        assertEquals(1, ClearKey.pairs(keyed).size)
    }

    @Test
    fun `a key-document url is passed through for the player to read itself`() {
        val drm = TgPlaylist.buildDrm(
            entry(
                licenseType = "clearkey",
                licenseKey = "https://livetgtv.lovable.app/api/key/143"
            )
        )
        val keyed = requireNotNull(drm)
        assertEquals("clearkey", keyed.optString("type"))
        assertEquals("https://livetgtv.lovable.app/api/key/143", keyed.optString("licenseUrl"))
        assertTrue(ClearKey.pairs(keyed).isEmpty())
    }

    @Test
    fun `unknown licence types and bare entries carry no drm`() {
        assertNull(TgPlaylist.buildDrm(entry(licenseType = "widevine", licenseKey = "https://x.example/lic")))
        assertNull(TgPlaylist.buildDrm(entry()))
    }

    @Test
    fun `the stream carries the playlist identity, cookie and drm, and no browser origin`() {
        val stream = TgPlaylist.toStream(
            entry(
                userAgent = "TGPlayer",
                cookie = "__hdnea__=st=1~exp=2~acl=/*~hmac=abc",
                licenseType = "clearkey",
                licenseKey = "https://livetgtv.lovable.app/api/key/143"
            )
        )
        assertEquals("https://cdn.tgtv.example/live/143/index.mpd", stream.url)
        assertEquals("dash", stream.type)
        assertEquals("TGPlayer", stream.headers["User-Agent"])
        assertEquals("__hdnea__=st=1~exp=2~acl=/*~hmac=abc", stream.cookie)
        assertEquals("https://livetgtv.lovable.app/api/key/143", stream.drm?.optString("licenseUrl"))
        assertEquals(TgResolver.SITE, stream.requestOrigin)
        assertTrue(stream.omitReferrer)
        assertTrue(stream.omitOrigin)
    }

    @Test
    fun `a bare entry still plays exactly as listed, with the app user agent`() {
        val stream = TgPlaylist.toStream(entry())
        assertEquals(TgHttp.USER_AGENT, stream.headers["User-Agent"])
        assertEquals("", stream.cookie)
        assertNull(stream.drm)
    }

    @Test
    fun `only entries that name a channel earn the metadata fallback`() {
        assertEquals(listOf(LiveProvider.TG, LiveProvider.JIOM3U), TgPlaylist.fallbackProviders("892"))
        assertEquals(listOf(LiveProvider.TG), TgPlaylist.fallbackProviders("sports-hd"))
        assertTrue(TgPlaylist.fallbackProviders("").isEmpty())
        assertTrue(TgPlaylist.fallbackProviders("not a channel?!").isEmpty())
    }
}
