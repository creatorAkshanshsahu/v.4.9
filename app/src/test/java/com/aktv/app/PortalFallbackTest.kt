package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PortalFallbackTest {
    @Test fun `case punctuation and spacing never distinguish channels`() {
        assertEquals(1, PortalFallback.matchIndex("Star Sports 1", listOf("Zee Cinema", "STAR SPORTS-1!!")))
    }

    @Test fun `a trailing quality tag still finds the same channel either way`() {
        assertEquals(0, PortalFallback.matchIndex("Zee Cinema HD", listOf("Zee Cinema", "Zee News")))
        assertEquals(1, PortalFallback.matchIndex("Zee Cinema", listOf("Zee News", "Zee Cinema 4K")))
    }

    @Test fun `an exact match wins over a quality variant`() {
        assertEquals(1, PortalFallback.matchIndex("Zee Cinema", listOf("Zee Cinema HD", "Zee Cinema")))
    }

    @Test fun `language stays part of the identity`() {
        assertEquals(-1, PortalFallback.matchIndex("Star Sports 1 Hindi", listOf("Star Sports 1 Tamil", "Star Sports 1")))
        assertEquals(0, PortalFallback.matchIndex("Star Sports 1 Hindi", listOf("Star Sports 1 Hindi HD")))
    }

    @Test fun `no match and empty input stay a miss`() {
        assertEquals(-1, PortalFallback.matchIndex("Colors", listOf("Zee Cinema")))
        assertEquals(-1, PortalFallback.matchIndex("Colors", emptyList()))
        assertEquals(-1, PortalFallback.matchIndex("   ", listOf("Colors")))
    }

    @Test fun `twin hunt hits one channel per pool in pool order`() {
        val pools = listOf(listOf("Nick", "Pogo"), listOf("Nick HD", "Sony Sab"), listOf("Cartoon Network"))
        assertEquals(listOf(0 to 0, 1 to 0), PortalFallback.twinIndices("Nick", pools))
    }

    @Test fun `twin hunt skips pools that do not carry the channel`() {
        val pools = listOf(listOf("Pogo"), listOf("Zee Cinema"), listOf("Nick"))
        assertEquals(listOf(2 to 0), PortalFallback.twinIndices("Nick", pools))
        assertEquals(emptyList<Pair<Int, Int>>(), PortalFallback.twinIndices("Nick", pools.take(2)))
    }

    @Test fun `a portal still loading its first playlist is the only unsettled case`() {
        assertEquals(true, PortalFallback.portalSettled(configured = false, hasChannels = false, inFlight = false, loaded = false, retried = false))
        assertEquals(true, PortalFallback.portalSettled(configured = true, hasChannels = true, inFlight = true, loaded = false, retried = false))
        assertEquals(true, PortalFallback.portalSettled(configured = true, hasChannels = false, inFlight = false, loaded = true, retried = false))
        assertEquals(true, PortalFallback.portalSettled(configured = true, hasChannels = false, inFlight = false, loaded = false, retried = true))
        assertEquals(false, PortalFallback.portalSettled(configured = true, hasChannels = false, inFlight = true, loaded = false, retried = false))
        assertEquals(false, PortalFallback.portalSettled(configured = true, hasChannels = false, inFlight = false, loaded = false, retried = false))
    }
}
