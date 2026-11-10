package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A playlist section lives or dies on its parser: the attributes, the group, and the address line. */
class M3uPlaylistTest {

    private val sample = """
        #EXTM3U
        #EXTINF:-1 tvg-id="143" tvg-logo="https://cdn/CNBCTV18Prime.png" group-title="English",CNBC TV18 Prime
        https://cdn/live/143/index.m3u8
        #EXTVLCOPT:http-user-agent=Mozilla
        #EXTINF:-1 tvg-logo="https://cdn/StarGold.png" group-title="Movies, Classic",Star Gold HD, Delhi
        https://cdn/live/156/index.m3u8
        #EXTGRP:News
        #EXTINF:-1,DD News
        #KODIPROP:inputstream=adaptive
        https://cdn/live/203/index.m3u8
        #EXTINF:-1 group-title="Dead",Broken channel
        rtmp://nowhere/dead
        #EXTINF:-1 group-title="Dead",No address follows
        #EXTINF:-1 group-title="Dup",CNBC again
        https://cdn/live/143/index.m3u8
    """.trimIndent()

    @Test
    fun `it reads the name, group, logo and address of each entry`() {
        val list = M3uPlaylist.parse(sample)
        assertEquals("only the three playable, unique entries survive", 3, list.size)
        val first = list[0]
        assertEquals("CNBC TV18 Prime", first.name)
        assertEquals("English", first.group)
        assertEquals("https://cdn/CNBCTV18Prime.png", first.logo)
        assertEquals("https://cdn/live/143/index.m3u8", first.url)
        assertTrue("the id is derived from the address", first.id.isNotEmpty())
    }

    @Test
    fun `a quoted comma belongs to the attribute, not the name`() {
        val star = M3uPlaylist.parse(sample)[1]
        assertEquals("Movies, Classic", star.group)
        assertEquals("Star Gold HD, Delhi", star.name)
    }

    @Test
    fun `extgrp supplies the group when the entry names none`() {
        assertEquals("News", M3uPlaylist.parse(sample)[2].group)
    }

    @Test
    fun `entries with no address, an unplayable scheme or a repeated address are dropped`() {
        val list = M3uPlaylist.parse(sample)
        assertFalse("rtmp is not something the native player opens", list.any { it.name == "Broken channel" })
        assertFalse("an entry with no address line is not a channel", list.any { it.name == "No address follows" })
        assertEquals("the same address twice is one channel", 1, list.count { it.url.endsWith("/143/index.m3u8") })
    }

    @Test
    fun `an empty or headerless file yields nothing instead of throwing`() {
        assertTrue(M3uPlaylist.parse("").isEmpty())
        assertTrue(M3uPlaylist.parse("#EXTM3U\n").isEmpty())
        assertTrue(M3uPlaylist.parse("not a playlist at all").isEmpty())
    }

    @Test
    fun `the section puts the biggest groups first and caps each row`() {
        val items = List(300) { "A" } + List(5) { "B" } + List(2) { "C" } + List(1) { "D" }
        val rows = M3uPlaylist.sections(items) { it }
        assertEquals("four groups, biggest first", 4, rows.size)
        assertEquals("A", rows[0].first)
        assertEquals("B", rows[1].first)
        assertEquals("a huge group is capped instead of filling one row", M3uPlaylist.MAX_PER_ROW, rows[0].second.size)
        assertEquals("a group with more rows than the cap is cut", M3uPlaylist.MAX_GROUPS, M3uPlaylist.sections(List(40) { "$it" }) { it }.size)
    }
}
