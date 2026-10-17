package com.aktv.app

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class FrostProtocolTest {
    private fun source(url: String = "https://media.example.test/play?token=synthetic", type: String = "dash") =
        JSONObject().put("id", "moviebox-source").put("type", type).put("url", url).put("quality", "1080p").put("language", "English")
    private fun parse(vararg entries: JSONObject) = FrostProtocol.parse(JSONObject().put("sources", JSONArray(entries.toList())).toString(), FrostProvider.FROST)

    @Test fun `movie lookup uses the observed resolver contract without episode parameters`() {
        val url = FrostProtocol.endpoint(FrostProtocol.SITE.toHttpUrl(), FrostProvider.FROST, FrostTitle(1288069, "movie"))
        assertEquals("/api/sources", url.encodedPath)
        assertEquals("tgflix", url.queryParameter("server"))
        assertEquals("movie", url.queryParameter("type"))
        assertEquals("1288069", url.queryParameter("tmdb_id"))
        assertNull(url.queryParameter("season"))
        assertNull(url.queryParameter("episode"))
    }
    @Test fun `series lookup retains exact season and episode including provider`() {
        val url = FrostProtocol.endpoint(FrostProtocol.SITE.toHttpUrl(), FrostProvider.OUT, FrostTitle(213562, "tv", 2, 3))
        assertEquals("tgout", url.queryParameter("server"))
        assertEquals("tv", url.queryParameter("type"))
        assertEquals("2", url.queryParameter("season"))
        assertEquals("3", url.queryParameter("episode"))
    }
    @Test fun `invalid identity and invented episode defaults are rejected`() {
        for (action in listOf<() -> Any>({ FrostTitle(0, "movie") }, { FrostTitle(1, "anime") },
            { FrostTitle(1, "tv") }, { FrostTitle(1, "movie", 1, 1) }, { FrostTitle(1, "tv", 1, 0) })) {
            assertThrows(IllegalArgumentException::class.java) { action() }
        }
    }
    @Test fun `extensionless DASH and HLS use explicit metadata MIME rather than file guessing`() {
        val dash = parse(source()).single()
        assertEquals(VodFormat.DASH, dash.format)
        assertEquals("application/dash+xml", dash.format.mime)
        assertEquals("https://media.example.test/play?token=synthetic", dash.url)
        val hls = parse(source(type = "hls")).single()
        assertEquals("application/x-mpegURL", hls.format.mime)
    }
    @Test fun `source order prefers adaptive relay sources over direct MP4 without losing alternatives`() {
        val sources = parse(source("https://media.example.test/file.mp4", "mp4"), source("https://relay.example.test/proxy?url=synthetic", "dash"))
        assertEquals(listOf(VodFormat.DASH, VodFormat.MP4), sources.map { it.format })
    }
    @Test fun `missing HEVC flag is unknown not a claim of AVC support`() {
        assertNull(parse(source()).single().hevc)
        assertEquals(true, parse(source().put("hevc", true)).single().hevc)
        assertEquals(false, parse(source().put("hevc", false)).single().hevc)
    }
    @Test fun `mirror order and signed query encoding are retained`() {
        val item = source("https://media.example.test/proxy?url=https%3A%2F%2Fcdn.example.test%2Fa.mpd%3Fsig%3Da%252Fb")
            .put("mirrors", JSONArray().put("https://mirror.example.test/a.mpd?token=other").put("javascript:alert(1)"))
        val sources = parse(item)
        assertEquals(2, sources.size)
        assertTrue(sources.first().url.contains("sig%3Da%252Fb"))
        assertEquals("https://mirror.example.test/a.mpd?token=other", sources.last().url)
    }
    @Test fun `duplicate urls with different languages are retained`() {
        val sources = parse(source(), source().put("language", "Hindi"))
        assertEquals(2, sources.size)
        assertNotEquals(sources[0].identity, sources[1].identity)
    }
    @Test fun `unsafe and browser-only URLs never become native media`() {
        for (url in listOf("blob:https://example.test/id", "data:video/mp4,abc", "file:///etc/passwd", "http://cdn.example.test/v.mp4",
            "https://localhost/v.mp4", "https://app.local/v.mp4", "https://[::1]/v.mp4", "https://127.0.0.1/v.mp4",
            "https://user:secret@cdn.example.test/v.mp4", "https://tgfrost.lovable.app/embed/movie/1", "https://vidbolt.xyz/watch/movie/1")) {
            assertNull(url, FrostProtocol.mediaUrl(url))
        }
        assertEquals("https://tgfrost.lovable.app/api/proxy/stream?url=synthetic", FrostProtocol.mediaUrl("/api/proxy/stream?url=synthetic"))
    }
    @Test fun `unknown format is not inferred from the label of an HTML embed`() {
        assertThrows(FrostResolveException::class.java) { parse(source(type = "iframe")) }
        assertThrows(FrostResolveException::class.java) { parse(source().put("kind", "embed")) }
        assertThrows(FrostResolveException::class.java) { parse(source(type = "")) }
        assertEquals(VodFormat.MP4, parse(source("https://media.example.test/file.mp4?sig=x", "")).single().format)
    }
    @Test fun `explicit unknown DRM is not replaced with a guessed licence or another source keys`() {
        assertThrows(FrostResolveException::class.java) { parse(source().put("drm", JSONObject().put("type", "widevine"))) }
    }
    @Test fun `header names are constrained and request injection is rejected`() {
        val result = parse(source().put("headers", JSONObject().put("Origin", "https://provider.example.test")
            .put("Authorization", "Bearer synthetic").put("Cookie", "sid=synthetic").put("X-Provider", "synthetic")
            .put("Host", "injected.example.test").put("Range", "bytes=0-").put("Referer", "bad\r\nHeader: bad"))).single()
        assertEquals(setOf("origin", "authorization", "cookie", "x-provider"), result.headers.keys)
    }
    @Test fun `subtitles map file url language and format but thumbnails and unknown HTML are not tracks`() {
        val tracks = JSONArray()
            .put(JSONObject().put("file", "https://subs.example.test/en.vtt?token=secret").put("kind", "captions").put("label", "English").put("language", "en").put("default", true))
            .put(JSONObject().put("url", "https://subs.example.test/api?lang=hi").put("format", "srt").put("lang", "hi"))
            .put(JSONObject().put("file", "https://subs.example.test/preview.vtt").put("kind", "thumbnails"))
            .put(JSONObject().put("file", "https://subs.example.test/page"))
        val subs = parse(source().put("tracks", tracks)).single().subtitles
        assertEquals(listOf("text/vtt", "application/x-subrip"), subs.map { it.mime })
        assertEquals(listOf("en", "hi"), subs.map { it.language })
        assertTrue(subs.first().default)
        assertFalse(subs.first().toString().contains("secret"))
    }
    @Test fun `resolver source diagnostics do not stringify signed urls or headers`() {
        val result = parse(source().put("headers", JSONObject().put("Authorization", "secret"))).single()
        assertFalse(result.toString().contains("synthetic"))
        assertFalse(result.toString().contains("secret"))
        assertFalse(FrostResolveException(403).message.orEmpty().contains("http://"))
    }
    @Test fun `empty malformed and oversized metadata fail with safe typed errors`() {
        assertThrows(FrostResolveException::class.java) { FrostProtocol.parse("not json", FrostProvider.FROST) }
        assertThrows(FrostResolveException::class.java) { FrostProtocol.parse("{}", FrostProvider.FROST) }
        assertThrows(FrostResolveException::class.java) { parse() }
        assertThrows(FrostResolveException::class.java) { FrostProtocol.readBounded(ByteArrayInputStream(ByteArray(21)), 20) }
        assertEquals(20, FrostProtocol.readBounded(ByteArrayInputStream(ByteArray(20)), 20).size)
    }
    @Test fun `resume is episode-specific skips tiny and completed positions and caps corrupt input`() {
        assertNotEquals(FrostTitle(1, "tv", 1, 1).progressKey, FrostTitle(1, "tv", 1, 2).progressKey)
        assertNotEquals(FrostTitle(1, "movie").progressKey, FrostTitle(1, "tv", 1, 1).progressKey)
        assertEquals(0L, VodProgress.resume(5_000, 200_000))
        assertEquals(60_000L, VodProgress.resume(60_000, 200_000))
        assertEquals(0L, VodProgress.resume(180_000, 200_000))
        assertEquals(24 * 3600_000L, VodProgress.resume(Long.MAX_VALUE, 0))
    }
    @Test fun `episode plan validates deduplicates sorts and crosses seasons only from known metadata`() {
        val episodes = VodProgress.episodes(intArrayOf(2, 1, 1, 2, 0, 2, 1, 1, 1, 2, 9))
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), episodes)
        assertEquals(FrostTitle(1, "tv", 2, 1), VodProgress.next(FrostTitle(1, "tv", 1, 2), episodes))
        assertNull(VodProgress.next(FrostTitle(1, "tv", 2, 1), episodes))
        assertNull(VodProgress.next(FrostTitle(1, "tv", 9, 1), episodes))
        assertNull(VodProgress.next(FrostTitle(1, "movie"), episodes))
    }
    @Test fun `recovery visits each URL once and never spins on unchanged refreshed metadata`() {
        val source = parse(source()).single()
        val recovery = VodRecovery()
        recovery.visit(FrostProvider.FROST)
        assertTrue(recovery.take(source)); assertFalse(recovery.take(source))
        assertTrue(recovery.refresh(FrostProvider.FROST, 403)); assertFalse(recovery.refresh(FrostProvider.FROST, 403))
        assertFalse(recovery.refresh(FrostProvider.OUT, 451))
        assertEquals(FrostProvider.OUT, recovery.alternative())
        recovery.visit(FrostProvider.OUT)
        assertNull(recovery.alternative())
        recovery.reset(); assertTrue(recovery.take(source))
    }
    @Test fun `recovery has a hard source-attempt ceiling even if server endlessly changes URLs`() {
        val recovery = VodRecovery()
        for (i in 1..16) assertTrue(recovery.take(parse(source("https://media.example.test/$i.mpd")).single()))
        assertFalse(recovery.take(parse(source("https://media.example.test/17.mpd")).single()))
        assertTrue(recovery.exhausted()); assertNull(recovery.alternative())
    }
    @Test fun `observed extensionless Frost subtitle service uses WebVTT without guessing other hosts`() {
        val tracks = JSONArray().put(JSONObject().put("url", "https://embed.reelsdownload.online/subtitle?token=synthetic")
            .put("label", "English").put("lang", "en"))
        val subs = parse(source().put("tracks", tracks)).single().subtitles
        assertEquals(1, subs.size)
        assertEquals("text/vtt", subs.single().mime)
        assertEquals("en", subs.single().language)
    }

    @Test fun `a fresh provider header on an unchanged URL permits one genuinely new request`() {
        val first = parse(source().put("headers", JSONObject().put("Authorization", "Bearer first"))).single()
        val second = parse(source().put("headers", JSONObject().put("Authorization", "Bearer renewed"))).single()
        val recovery = VodRecovery()
        assertTrue(recovery.take(first)); assertFalse(recovery.take(first))
        assertTrue(recovery.take(second)); assertFalse(recovery.take(second))
    }

}
