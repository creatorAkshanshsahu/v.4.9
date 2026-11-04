package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `allinonereborn2.online/sony/` page lists 22 Sony tiles; the app rebuilds them from the page's
 * own list and binds each one to the matching channel of that site's playlist (native manifest +
 * ClearKey). These cases pin the page order, the name and logo matching, the one-channel-one-tile
 * rule, and the pin that keeps a Sony tile on the playlist source only.
 */
class SonyPageTest {

    private fun channel(id: String, name: String, jio: Boolean = true, logo: String = "") =
        MainActivity.Channel(
            id, name, "Sports", logo.ifEmpty { "https://logo.example/$id.png" }, v2 = false, jio = jio
        )

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

    /** The playlist, not another provider, is what these tiles play: every tile is pinned to it. */
    @Test fun `every tile is pinned to the playlist only`() {
        val picked = SonyPage.channels(listOf(channel("892", "Sony Ten 3 Hindi HD"), channel("162", "Sony Ten 1 HD")))
        assertEquals(2, picked.size)
        assertTrue(picked.all { it.pinned })
        assertTrue(picked.all { it.jio })
        assertTrue(picked.all { !it.v2 })
        picked.forEach { tile ->
            assertEquals(
                listOf(LiveProvider.JIOM3U),
                ChannelProviderOrder.playbackOrder(
                    tile.id, v2 = tile.v2, jio = tile.jio,
                    rememberedWebsite = TgResolver.SITE, requested = null, pinned = tile.pinned
                )
            )
        }
    }

    /** A channel the playlist does not carry is left out, never replaced by a guessed one. */
    @Test fun `unavailable page tiles are skipped instead of invented`() {
        val picked = SonyPage.channels(listOf(channel("1393", "Sony Wah")))
        assertEquals(listOf("Sony Wah"), picked.map { it.name })
        assertTrue(picked.single().jio)
    }

    /** The page and the playlist publish the same CDN logo file, so a renamed channel still binds. */
    @Test fun `a channel is matched by its published logo when the name differs`() {
        val catalogue = listOf(
            channel("999", "Sports Feed 03", logo = "https://jiotvimages.cdn.jio.com/dare_images/images/Ten3_HD.png")
        )
        val picked = SonyPage.channels(catalogue)
        assertEquals(listOf("Sports Feed 03"), picked.map { it.name })
        assertTrue(picked.single().pinned)
    }

    /** Every other Sony channel the source carries is added after the page's own tiles. */
    @Test fun `the section also carries Sony channels the page does not list`() {
        val catalogue = listOf(
            channel("872", "Sony Yay Hindi"),
            channel("1587", "Sony Yay Tamil"),
            channel("1927", "SONY YAY Bengali"),
            channel("484", "Sony MAX1"),
            channel("1407", "Sony BBC Earth HD Telugu"),
            channel("483", "Sony MAX2")
        )
        // Page order first (Yay, MAX2, the BBC Earth feed), then the rest of the Sony-branded channels.
        assertEquals(
            listOf("Sony Yay Hindi", "Sony MAX2", "Sony BBC Earth HD Telugu", "Sony Yay Tamil", "SONY YAY Bengali", "Sony MAX1"),
            SonyPage.channels(catalogue).map { it.name }
        )
        assertTrue(SonyPage.channels(catalogue).all { it.pinned })
    }

    /** Channels that merely share a word with a Sony brand are not Sony channels. */
    @Test fun `lookalike channels are never pulled into the section`() {
        val catalogue = listOf(
            channel("1", "Mahaa Max"), channel("2", "Jaya Max"), channel("3", "Aakash Aath"),
            channel("4", "MK Six"), channel("5", "Animax"), channel("6", "Sony Wah")
        )
        assertEquals(listOf("Sony Wah"), SonyPage.channels(catalogue).map { it.name })
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

    /** One section, built from this page and its playlist; nothing else in the app lists Sony channels. */
    @Test fun `the section is the page's own and carries all 22 tiles`() {
        assertTrue(SonyPage.TITLE.isNotBlank())
        assertEquals(22, SonyPage.TILES.size)
        assertEquals(SonyPage.TILES.size, SonyPage.TILES.map { it.slug }.distinct().size)
        assertTrue(SonyPage.TILES.all { it.names.isNotEmpty() && it.logo.endsWith(".png") })
    }
}
