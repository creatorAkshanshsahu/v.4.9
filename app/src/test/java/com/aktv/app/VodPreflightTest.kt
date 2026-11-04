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

    @Test fun `the probe keeps its candidate budget small`() {
        assertTrue(VodPreflight.MAX_CANDIDATES in 2..8)
        assertTrue(VodPreflight.TIMEOUT_MS <= 5_000L)
        val many = (1..20).map { source("s$it") }
        // verify() only ever probes the head of the list; this asserts the budget itself, not the network.
        assertEquals(VodPreflight.MAX_CANDIDATES, many.take(VodPreflight.MAX_CANDIDATES).size)
    }
}
