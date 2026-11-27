package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** maxTV / Fusion / StarShare drops mid-watch reconnect to the same subscription, within a bounded budget. */
class SubscriptionRelinkTest {
    @Test fun `a channel that never played keeps the normal fallback chain`() {
        assertNull(SubscriptionRelink().next(now = 0, played = false))
    }

    @Test fun `the first drop relinks at once and repeated drops back off`() {
        val relink = SubscriptionRelink(maxConsecutive = 4, gapMs = 2_500)
        assertEquals(0L, relink.next(10_000, played = true))
        assertEquals(2_500L, relink.next(10_100, played = true)!! + 100)   // due at 12_500
        assertEquals("two relinks left before falling back", 2, relink.remaining)
    }

    @Test fun `the budget is spent after repeated drops without stable playback`() {
        val relink = SubscriptionRelink(maxConsecutive = 3)
        var t = 0L
        repeat(3) { t += 60_000; relink.next(t, played = true) }
        assertNull("a dead channel must fall back to another server", relink.next(t + 60_000, played = true))
    }

    @Test fun `stable playback restores the budget so hours of token expiries keep the same server`() {
        val relink = SubscriptionRelink(maxConsecutive = 2)
        var t = 0L
        repeat(50) {                       // fifty ten-minute token expiries
            t += 600_000
            assertEquals("expiry #$it relinks immediately", 0L, relink.next(t, played = true))
            relink.stable()                 // the fresh link played cleanly for 30 s
        }
    }
}
