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
}
