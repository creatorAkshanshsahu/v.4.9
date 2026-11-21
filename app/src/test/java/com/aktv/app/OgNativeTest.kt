package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * theogpiratebot.online's servers are played natively by reading the media address each server's page names
 * for a title. These cases pin what may be read (a real media address, one iframe hop) and what may not be
 * played (a DASH manifest that means Widevine, a plain http address, an embed page).
 */
class OgNativeTest {

    private val movie = FrostTitle(934042, "movie")
    private val episode = FrostTitle(1396, "tv", 2, 5)

    @Test fun `each server is asked for the title in its own scheme`() {
        val servers = OgNative.SERVERS.associateBy { it.key }
        assertEquals("https://vidstuck.xyz/embed/movie/934042", servers.getValue("vidstuck").embed(movie))
        assertEquals(
            "https://vidstuck.xyz/embed/tv/1396/2/5",
            servers.getValue("vidstuck").embed(episode)
        )
        assertEquals(
            "https://vidfast.pro/tv/1396/2/5?autoPlay=true",
            servers.getValue("vidfast").embed(episode)
        )
        assertEquals("https://vidlink.pro/movie/934042", servers.getValue("vidlink").embed(movie))
        assertEquals(3, OgNative.SERVERS.size)
        assertNull(OgNative.server("unknown"))
    }

    @Test fun `the media address a page names is read, escaped slashes included`() {
        val page = "https://vidstuck.xyz/embed/movie/934042"
        val json = """{"sources":[{"file":"https:\/\/cdn.example.test\/hls\/index.m3u8?token=abc"}]}"""
        assertEquals(listOf("https://cdn.example.test/hls/index.m3u8?token=abc"), OgNative.candidates(json, page))
        val hls = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example.test/v/720.m3u8\n"
        assertEquals(listOf("https://cdn.example.test/v/720.m3u8"), OgNative.candidates(hls, page))
        // Duplicates collapse, and the order the page names them in is kept.
        val twice = "https://cdn.example.test/a.mp4 https://cdn.example.test/a.mp4 https://cdn.example.test/b.m3u8"
        assertEquals(listOf("https://cdn.example.test/a.mp4", "https://cdn.example.test/b.m3u8"),
            OgNative.candidates(twice, page))
    }

    @Test fun `an address the native player must not open is never offered`() {
        val page = "https://vidstuck.xyz/embed/movie/934042"
        // A DASH manifest from these hosts means Widevine; a plain http address is not fetched at all.
        assertEquals(emptyList<String>(), OgNative.candidates(
            "https://cdn.example.test/manifest.mpd http://cdn.example.test/v.mp4", page))
        // An embed page is a page, not media.
        assertNull(OgNative.format("https://vidstuck.xyz/embed/movie/934042"))
        assertNull(OgNative.format("https://cdn.example.test/manifest.mpd"))
        assertEquals(VodFormat.HLS, OgNative.format("https://cdn.example.test/x.m3u8?token=1"))
        assertEquals(VodFormat.MP4, OgNative.format("https://cdn.example.test/x.MP4"))
    }

    @Test fun `one iframe hop is read, and only over https`() {
        assertNull(OgNative.iframe("<div>no player</div>"))
        assertEquals(
            "https://inner.example.test/e/1",
            OgNative.iframe("""<iframe src="https://inner.example.test/e/1" allowfullscreen></iframe>""")
        )
        // An iframe that is not https can never be followed.
        assertNull(OgNative.iframe("""<iframe src="http://inner.example.test/e/1"></iframe>"""))
    }

    @Test fun `a server that wraps another is read through the iframe`() {
        val server = OgNative.server("vidstuck")!!
        val pages = mapOf(
            "https://vidstuck.xyz/embed/movie/934042" to """<iframe src="https://inner.example.test/e/1"></iframe>""",
            "https://inner.example.test/e/1" to """{"file":"https://cdn.example.test/hls/index.m3u8?token=x"}""",
        )
        val sources = OgNative.sources(movie, server) { pages[it] }
        assertEquals(1, sources.size)
        val only = sources.single()
        assertEquals(FrostProvider.OG, only.provider)
        assertEquals("https://cdn.example.test/hls/index.m3u8?token=x", only.url)
        assertEquals(VodFormat.HLS, only.format)
        assertTrue(only.label.contains("OG Vidstuck"))
        // The media host is asked for the stream with a browser identity, and with the page that named
        // the address as the Referer - that page, not the site root, is what the CDN was serving.
        assertEquals(TgHttp.USER_AGENT, only.headers["User-Agent"])
        assertEquals("https://inner.example.test/e/1", only.headers["Referer"])
        assertEquals("https://inner.example.test", only.headers["Origin"])
    }

    @Test fun `the site's own embed is addressed the way its documentation publishes`() {
        assertEquals("https://embed.theogpiratebot.online/embed/movie/934042", OgNative.embedUrl(movie))
        assertEquals("https://embed.theogpiratebot.online/embed/tv/1396/2/5", OgNative.embedUrl(episode))
    }

    @Test fun `a server with nothing to play contributes nothing`() {
        val server = OgNative.server("vidfast")!!
        assertTrue(OgNative.sources(movie, server) { null }.isEmpty())
        assertTrue(OgNative.sources(movie, server) { "<html>not found</html>" }.isEmpty())
    }
}
