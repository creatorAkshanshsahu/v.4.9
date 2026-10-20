package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class NativeProviderWindowTest {
    @Test fun `metadata source changes and codec retries cannot keep the first provider alive forever`() {
        val window = NativeProviderWindow()
        window.enter(0)
        window.recover(12_000) // metadata completed
        window.recover(15_000) // source retry
        window.recover(24_000) // attempted codec retry
        assertFalse(window.expired(24_999))
        assertTrue(window.expired(25_000))
    }

    @Test fun `a slow TG lookup cannot consume 60 seconds before JJTV is attempted`() {
        val total = PlaybackStartupBudget()
        val window = NativeProviderWindow()
        val recovery = PlaybackRecovery()
        total.start(0); window.enter(0)
        // Even when no native player / source list exists, the provider lookup has a deadline.
        assertTrue(window.expired(25_000))
        assertEquals(PlaybackRecovery.Action.MoreSources(false), recovery.failed(0, 0, canLoadMore = true))
        window.enter(25_000) // JJTV starts inside the original total budget, not a reset budget.
        assertFalse(total.expired(35_000))
        assertFalse(window.expired(35_000))
        total.rendered(); window.rendered()
        assertFalse(total.expired(100_000))
        assertFalse(window.expired(100_000))
    }

    @Test fun `TG server one and two cannot starve an untried JJTV source`() {
        val recovery = PlaybackRecovery()
        val window = NativeProviderWindow()
        window.enter(0)
        assertEquals(PlaybackRecovery.Action.Source(1), recovery.failed(0, 2, canLoadMore = true))
        assertTrue(window.expired(25_000))
        recovery.skip(listOf(0, 1))
        assertEquals(PlaybackRecovery.Action.MoreSources(false), recovery.failed(1, 2, canLoadMore = true))
        // JJTV's two header variants are appended at indices 2 and 3. Neither failed TG source
        // should be revisited before the untried JJTV variant.
        assertEquals(PlaybackRecovery.Action.Source(3), recovery.failed(2, 4))
        assertEquals(PlaybackRecovery.Action.Refresh, recovery.failed(3, 4))
    }

    @Test fun `fresh provider passes cannot extend the whole tuning deadline`() {
        val total = PlaybackStartupBudget(); val window = NativeProviderWindow()
        total.start(0); window.enter(0)
        window.enter(25_000) // second provider
        window.enter(50_000) // forced fresh pass
        assertFalse(window.expired(60_000))
        assertTrue(total.expired(60_000))
    }

    @Test fun `a rendered frame clears the provider timer until later recovery`() {
        val window = NativeProviderWindow()
        window.enter(0); window.rendered()
        assertFalse(window.expired(120_000))
        window.recover(120_000)
        assertFalse(window.expired(144_999))
        assertTrue(window.expired(145_000))
    }

    @Test fun `closing a player clears its pending provider deadline`() {
        val window = NativeProviderWindow()
        window.enter(0); window.clear()
        assertFalse(window.expired(60_000))
    }

    @Test fun `a failed TG decoder retry does not force JJTV into software mode`() {
        val retries = NativeDecoderRetries()
        retries.mark(LiveProvider.TG)
        assertTrue(retries.used(LiveProvider.TG))
        assertFalse(retries.used(LiveProvider.JJTV))
        retries.mark(LiveProvider.JJTV)
        assertTrue(retries.used(LiveProvider.JJTV))
        retries.clear()
        assertFalse(retries.used(LiveProvider.TG)); assertFalse(retries.used(LiveProvider.JJTV))
    }

    @Test fun `empty second-provider lookup still permits only one refresh`() {
        val recovery = PlaybackRecovery()
        assertEquals(PlaybackRecovery.Action.MoreSources(false), recovery.failed(0, 0, true))
        assertEquals(PlaybackRecovery.Action.Refresh, recovery.failed(0, 0, false))
        assertEquals(PlaybackRecovery.Action.MoreSources(true), recovery.failed(0, 0, true))
        assertEquals(PlaybackRecovery.Action.Unavailable, recovery.failed(0, 0, false))
    }
}
