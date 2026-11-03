package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class LiveCollectionsTest {
    private fun channel(id: String, name: String) = MainActivity.Channel(id, name, "Sony", "")

    @Test fun `curated IDs are unique and never empty`() {
        for (spec in LiveCollections.shelves) {
            assertEquals("duplicate ID in ${spec.title}", spec.ids.size, spec.ids.distinct().size)
            assertTrue(spec.title.isNotBlank())
            assertTrue(spec.kicker.isNotBlank())
        }
    }

    @Test fun `editorial order is preserved rather than catalogue order`() {
        val catalogue = listOf(channel("872", "Sony YAY"), channel("1103", "SET HD"), channel("1142", "BBC Earth"))
        val picked = LiveCollections.channels(LiveCollections.SONY, catalogue)
        assertEquals(listOf("1103", "1142", "872"), picked.map { it.id })
    }

    @Test fun `channels the providers do not advertise are dropped not substituted`() {
        val catalogue = listOf(channel("1103", "SET HD"), channel("9999", "Sony Something Else"))
        val picked = LiveCollections.channels(LiveCollections.SONY, catalogue)
        assertEquals(listOf("1103"), picked.map { it.id })
    }

    @Test fun `an empty catalogue yields no shelf instead of a broken row`() {
        assertTrue(LiveCollections.channels(LiveCollections.SONY, emptyList()).isEmpty())
    }

    @Test fun `a V2 channel can never be pulled into a V1 Sony shelf`() {
        val v2 = MainActivity.Channel("1103", "SET HD", "Sony", "", v2 = true)
        assertTrue(LiveCollections.channels(LiveCollections.SONY, listOf(v2)).isEmpty())
    }
}
