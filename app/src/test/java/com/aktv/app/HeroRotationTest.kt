package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home hero turns through the trending shelf on its own, and yields the moment the viewer browses. */
class HeroRotationTest {

    private fun allowed(
        tab: Int = HeroRotation.HOME,
        films: Int = 8,
        collapsed: Boolean = false,
        browsing: Boolean = false,
        playing: Boolean = false,
        searching: Boolean = false
    ) = HeroRotation.allowed(tab, films, collapsed, browsing, playing, searching)

    @Test
    fun `the hero advances through the shelf and wraps around`() {
        assertEquals(1, HeroRotation.next(0, 3))
        assertEquals(2, HeroRotation.next(1, 3))
        assertEquals("the last film leads back to the first", 0, HeroRotation.next(2, 3))
        assertEquals(0, HeroRotation.next(0, 0))
    }

    @Test
    fun `it turns over every few seconds on the home section`() {
        assertTrue(allowed())
        assertTrue("seven seconds is OTT pacing", HeroRotation.PERIOD_MS in 4_000L..10_000L)
    }

    @Test
    fun `only the channel sections give the hero to a channel`() {
        assertFalse("home keeps the films", HeroRotation.channelHero(HeroRotation.HOME))
        assertTrue(HeroRotation.channelHero(1))
        assertTrue(HeroRotation.channelHero(6))
    }

    @Test
    fun `nothing turns over when the viewer is doing something`() {
        assertFalse("another section owns its own hero", allowed(tab = 2))
        assertFalse("one film is not a carousel", allowed(films = 1))
        assertFalse("a folded hero is not on screen", allowed(collapsed = true))
        assertFalse("the card under the remote owns the hero", allowed(browsing = true))
        assertFalse(allowed(playing = true))
        assertFalse(allowed(searching = true))
    }
}
