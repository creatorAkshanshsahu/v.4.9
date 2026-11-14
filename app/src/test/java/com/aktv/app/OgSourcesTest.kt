package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Movies and series are played by theogpiratebot.online's own player, on the server the viewer picks.
 * These tests pin the addresses to the site's own shape so a picker can never invent one: the site's Play
 * button is `player?id=<tmdb>&type=movie|tv&server=0`.
 */
class OgSourcesTest {

    @Test fun `a movie opens the site player on the chosen server`() {
        assertEquals(
            "https://theogpiratebot.online/player?id=1423191&type=movie&server=0",
            OgSources.playerUrl("movie", 1423191, 0)
        )
        assertEquals(
            "https://theogpiratebot.online/player?id=1423191&type=movie&server=2",
            OgSources.playerUrl("movie", 1423191, 2)
        )
    }

    @Test fun `a series carries its episode and keeps the site's own type`() {
        assertEquals(
            "https://theogpiratebot.online/player?id=1396&type=tv&server=1&season=2&episode=5",
            OgSources.playerUrl("series", 1396, 1, season = 2, episode = 5)
        )
        // The catalogue says "series"; the site says "tv". Nothing else may leak into the address.
        assertEquals(OgSources.playerUrl("tv", 1396, 1, 2, 5), OgSources.playerUrl("series", 1396, 1, 2, 5))
        // A series opened without an episode is still a valid title address.
        assertEquals(
            "https://theogpiratebot.online/player?id=1396&type=tv&server=0",
            OgSources.playerUrl("tv", 1396, 0)
        )
    }

    @Test fun `only the site's own servers are offered`() {
        assertEquals(4, OgSources.SERVERS)
        val menu = OgSources.menu("movie", 550)
        assertEquals(OgSources.SERVERS, menu.size)
        assertEquals(listOf("OG player · Server 1 (site player)", "OG player · Server 2 (site player)",
            "OG player · Server 3 (site player)", "OG player · Server 4 (site player)"), menu.map { it.first })
        assertEquals(menu.size, menu.map { it.second }.distinct().size)
        assertTrue(menu.all { it.second.startsWith("https://theogpiratebot.online/player?id=550&type=movie&server=") })
        // A number the site does not publish is clamped, never turned into an address of its own.
        assertEquals(OgSources.SERVERS - 1, menu.map { it.second }.indexOf(OgSources.playerUrl("movie", 550, 99)))
        assertNotEquals(OgSources.playerUrl("movie", 550, 0), OgSources.playerUrl("movie", 550, 1))
    }

    @Test fun `a title without a TMDB identity is refused`() {
        listOf(0, -1).forEach { id ->
            runCatching { OgSources.playerUrl("movie", id, 0) }.let { assertTrue(it.isFailure) }
        }
    }
}
