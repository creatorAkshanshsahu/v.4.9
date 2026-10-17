package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun `sources are tried once then refreshed once then playback stops`() {
        val recovery = PlaybackRecovery()
        assertEquals(PlaybackRecovery.Action.Source(1), recovery.failed(0, 2))
        assertEquals(PlaybackRecovery.Action.Refresh, recovery.failed(1, 2))
        assertEquals(PlaybackRecovery.Action.Source(1), recovery.failed(0, 2))
        assertEquals(PlaybackRecovery.Action.Unavailable, recovery.failed(1, 2))
    }

    @Test fun `a rejected source cannot force a reload before trying the other servers`() {
        val recovery = PlaybackRecovery()
        assertEquals(PlaybackRecovery.Action.Source(1), recovery.failed(0, 3))
        assertEquals(PlaybackRecovery.Action.Source(2), recovery.failed(1, 3))
        assertEquals(PlaybackRecovery.Action.Refresh, recovery.failed(2, 3))
        assertEquals(PlaybackRecovery.Action.Source(1), recovery.failed(0, 3))
        assertEquals(PlaybackRecovery.Action.Source(2), recovery.failed(1, 3))
        assertEquals(PlaybackRecovery.Action.Unavailable, recovery.failed(2, 3))
    }

    @Test fun `remembered middle server wraps without skipping the first source`() {
        val recovery = PlaybackRecovery()
        assertEquals(PlaybackRecovery.Action.Source(2), recovery.failed(1, 3))
        assertEquals(PlaybackRecovery.Action.Source(0), recovery.failed(2, 3))
        assertEquals(PlaybackRecovery.Action.Refresh, recovery.failed(0, 3))
    }

    @Test fun `no server cannot cause division by zero or an endless reload loop`() {
        val recovery = PlaybackRecovery()
        assertEquals(PlaybackRecovery.Action.Refresh, recovery.failed(0, 0))
        assertEquals(PlaybackRecovery.Action.Unavailable, recovery.failed(0, 0))
    }

    @Test fun `a new channel manual retry or sustained playback restores the budget`() {
        val recovery = PlaybackRecovery()
        recovery.failed(0, 1)
        assertEquals(PlaybackRecovery.Action.Unavailable, recovery.failed(0, 1))
        recovery.reset()
        assertEquals(PlaybackRecovery.Action.Refresh, recovery.failed(0, 1))
    }

    @Test fun `additional native sources load only after the current server list fails`() {
        val recovery = PlaybackRecovery()
        assertEquals(PlaybackRecovery.Action.Source(1), recovery.failed(0, 2, canLoadMore = true))
        assertEquals(PlaybackRecovery.Action.MoreSources(false), recovery.failed(1, 2, canLoadMore = true))
        // One extra provider appended source 2. The failed TG indices must stay failed.
        assertEquals(PlaybackRecovery.Action.Refresh, recovery.failed(2, 3))
    }

    @Test fun `empty additional metadata cannot cause an endless provider lookup`() {
        val recovery = PlaybackRecovery()
        assertEquals(PlaybackRecovery.Action.MoreSources(false), recovery.failed(0, 0, canLoadMore = true))
        assertEquals(PlaybackRecovery.Action.Refresh, recovery.failed(0, 0, canLoadMore = true))
        assertEquals(PlaybackRecovery.Action.MoreSources(true), recovery.failed(0, 0, canLoadMore = true))
        assertEquals(PlaybackRecovery.Action.Unavailable, recovery.failed(0, 0, canLoadMore = true))
    }

    @Test fun `fresh recovery also refreshes the additional provider once and then stops`() {
        val recovery = PlaybackRecovery()
        assertEquals(PlaybackRecovery.Action.MoreSources(false), recovery.failed(0, 1, canLoadMore = true))
        assertEquals(PlaybackRecovery.Action.Refresh, recovery.failed(1, 2))
        assertEquals(PlaybackRecovery.Action.MoreSources(true), recovery.failed(0, 1, canLoadMore = true))
        assertEquals(PlaybackRecovery.Action.Unavailable, recovery.failed(1, 2))
    }

    @Test fun `replacing expired provider entries retains other provider failures without resetting refresh budget`() {
        val recovery = PlaybackRecovery()
        recovery.skip(listOf(0, 1, 2))
        recovery.remapSources(mapOf(2 to 0)) // Old TG index 2 retained; renewed JJTV is now 1 and 2.
        assertEquals(PlaybackRecovery.Action.Source(2), recovery.failed(1, 3))
        assertEquals(PlaybackRecovery.Action.Refresh, recovery.failed(2, 3))
        recovery.skip(listOf(0, 1))
        recovery.remapSources(emptyMap())
        assertEquals(PlaybackRecovery.Action.Unavailable, recovery.failed(0, 1))
    }

}
