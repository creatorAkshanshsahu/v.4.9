package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `allinonereborn2.online/sony/` page lists 22 Sony tiles. Each one must appear in the app and must
 * play: a tile the site's playlist carries plays natively from that playlist, and a tile it does not
 * carry keeps its own player page (`suniliv.pages.dev/ptest1?id=<slug>`) so it is never silent.
 */
class SonyPageTest {

    private fun channel(id: String, name: String, jio: Boolean = true, logo: String = "") =
        MainActivity.Channel(
            id, name, "Sports", logo.ifEmpty { "https://logo.example/$id.png" }, v2 = false, jio = jio
        )

    private fun bound(section: List<MainActivity.Channel>) = section.filter { !it.pageOnly }

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
            bound(SonyPage.channels(catalogue)).map { it.name }
        )
    }

    /** Every page tile is present: the playlist plays what it carries, the site's page plays the rest. */
    @Test fun `all 22 page channels are tiles and every tile has a source`() {
        val section = SonyPage.channels(listOf(channel("892", "Sony Ten 3 Hindi HD"), channel("162", "Sony Ten 1 HD")))
        assertEquals(22, section.count { it.page != null })
        assertEquals(SonyPage.TILES.size, SonyPage.TILES.map { it.slug }.distinct().size)
        assertTrue(section.all { it.pinned })
        // A tile the playlist carries plays natively and keeps its page as the fallback.
        val native = bound(section)
        assertEquals(2, native.size)
        assertTrue(native.all { it.jio && it.page!!.startsWith(SonyPage.PAGE) })
        // A tile it does not carry has no native provider at all and plays its page inside the app.
        val pageOnly = section.filter { it.pageOnly }
        assertEquals(20, pageOnly.size)
        assertTrue(pageOnly.all { !it.jio && it.page!!.startsWith(SonyPage.PAGE) && it.name.isNotBlank() && it.logo.isNotBlank() })
        pageOnly.forEach {
            assertEquals(
                emptyList<LiveProvider>(),
                ChannelProviderOrder.playbackOrder(
                    it.id, v2 = it.v2, jio = it.jio, rememberedWebsite = null, pinned = it.pinned, pageOnly = it.pageOnly
                )
            )
        }
    }

    /** The tile that carries a playlist channel is pinned to the playlist, never to another provider. */
    @Test fun `a bound tile is pinned to the playlist only`() {
        val picked = bound(SonyPage.channels(listOf(channel("892", "Sony Ten 3 Hindi HD"))))
        assertEquals(listOf("Sony Ten 3 Hindi HD"), picked.map { it.name })
        assertTrue(picked.single().jio && picked.single().pinned)
        assertEquals(
            listOf(LiveProvider.JIOM3U),
            ChannelProviderOrder.playbackOrder(
                picked.single().id, v2 = false, jio = true, rememberedWebsite = TgResolver.SITE,
                pinned = true, pageOnly = false
            )
        )
    }

    /** The page and the playlist publish the same CDN logo file, so a renamed channel still binds. */
    @Test fun `a channel is matched by its published logo when the name differs`() {
        val catalogue = listOf(
            channel("999", "Sports Feed 03", logo = "https://jiotvimages.cdn.jio.com/dare_images/images/Ten3_HD.png")
        )
        val picked = bound(SonyPage.channels(catalogue))
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
        val extras = SonyPage.channels(catalogue).drop(22)
        assertEquals(
            listOf("Sony Yay Tamil", "SONY YAY Bengali", "Sony MAX1"),
            extras.map { it.name }
        )
        assertTrue(extras.all { it.pinned && it.jio && it.page == null })
    }

    /** Channels that merely share a word with a Sony brand are not Sony channels. */
    @Test fun `lookalike channels are never pulled into the section`() {
        val catalogue = listOf(
            channel("1", "Mahaa Max"), channel("2", "Jaya Max"), channel("3", "Aakash Aath"),
            channel("4", "MK Six"), channel("5", "Animax"), channel("6", "Sony Wah")
        )
        val section = SonyPage.channels(catalogue)
        assertEquals(listOf("Sony Wah"), bound(section).map { it.name })
        assertEquals(22, section.size)
    }

    /** One catalogue entry becomes one tile: the plain and HD page tiles cannot both claim it. */
    @Test fun `a single catalogue entry never fills two page tiles`() {
        val section = SonyPage.channels(listOf(channel("892", "Sony Ten 3 Hindi HD")))
        assertEquals(1, bound(section).size)
        // The HD slug it did not fill keeps its own page, so it is still a playable tile of the section.
        assertTrue(section.any { it.id == "page:sony-ten-3-hd" && it.pageOnly })
        assertEquals(1, section.count { it.jio })
    }

    @Test fun `both Ten 3 page tiles survive when the catalogue has both`() {
        val catalogue = listOf(channel("892", "Sony Ten 3 HD"), channel("1892", "Sony Ten 3"))
        assertEquals(listOf("Sony Ten 3 HD", "Sony Ten 3"), bound(SonyPage.channels(catalogue)).map { it.name })
    }

    @Test fun `an empty catalogue never produces a row`() {
        assertTrue(SonyPage.channels(emptyList()).isEmpty())
        val v2 = MainActivity.Channel("162", "Sony Ten 1 HD", "Sports", "", v2 = true, jio = false)
        assertTrue(SonyPage.channels(listOf(v2)).isEmpty())
    }

    /** One section, built from this page and its playlist; nothing else in the app lists Sony channels. */
    @Test fun `the section is the page's own and carries all 22 tiles`() {
        assertTrue(SonyPage.TITLE.isNotBlank())
        assertEquals(22, SonyPage.TILES.size)
        assertEquals(SonyPage.TILES.size, SonyPage.TILES.map { it.slug }.distinct().size)
        assertTrue(SonyPage.TILES.all { it.names.isNotEmpty() && it.logo.endsWith(".png") && it.title.isNotBlank() })
        assertTrue(SonyPage.TILES.all { SonyPage.pageUrl(it.slug) == SonyPage.PAGE + it.slug })
        assertTrue(SonyPage.TILES.all { SonyPage.pageUrl(it.slug).startsWith("https://suniliv.pages.dev/ptest1?id=") })
    }
}
