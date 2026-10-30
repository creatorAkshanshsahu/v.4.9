package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class HttpReferenceClockTest {
    @Test fun `HTTP Date and Age establish a reference independent of device time`() {
        val time = HttpReferenceClock.parse("Sat, 17 Oct 2026 12:00:00 GMT", "60")!!
        assertEquals(60_000L, time - HttpReferenceClock.parse("Sat, 17 Oct 2026 12:00:00 GMT")!!)
        val clock = HttpReferenceClock(wall = { 1L }, elapsed = { 10L })
        clock.observe(time)
        assertEquals(time, clock.now())
    }
    @Test fun `a TV clock correction cannot move an observed server clock`() {
        var wall = 9_000_000L; var elapsed = 1_000L
        val clock = HttpReferenceClock({ wall }, { elapsed })
        clock.observe(100_000)
        wall = 0; elapsed += 2_000
        assertEquals(102_000L, clock.now())
        wall = Long.MAX_VALUE; elapsed += 1_000
        assertEquals(103_000L, clock.now())
    }
    @Test fun `without an HTTP time reference the wall clock is only a freshness hint`() {
        val clock = HttpReferenceClock({ 1234 }, { 0 })
        clock.observe(null)
        assertEquals(1234L, clock.now())
        assertNull(HttpReferenceClock.parse("not a date"))
        assertNull(HttpReferenceClock.parse(null))
    }
    @Test fun `negative and malformed Age cannot wind the reference backwards`() {
        val date = "Sat, 17 Oct 2026 12:00:00 GMT"
        assertEquals(HttpReferenceClock.parse(date), HttpReferenceClock.parse(date, "-100"))
        assertEquals(HttpReferenceClock.parse(date), HttpReferenceClock.parse(date, "bad"))
    }
    @Test fun `a newer successful observation replaces the old reference`() {
        var elapsed = 10L
        val clock = HttpReferenceClock({ 0 }, { elapsed })
        clock.observe(100)
        elapsed = 15; clock.observe(200)
        elapsed = 20
        assertEquals(205L, clock.now())
    }

    @Test fun `authentication rejection gets exactly one immediate refresh per provider`() {
        val recovery = PlaybackAuthRecovery()
        assertTrue(recovery.retry(LiveProvider.JJTV, 403))
        assertFalse(recovery.retry(LiveProvider.JJTV, 401))
        assertTrue(recovery.retry(LiveProvider.TG, 401))
        assertFalse(recovery.retry(LiveProvider.TG, 403))
    }
    @Test fun `network DRM and geographic errors must not be disguised as token refreshes`() {
        val recovery = PlaybackAuthRecovery()
        for (code in listOf(null, 200, 404, 429, 451, 500, 6006, 4001)) assertFalse(recovery.retry(LiveProvider.JJTV, code))
        assertTrue(recovery.retry(LiveProvider.JJTV, 401))
    }
    @Test fun `new channel or stable playback can permit a later token renewal without extending deadlines`() {
        val recovery = PlaybackAuthRecovery(); val total = PlaybackStartupBudget(); val provider = NativeProviderWindow()
        total.start(0); provider.enter(0)
        assertTrue(recovery.retry(LiveProvider.JJTV, 403))
        total.recover(20_000); provider.recover(20_000)
        assertTrue(provider.expired(25_000)); assertTrue(total.expired(60_000))
        recovery.reset()
        assertTrue(recovery.retry(LiveProvider.JJTV, 401))
    }
}
