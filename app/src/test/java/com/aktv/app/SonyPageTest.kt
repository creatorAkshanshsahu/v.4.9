package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `allinonereborn2.online/sony/` page lists 22 Sony tiles and hands each one to a third-party web
 * player. The app never opens that player: the tiles are rebuilt from the page's own list and bound to
 * the playlist's channels, which carry a native manifest and ClearKey document. These cases pin the
 * page order and the name matching, and prove that nothing is invented for a channel the catalogue
 * does not carry.
 */
class SonyPageTest {

    private fun channel(id: String, name: String, jio: Boolean = true) =
        MainActivity.Channel(id, name, "Sports", "https://logo.example/$id.png", v2 = false, jio = jio)

    /** The page's own order, as far as the sampled playlist carries those channels. */
    @Test fun `the page's channels come back in the page's order`() {
        val catalogue = listOf(
            channel("892", "Sony Ten 3 Hindi HD"),
            channel("162", "Sony Ten 1 HD"),
            channel("291", "SET HD"),
            channel("1393", "Sony Wah"),
            channel("471", "Sony SAB HD"),
            channel("852", "Sony BBC Earth HD English")
        )
        assertEquals(
            listOf("SET HD", "Sony SAB HD", "Sony Wah", "Sony Ten 1 HD", "Sony Ten 3 Hindi HD", "Sony BBC Earth HD English"),
            SonyPage.channels(catalogue).map { it.name }
        )
    }

    /** A channel the playlist does not carry is left out, never replaced by a guessed one. */
    @Test fun `unavailable page tiles are skipped instead of invented`() {
        val picked = SonyPage.channels(listOf(channel("1393", "Sony Wah")))
        assertEquals(listOf("Sony Wah"), picked.map { it.name })
        assertTrue(picked.single().jio)
    }

    /** One catalogue entry becomes one tile: the plain and HD page tiles cannot both claim it. */
    @Test fun `a single catalogue entry never fills two page tiles`() {
        val picked = SonyPage.channels(listOf(channel("892", "Sony Ten 3 Hindi HD")))
        assertEquals(1, picked.size)
        assertEquals(1, picked.map { it.key }.distinct().size)
    }

    @Test fun `both Ten 3 page tiles survive when the catalogue has both`() {
        val catalogue = listOf(channel("892", "Sony Ten 3 HD"), channel("1892", "Sony Ten 3"))
        assertEquals(listOf("Sony Ten 3 HD", "Sony Ten 3"), SonyPage.channels(catalogue).map { it.name })
    }

    @Test fun `V2 channels and an empty catalogue never produce a row`() {
        assertTrue(SonyPage.channels(emptyList()).isEmpty())
        val v2 = MainActivity.Channel("162", "Sony Ten 1 HD", "Sports", "", v2 = true, jio = false)
        assertTrue(SonyPage.channels(listOf(v2)).isEmpty())
    }

    @Test fun `the page section has its own title and does not replace the catalogued shelf`() {
        assertTrue(SonyPage.TITLE.isNotBlank())
        assertTrue(SonyPage.TITLE != LiveCollections.SONY_TITLE)
        assertEquals(22, SonyPage.TILES.size)
        assertEquals(SonyPage.TILES.size, SonyPage.TILES.map { it.slug }.distinct().size)
    }
}
