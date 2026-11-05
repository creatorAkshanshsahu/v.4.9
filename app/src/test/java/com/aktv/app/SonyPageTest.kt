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

    /**
     * The JioTV section is the playlist, so the channels it holds must stay ordinary channels: no page on
     * the channel itself. Only the Live TV tiles the section builds get a slug, which is why a Sony channel
     * played from JioTV goes to the native player and the same channel played from Live TV opens the page.
     */
    @Test fun `a playlist Sony channel is not diverted to the page by its name`() {
        val playlist = listOf(channel("892", "Sony Ten 3 Hindi HD"), channel("287", "Sony SAB HD"), channel("325", "Sony MAX2"))
        assertTrue("the playlist's own channels must not carry a page", playlist.all { it.page == null })
        val section = SonyPage.channels(playlist)
        assertEquals(SonyPage.TILES.size, section.count { it.page != null })
        assertEquals(SonyPage.TILES.size, SonyPage.channels(playlist).size)
    }

    /** Every tile opens the page, and the slug it opens is the page's own - no invented address. */
    @Test fun `every tile opens the page own player`() {
        val section = SonyPage.channels(listOf(channel("892", "Sony Ten 3 Hindi HD")))
        assertTrue(section.all { it.page != null && it.page!!.startsWith(SonyPage.PAGE) })
        assertEquals(SonyPage.TILES.size, section.map { it.page }.distinct().size)
    }

    @Test fun `the page and its player are the only source names used`() {
        assertEquals("https://suniliv.pages.dev/ptest1?id=", SonyPage.PAGE)
        assertTrue(SonyPage.TITLE.isNotBlank())
        assertTrue(SonyPage.TILES.all { it.names.isNotEmpty() && it.logo.endsWith(".png") && it.title.isNotBlank() })
    }

    /**
     * The same 22 tiles in the JioTV section are the playlist's channels, so they play natively there; only
     * the Live TV copy of the section opens the site's player. A tile the playlist does not carry has nothing
     * to play natively and keeps its page instead of going silent.
     */
    @Test fun `the JioTV copy of the section plays the playlist, not the page`() {
        val playlist = listOf(channel("892", "Sony Ten 3 Hindi HD"), channel("287", "Sony SAB HD"))
        val native = SonyPage.playlistChannels(playlist)
        assertEquals(SonyPage.TILES.size, native.size)
        val tenThree = native.first { it.name == "SONY TEN 3" }
        assertEquals("jio:892", tenThree.key)
        assertNull("the JioTV tile must play the playlist natively", tenThree.page)
        // A tile the playlist does not carry keeps the page: it has no native source at all.
        val tenThreeHd = native.first { it.name == "SONY TEN 3 HD" }
        assertTrue(tenThreeHd.page != null && tenThreeHd.pageOnly)
        // The Live TV copy is untouched and still opens the page for every tile.
        assertTrue(SonyPage.channels(playlist).all { it.page != null })
    }
}
