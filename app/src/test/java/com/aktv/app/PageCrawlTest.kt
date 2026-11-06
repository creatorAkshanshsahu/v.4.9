package com.aktv.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pages these movie hosts serve build their player with script, but the address it will play is in
 * what the page sends. These cases pin the three shapes that carry it - a JSON field with escaped
 * slashes, a protocol-relative address, and a player inside a frame - and the one shape that must never
 * be offered: a DASH manifest, which from these hosts means Widevine.
 */
class PageCrawlTest {

    @After fun restore() = PageCrawl.restoreTransport()

    private fun serve(pages: Map<String, String>) {
        PageCrawl.fetcher = { url, _ -> pages[url] }
    }

    @Test fun `a json field with escaped slashes names the stream`() {
        val page = "https://player.example.test/e/1"
        val body = """{"sources":[{"file":"https:\/\/cdn.example.test\/hls\/master.m3u8?token=abc"}]}"""
        assertEquals(listOf("https://cdn.example.test/hls/master.m3u8?token=abc"),
            PageCrawl.media(body, page))
    }

    @Test fun `a protocol relative address is resolved against the page`() {
        val page = "https://player.example.test/e/1"
        val body = """<video src="//cdn.example.test/v/720.mp4?sig=x"></video>"""
        assertEquals(listOf("https://cdn.example.test/v/720.mp4?sig=x"), PageCrawl.media(body, page))
    }

    @Test fun `a player inside a frame is followed once and keeps its own page as the referer`() {
        val outer = "https://player.example.test/e/1"
        val inner = "https://inner.example.test/embed/1"
        serve(mapOf(
            outer to """<html><iframe src="$inner"></iframe></html>""",
            inner to """{"src":"https://cdn.example.test/hls/index.m3u8"}"""
        ))
        val hits = PageCrawl.crawl(outer, "https://site.example.test/")
        assertEquals(1, hits.size)
        assertEquals("https://cdn.example.test/hls/index.m3u8", hits.single().url)
        assertEquals(inner, hits.single().page)
        assertEquals(VodFormat.HLS, hits.single().format)
    }

    @Test fun `a page that answers with the playlist itself has named its stream`() {
        val page = "https://cdn.example.test/live/index.m3u8"
        serve(mapOf(page to "#EXTM3U\n#EXT-X-VERSION:3\n"))
        val hits = PageCrawl.crawl(page, null)
        assertEquals(listOf(page), hits.map { it.url })
    }

    @Test fun `a dash manifest is not offered to this player unless it is asked for`() {
        val page = "https://player.example.test/e/1"
        serve(mapOf(page to """{"file":"https://cdn.example.test/m.mpd"}"""))
        assertTrue(PageCrawl.crawl(page, null).isEmpty())
        PageCrawl.fetcher = { _, _ -> """{"file":"https://cdn.example.test/m.mpd"}""" }
        assertEquals(listOf(VodFormat.DASH), PageCrawl.crawl(page, null, includeDash = true).map { it.format })
    }

    @Test fun `an address that is not media is not a source`() {
        val page = "https://player.example.test/e/1"
        assertEquals(emptyList<String>(), PageCrawl.media("""<a href="https://player.example.test/next">next</a>""", page))
        // Plain http is never fetched, and neither is an opaque scheme.
        assertEquals(emptyList<String>(), PageCrawl.media("http://cdn.example.test/a.m3u8 blob:https://x/y", page))
        assertEquals(VodFormat.DASH, PageCrawl.format("https://cdn.example.test/manifest.mpd"))
    }

    @Test fun `a relative manifest path is resolved against the page`() {
        val page = "https://player.example.test/embed/movie/1"
        val body = """<script>var cfg={"src":"/hls/master.m3u8?sig=1"}</script>"""
        assertEquals(listOf("https://player.example.test/hls/master.m3u8?sig=1"), PageCrawl.media(body, page))
    }

    @Test fun `the playback backend's ticket route is a playlist`() {
        val page = "https://embed.example.test/embed/movie/1423191"
        val body = """<script>window.__P={"sources":[{"url":"/api/v1/stream?t=7f2a9c"}]}</script>"""
        val media = PageCrawl.media(body, page)
        assertEquals(listOf("https://embed.example.test/api/v1/stream?t=7f2a9c"), media)
        // It carries no extension, so the route itself is what says "playlist".
        assertEquals(VodFormat.HLS, PageCrawl.format(media.single()))
        assertTrue(PageCrawl.isTicket(media.single()))
        assertEquals(VodFormat.MP4, PageCrawl.format("https://cdn.example.test/x.mp4"))
    }

    @Test fun `a page that does not answer contributes nothing`() {
        PageCrawl.fetcher = { _, _ -> null }
        assertTrue(PageCrawl.crawl("https://player.example.test/e/1", null, budgetMs = 1_000).isEmpty())
        assertTrue(PageCrawl.crawl("http://player.example.test/e/1", null, budgetMs = 1_000).isEmpty())
    }
}
