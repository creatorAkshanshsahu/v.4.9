package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The section is the `allinonereborn2.online/sony/` page and nothing else: 22 tiles, the page's own order,
 * the page's own labels and logos, and every one of them opens that site's player for its slug inside the
 * app. Nothing is added to the page's list and nothing is guessed into it.
 */
class SonyPageTest {

    private fun channel(id: String, name: String, jio: Boolean = true, logo: String = "") =
        MainActivity.Channel(
            id, name, "Sports", logo.ifEmpty { "https://logo.example/$id.png" }, v2 = false, jio = jio
        )

    /** Nothing the page does not list, nothing it lists twice. */
    @Test fun `the section is exactly the page's 22 tiles`() {
        assertEquals(22, SonyPage.TILES.size)
        assertEquals(22, SonyPage.TILES.map { it.slug }.distinct().size)
        assertEquals(22, SonyPage.channels(emptyList()).size)
        assertEquals(
            22,
            SonyPage.channels(listOf(channel("892", "Sony Ten 3 Hindi HD"), channel("1587", "Sony Yay Tamil"))).size
        )
    }

    /** Channels the page does not list are never pulled in, however Sony-branded they are. */
    @Test fun `regional Sony channels that are not tiles stay out`() {
        val catalogue = listOf(
            channel("1587", "Sony Yay Tamil"), channel("1927", "SONY YAY Bengali"), channel("484", "Sony MAX1"),
            channel("154", "Sony SAB SD"), channel("1407", "Sony BBC Earth HD Telugu"), channel("1", "Mahaa Max")
        )
        assertEquals(
            SonyPage.TILES.map { it.slug },
            SonyPage.channels(catalogue).map { it.page!!.removePrefix(SonyPage.PAGE) }
        )
    }

    /** Every tile opens the site's player: that is the source, and it needs no playlist channel. */
    @Test fun `every tile carries its own player page`() {
        val section = SonyPage.channels(listOf(channel("892", "Sony Ten 3 Hindi HD")))
        assertTrue(section.all { it.page != null && it.page!!.startsWith(SonyPage.PAGE) })
        assertTrue(section.all { it.pinned })
        section.forEach {
            assertEquals(
                emptyList<LiveProvider>(),
                ChannelProviderOrder.playbackOrder(
                    it.id, v2 = it.v2, jio = it.jio, rememberedWebsite = TgResolver.SITE, pinned = it.pinned, pageOnly = it.page != null
                )
            )
        }
    }

    /** The page's own labels, in the page's own order. */
    @Test fun `tiles use the page's labels and order`() {
        val section = SonyPage.channels(emptyList())
        assertEquals(
            listOf(
                "SONY HD", "SAB HD", "SONY MARATHI", "SONY PAL", "SONY AATH", "SONY YAY", "SONY MAX HD",
                "SONY MAX", "SONY WAH", "SONY MAX2", "SONY PIX HD", "SONY TEN 1 HD", "SONY TEN 1",
                "SONY TEN 2 HD", "SONY TEN 2", "SONY TEN 3 HD", "SONY TEN 3", "SONY TEN 4 HD", "SONY TEN 4",
                "SONY TEN 5 HD", "SONY TEN 5", "BBC EARTH"
            ),
            section.map { it.name }
        )
        assertEquals(
            listOf(
                "sony-hd", "sab-hd", "sony-marathi", "sony-pal", "sony-aath", "sony-yay", "sony-max-hd",
                "sony-max", "sony-wah", "sony-max2", "sony-pix-hd", "sony-ten-1-hd", "sony-ten-1",
                "sony-ten-2-hd", "sony-ten-2", "sony-ten-3-hd", "sony-ten-3", "sony-ten-4-hd", "sony-ten-4",
                "sony-ten-5-hd", "sony-ten-5", "bbc-earth"
            ),
            section.map { it.page!!.removePrefix(SonyPage.PAGE) }
        )
        assertTrue(section.all { it.logo.startsWith("https://jiotvimages.cdn.jio.com/dare_images/images/") })
    }

    /** A tile whose channel is in the playlist keeps that identity, so favourites still line up. */
    @Test fun `a bound tile keeps the playlist channel identity but plays the page`() {
        val section = SonyPage.channels(listOf(channel("892", "Sony Ten 3 Hindi HD")))
        val tenThree = section.first { it.page!!.endsWith("sony-ten-3") }
        assertEquals("jio:892", tenThree.key)
        assertTrue(tenThree.page != null)
        // The HD tile the playlist does not carry keeps its own slug and still opens the player.
        val tenThreeHd = section.first { it.page!!.endsWith("sony-ten-3-hd") }
        assertEquals("page:sony-ten-3-hd", tenThreeHd.id)
        assertTrue(tenThreeHd.pageOnly)
    }

    /** A Sony channel reached from any other row opens the same player, matched by its name. */
    @Test fun `a Sony channel outside the row is matched to its page by name`() {
        assertEquals(SonyPage.pageUrl("sony-ten-3"), SonyPage.pageFor("Sony Ten 3 Hindi HD"))
        assertEquals(SonyPage.pageUrl("sony-hd"), SonyPage.pageFor("SET HD"))
        assertEquals(SonyPage.pageUrl("sab-hd"), SonyPage.pageFor("Sony SAB HD"))
        assertEquals(SonyPage.pageUrl("sony-marathi"), SonyPage.pageFor("Sony Marathi SD"))
        assertEquals(SonyPage.pageUrl("bbc-earth"), SonyPage.pageFor("BBC Earth HD"))
        assertEquals(SonyPage.pageUrl("sab-hd"), SonyPage.pageFor("SAB HD"))
        assertEquals(SonyPage.pageUrl("sony-max2"), SonyPage.pageFor("Sony MAX2"))
    }

    /** A channel that is not one of the page's tiles never gets its slug - not even a Sony one. */
    @Test fun `brands the page does not list are never given a slug`() {
        assertNull(SonyPage.pageFor("Sony Yay Tamil"))
        assertNull(SonyPage.pageFor("SONY YAY Bengali"))
        assertNull(SonyPage.pageFor("Sony MAX1"))
        assertNull(SonyPage.pageFor("Sony BBC Earth HD Telugu"))
        assertNull(SonyPage.pageFor("Sony SAB SD"))
        assertNull(SonyPage.pageFor("Sony Six"))
        assertNull(SonyPage.pageFor(""))
    }

    @Test fun `the page and its player are the only source names used`() {
        assertEquals("https://suniliv.pages.dev/ptest1?id=", SonyPage.PAGE)
        assertTrue(SonyPage.TITLE.isNotBlank())
        assertTrue(SonyPage.TILES.all { it.names.isNotEmpty() && it.logo.endsWith(".png") && it.title.isNotBlank() })
    }
}
