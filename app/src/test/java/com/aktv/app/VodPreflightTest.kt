package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPreflightTest {
    private fun source(label: String, format: VodFormat = VodFormat.DASH) = FrostSource(
        FrostProvider.FROST, label, "https://relay.example.test/$label.mpd", format,
        "1080p", "English", null, emptyMap(), emptyList()
    )

    private val first = source("first")
    private val second = source("second")
    private val third = source("third", VodFormat.MP4)

    @Test fun `a verified source starts before an unverified one without dropping either`() {
        val ranked = VodPreflight.rank(listOf(first, second, third), setOf("third"))
        assertEquals(listOf("third", "first", "second"), ranked.map { it.identity })
        assertEquals(3, ranked.size)
    }

    @Test fun `order inside each group follows the provider list`() {
        val ranked = VodPreflight.rank(listOf(first, second, third), setOf("second", "third"))
        assertEquals(listOf("second", "third", "first"), ranked.map { it.identity })
    }

    @Test fun `no probe evidence leaves the provider order exactly as it was`() {
        val sources = listOf(first, second, third)
        assertSame(sources, VodPreflight.rank(sources, emptySet()))
    }

    @Test fun `unknown identities cannot disturb the list`() {
        val sources = listOf(first, second)
        assertEquals(listOf("first", "second"), VodPreflight.rank(sources, setOf("not-in-list")).map { it.identity })
    }

    @Test fun `a DASH or HLS document body counts as reachable`() {
        assertTrue(VodPreflight.looksLikeManifest("application/octet-stream", "<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"))
        assertTrue(VodPreflight.looksLikeManifest("text/plain", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000"))
        assertTrue(VodPreflight.looksLikeManifest("application/dash+xml", ""))
        assertTrue(VodPreflight.looksLikeManifest("application/vnd.apple.mpegurl", ""))
        assertTrue(VodPreflight.looksLikeManifest("video/mp4", ""))
    }

    @Test fun `an error page or an API error body is not a playable source`() {
        assertFalse(VodPreflight.looksLikeManifest("text/html", "<!DOCTYPE html><html><body>403 forbidden</body></html>"))
        assertFalse(VodPreflight.looksLikeManifest("application/json", "{\"error\":\"not found\"}"))
        assertFalse(VodPreflight.looksLikeManifest("", ""))
    }

    @Test fun `a sprite-sheet playlist is not a video source`() {
        // Shape taken from the live service: a real #EXTM3U whose media lines are seek-bar thumbnails.
        val sprites = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n" +
            (0..5).joinToString("") { "#EXTINF:10.4,\nhttps://img.fcxmb.com/hls/abc/720/000$it.jpg?t=1792168241\n" }
        assertTrue(VodPreflight.isImagePlaylist(sprites))
        assertFalse(VodPreflight.looksLikeManifest("application/vnd.apple.mpegurl", sprites))
        val video = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10.4,\nseg0.ts\n#EXTINF:9.1,\nseg1.ts\n"
        assertFalse(VodPreflight.isImagePlaylist(video))
        assertTrue(VodPreflight.looksLikeManifest("application/vnd.apple.mpegurl", video))
    }

    @Test fun `a DASH source is checked against the first segment it names`() {
        val dollar = "${'$'}"
        // Trimmed from the live tgflix manifest: static, HEVC, absolute signed segments and a relay BaseURL.
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT2H19M8.3S">
            <BaseURL>https://relay.example.test/t/eyJkaXIiOiJodHRwczovL2hvbGx5LmV4YW1wbGUudGVzdC9wLyJ9/</BaseURL>
            <Period><AdaptationSet contentType="video">
            <Representation id="0" mimeType="video/mp4" codecs="hev1.1.6.L150.90">
            <SegmentTemplate timescale="1000000" duration="5000000"
              initialization="https://cdn.example.test/dash/abc/init-stream${dollar}RepresentationID${dollar}.m4s?Policy=p1&amp;Signature=s1&amp;Key-Pair-Id=k1"
              media="https://cdn.example.test/dash/abc/chunk-stream${dollar}RepresentationID${dollar}-${dollar}Number%05d${dollar}.m4s?Policy=p1&amp;Signature=s1"
              startNumber="1"/>
            </Representation></AdaptationSet></Period></MPD>
        """.trimIndent()
        val targets = VodPreflight.manifestTargets(VodFormat.DASH, "https://relay.example.test/?url=x", manifest)
        assertEquals(listOf("https://cdn.example.test/dash/abc/init-stream0.m4s?Policy=p1&Signature=s1&Key-Pair-Id=k1"), targets)
        // The BaseURL is never probe evidence by itself: a manifest with no template names no resource.
        assertTrue(VodPreflight.manifestTargets(VodFormat.DASH, "https://relay.example.test/?url=x",
            "<MPD><BaseURL>https://relay.example.test/t/abc/</BaseURL></MPD>").isEmpty())
        assertTrue(VodPreflight.manifestTargets(VodFormat.MP4, "https://cdn.example.test/a.mp4", "").isEmpty())
    }

    @Test fun `an HLS source is checked against its first segment or its init map`() {
        assertEquals(listOf("https://cdn.example.test/hls/720/0000.ts"),
            VodPreflight.manifestTargets(VodFormat.HLS, "https://cdn.example.test/hls/720/index.m3u8?Expires=1",
                "#EXTM3U\n#EXTINF:10,\n0000.ts\n#EXTINF:10,\n0001.ts\n"))
        assertEquals(listOf("https://cdn.example.test/hls/720/init.mp4"),
            VodPreflight.manifestTargets(VodFormat.HLS, "https://cdn.example.test/hls/720/index.m3u8",
                "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:10,\n0000.m4s\n"))
    }

    @Test fun `the probe keeps its candidate budget small`() {
        assertTrue(VodPreflight.MAX_CANDIDATES in 2..8)
        assertTrue(VodPreflight.TIMEOUT_MS <= 5_000L)
        assertTrue(VodPreflight.CONCURRENCY in 1..4)
        val many = (1..20).map { source("s$it") }
        // verify() only ever probes the head of the list; this asserts the budget itself, not the network.
        assertEquals(VodPreflight.MAX_CANDIDATES, many.take(VodPreflight.MAX_CANDIDATES).size)
    }

    @Test fun `one-minute VOD anime decoy playlist is detected while full movies and live playlists pass`() {
        val oneMinAnime = "#EXTM3U\n#EXT-X-VERSION:3\n" +
            (0 until 6).joinToString("") { "#EXTINF:10.0,\nseg$it.ts\n" } + "#EXT-X-ENDLIST\n"
        assertEquals(60.0, VodPreflight.vodPlaylistDurationSeconds(oneMinAnime) ?: -1.0, 0.01)
        assertTrue(VodPreflight.isShortVodPlaylist(oneMinAnime, 210))

        val fullMovie = "#EXTM3U\n#EXT-X-VERSION:3\n" +
            (0 until 650).joinToString("") { "#EXTINF:10.0,\nseg$it.ts\n" } + "#EXT-X-ENDLIST\n"
        assertFalse(VodPreflight.isShortVodPlaylist(fullMovie, 210))

        // Live/sliding playlists without #EXT-X-ENDLIST are never treated as short clips
        val sliding = "#EXTM3U\n#EXTINF:6.0,\nlive0.ts\n#EXTINF:6.0,\nlive1.ts\n"
        assertFalse(VodPreflight.isShortVodPlaylist(sliding, 210))
    }

    @Test fun `duration guard skips 1-minute clip on 108m documentary while allowing genuine short films`() {
        assertEquals(108, VodDurationGuard.parseMinutes("108m"))
        assertEquals(108, VodDurationGuard.parseMinutes("1h 48m"))
        // 60s anime clip on 108m "Look at Me: XXXTENTACION" -> bogus!
        assertTrue(VodDurationGuard.isBogusShortClip(isSeries = false, expectedRuntimeMinutes = 108, durationMs = 60_000L, hasAlternativeSources = true))
        // Real 1h 53m stream -> valid!
        assertFalse(VodDurationGuard.isBogusShortClip(isSeries = false, expectedRuntimeMinutes = 108, durationMs = 6_807_000L, hasAlternativeSources = true))
        // Genuine 6m short film -> valid!
        assertFalse(VodDurationGuard.isBogusShortClip(isSeries = false, expectedRuntimeMinutes = 6, durationMs = 360_000L, hasAlternativeSources = true))
        // Last remaining source is never discarded
        assertFalse(VodDurationGuard.isBogusShortClip(isSeries = false, expectedRuntimeMinutes = 108, durationMs = 60_000L, hasAlternativeSources = false))
    }

    @Test fun `TMDB-exact VidLink and Source 13 rank ahead of fuzzy OG Source 2`() {
        val fuzzy2 = FrostSource(FrostProvider.OG, "og-s2", "https://embed.theogpiratebot.online/api/v1/stream?t=s2",
            VodFormat.HLS, "1080p", "Original", null, emptyMap(), emptyList(), scraperName = "Source 2")
        val vidlink = FrostSource(FrostProvider.OG, "og-vl", "https://bcdnxw.hakunaymatata.com/resource/720.mp4",
            VodFormat.MP4, "720p", "Original", false, emptyMap(), emptyList(), scraperName = "VidLink")
        val source13 = FrostSource(FrostProvider.OG, "og-s13", "https://bcdnxw.hakunaymatata.com/resource/720b.mp4",
            VodFormat.MP4, "720p", "Original", false, emptyMap(), emptyList(), scraperName = "Source 13")

        val ordered = VodSourceOrder.prioritize(listOf(fuzzy2, vidlink, source13))
        assertEquals(listOf("og-vl", "og-s13", "og-s2"), ordered.map { it.identity })
    }
}
