package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class LiveCollectionsTest {
    private fun channel(id: String, name: String) = MainActivity.Channel(id, name, "Sony", "")

    @Test fun `every shelf has a title and kicker`() {
        for (spec in LiveCollections.shelves) {
            assertTrue(spec.title.isNotBlank())
            assertTrue(spec.kicker.isNotBlank())
            assertTrue(spec.order.isNotEmpty())
        }
    }

    @Test fun `editorial order follows the source page rather than catalogue order`() {
        val catalogue = listOf(
            channel("821", "Sony BBC Earth HD"),
            channel("872", "Sony Yay Hindi"),
            channel("1103", "SET HD"),
            channel("3284", "Sony Pix HD")
        )
        val picked = LiveCollections.channels(LiveCollections.SONY, catalogue)
        assertEquals(listOf("SET HD", "Sony Pix HD", "Sony Yay Hindi", "Sony BBC Earth HD"), picked.map { it.name })
    }

    /**
     * The provider writes names its own way. These are the exact strings the live channel feed served
     * on 2026-11-03, so this test pins the real contract rather than a tidy-up of it.
     */
    @Test fun `real provider spellings are recognised as Sony channels`() {
        val catalogue = listOf(
            channel("872", "Sony Yay Hindi"),
            channel("483", "Sony MAX2"),
            channel("1146", "Sony Marathi SD"),
            channel("821", "Sony BBC Earth HD"),
            channel("762", "Sony Pix HD"),
            channel("162", "Sony Ten 1 HD"),
            channel("1103", "SET HD"),
            channel("1114", "SAB HD")
        )
        val picked = LiveCollections.channels(LiveCollections.SONY, catalogue)
        assertEquals(8, picked.size)
    }

    /**
     * Regression for the defect a live check caught: the shelf used to carry guessed Jio IDs, and 14 of
     * them resolved to a completely different broadcaster. Selection is by name now, so these exact
     * channels - which really do own those IDs - can never be pulled in.
     */
    @Test fun `channels that merely own a previously guessed ID are never selected`() {
        val impostors = listOf(
            channel("1110", "Star Movies Select HD"),
            channel("1142", "Star Sports 1"),
            channel("3284", "Rozana Spokesman 24x7"),
            channel("1106", "Star Sports 1 HD"),
            channel("1114", "Star Sports Select 2"),
            channel("1124", "Star Sports 1 Tamil"),
            channel("1113", "Star Gold Select HD"),
            channel("1123", "Star Sports Select 1"),
            channel("1112", "Maa HD"),
            channel("1122", "Star Sports 2 Kannada"),
            channel("1111", "Maa Movies"),
            channel("1109", "Star Sports 2 HD"),
            channel("1108", "Star Sports 1 Hindi HD"),
            channel("1119", "Star Gold Select")
        )
        assertTrue(LiveCollections.channels(LiveCollections.SONY, impostors).isEmpty())
    }

    @Test fun `SonyLIV app-only streams are kept out of the broadcast shelf`() {
        val catalogue = listOf(
            channel("3372", "Sony LIV Sports 1"),
            channel("3373", "Sony LIV Sports 2"),
            channel("1393", "Sony Wah")
        )
        assertEquals(listOf("Sony Wah"), LiveCollections.channels(LiveCollections.SONY, catalogue).map { it.name })
    }

    @Test fun `an empty catalogue yields no shelf instead of a broken row`() {
        assertTrue(LiveCollections.channels(LiveCollections.SONY, emptyList()).isEmpty())
    }

    @Test fun `a V2 channel can never be pulled into a V1 Sony shelf`() {
        val v2 = MainActivity.Channel("1103", "SET HD", "Sony", "", v2 = true)
        assertTrue(LiveCollections.channels(LiveCollections.SONY, listOf(v2)).isEmpty())
    }

    @Test fun `duplicate catalogue entries collapse to one tile`() {
        val catalogue = listOf(channel("872", "Sony Yay Hindi"), channel("872", "Sony Yay Hindi"))
        assertEquals(1, LiveCollections.channels(LiveCollections.SONY, catalogue).size)
    }
}
