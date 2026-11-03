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
        // Deliberately out of page order, and with the provider's own spellings.
        val catalogue = listOf(
            channel("852", "Sony BBC Earth HD English"),
            channel("891", "Sony Ten 2 HD"),
            channel("291", "SET HD"),
            channel("762", "Sony Pix HD"),
            channel("471", "Sony SAB HD"),
            channel("872", "Sony Yay Hindi")
        )
        val picked = LiveCollections.channels(LiveCollections.SONY, catalogue)
        assertEquals(
            listOf("SET HD", "Sony SAB HD", "Sony Yay Hindi", "Sony Pix HD", "Sony Ten 2 HD", "Sony BBC Earth HD English"),
            picked.map { it.name }
        )
    }

    /** The source page offers a plain and an HD tile per channel; HD must lead. */
    /**
     * The live channel feed of 2026-11-03, verbatim: 24 Sony channels out of 1255. This is the shelf a
     * user actually gets, so the whole selection and its order are pinned here.
     */
    @Test fun `the real catalogue produces the whole Sony shelf in page order`() {
        val live = linkedMapOf(
            "3420" to "SONY YAY Kannada", "291" to "SET HD", "1393" to "Sony Wah",
            "162" to "Sony Ten 1 HD", "471" to "Sony SAB HD", "483" to "Sony MAX2",
            "154" to "Sony SAB SD", "891" to "Sony Ten 2 HD", "474" to "Sony Pal",
            "892" to "Sony Ten 3 Hindi HD", "872" to "Sony Yay Hindi", "476" to "Sony Max HD",
            "762" to "Sony Pix HD", "697" to "Sony aath", "3424" to "SONY YAY Bengali",
            "873" to "Sony Yay Tamil", "1146" to "Sony Marathi SD", "3423" to "SONY YAY Malayalam",
            "874" to "Sony Yay Telugu", "852" to "Sony BBC Earth HD English",
            "853" to "Sony BBC Earth HD Tamil", "821" to "Sony BBC Earth HD",
            "854" to "Sony BBC Earth HD Telugu", "3418" to "Sony MAX1",
            // Present in the same feed, but SonyLIV app-only: must stay off the broadcast shelf.
            "3372" to "Sony LIV Sports 1", "3399" to "Sony LIV Sports 5"
        )
        val picked = LiveCollections.channels(LiveCollections.SONY, live.map { (id, name) -> channel(id, name) })
        assertEquals(24, picked.size)
        assertEquals(
            listOf(
                "SET HD", "Sony SAB HD", "Sony SAB SD", "Sony Marathi SD", "Sony Pal", "Sony aath",
                "SONY YAY Bengali", "Sony Yay Hindi", "SONY YAY Kannada", "SONY YAY Malayalam",
                "Sony Yay Tamil", "Sony Yay Telugu", "Sony Max HD", "Sony MAX1", "Sony Wah",
                "Sony MAX2", "Sony Pix HD", "Sony Ten 1 HD", "Sony Ten 2 HD", "Sony Ten 3 Hindi HD",
                "Sony BBC Earth HD", "Sony BBC Earth HD English", "Sony BBC Earth HD Tamil",
                "Sony BBC Earth HD Telugu"
            ),
            picked.map { it.name }
        )
    }

    @Test fun `an HD tile sorts ahead of the plain one it shadows`() {
        val catalogue = listOf(channel("1106", "Sony Ten 1"), channel("162", "Sony Ten 1 HD"))
        assertEquals(listOf("Sony Ten 1 HD", "Sony Ten 1"),
            LiveCollections.channels(LiveCollections.SONY, catalogue).map { it.name })
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
            channel("471", "Sony SAB HD")
        )
        val picked = LiveCollections.channels(LiveCollections.SONY, catalogue)
        // SET HD is Sony's own identity for Sony HD, so it is on the shelf; every name here is real.
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

    @Test fun `a bare SAB channel from another broadcaster is not Sony`() {
        val catalogue = listOf(channel("999", "SAB HD"), channel("471", "Sony SAB HD"))
        assertEquals(listOf("Sony SAB HD"), LiveCollections.channels(LiveCollections.SONY, catalogue).map { it.name })
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
