package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackHealthTest {
    private val ready = PlaybackHealth.State.READY
    private val buffering = PlaybackHealth.State.BUFFERING

    @Test fun `READY with audio progress but no picture still times out`() {
        val health = PlaybackHealth(0)
        assertNull(health.sample(14_999, ready, true, 14_999).failure)
        val result = health.sample(15_000, ready, true, 15_000)
        assertEquals(PlaybackHealth.Failure.NO_PICTURE, result.failure)
        assertFalse(result.stable)
    }

    @Test fun `buffering ready oscillation cannot extend the first-frame deadline`() {
        val health = PlaybackHealth(0)
        for (second in 1..14) {
            assertNull(health.sample(second * 1_000L, if (second % 2 == 0) ready else buffering, false, 0).failure)
        }
        assertEquals(PlaybackHealth.Failure.NO_PICTURE, health.sample(15_000, ready, true, 100).failure)
    }

    @Test fun `renderer metadata without a rendered first frame is not success`() {
        val health = PlaybackHealth(0)
        health.videoProgress(14_900)
        assertEquals(PlaybackHealth.Failure.NO_PICTURE, health.sample(15_000, ready, true, 15_000).failure)
    }

    @Test fun `continuous actual video progress resets recovery once even with a sliding live position`() {
        val health = PlaybackHealth(0)
        health.firstFrame(0)
        assertFalse(health.sample(0, ready, true, 10_000).stable)
        for (second in 1..29) {
            health.videoProgress(second * 1_000L)
            assertFalse(health.sample(second * 1_000L, ready, true, 10_000).stable)
        }
        health.videoProgress(30_000)
        assertTrue(health.sample(30_000, ready, true, 10_000).stable)
        health.videoProgress(31_000)
        assertFalse(health.sample(31_000, ready, true, 10_000).stable)
    }

    @Test fun `repeated buffering events after playback do not restart the stall timer`() {
        val health = PlaybackHealth(0)
        health.firstFrame(0)
        for (second in 2..13) assertNull(health.sample(second * 1_000L, buffering, false, 1_000).failure)
        assertEquals(PlaybackHealth.Failure.BUFFERING, health.sample(14_000, buffering, false, 1_000).failure)
    }

    @Test fun `audio playing over frozen video cannot count as healthy playback`() {
        val health = PlaybackHealth(0)
        health.firstFrame(0)
        assertEquals(PlaybackHealth.Failure.VIDEO_STOPPED, health.sample(12_000, ready, true, 12_000).failure)
    }

    @Test fun `READY but suppressed playback cannot leave a stale first frame forever`() {
        val health = PlaybackHealth(0)
        health.firstFrame(0)
        assertEquals(PlaybackHealth.Failure.VIDEO_STOPPED, health.sample(12_000, ready, false, 0).failure)
    }

    @Test fun `explicitly paused playback is not declared a decoder failure`() {
        val health = PlaybackHealth(0)
        health.firstFrame(0)
        assertNull(health.sample(30_000, ready, false, 0, wantsPlayback = false).failure)
        health.videoProgress(30_001)
        assertNull(health.sample(30_001, ready, true, 1_000).failure)
    }

    @Test fun `radio can establish progress without a video frame and later rebuffer normally`() {
        val health = PlaybackHealth(0, expectsVideo = false)
        assertFalse(health.sample(1_000, ready, true, 0).stable)
        assertTrue(health.sample(31_000, ready, true, 30_000).stable)
        assertNull(health.sample(32_000, buffering, false, 30_000).failure)
        assertEquals(PlaybackHealth.Failure.BUFFERING, health.sample(44_000, buffering, false, 30_000).failure)
    }

    @Test fun `an idle audio-only source still has a finite startup timeout`() {
        val health = PlaybackHealth(0, expectsVideo = false)
        assertEquals(PlaybackHealth.Failure.STARTUP, health.sample(15_000, PlaybackHealth.State.IDLE, false, 0).failure)
    }

    @Test fun `the overall tuning budget cannot be extended by source or decoder retries`() {
        val budget = PlaybackStartupBudget()
        budget.start(1_000)
        budget.recover(20_000); budget.recover(40_000); budget.recover(60_000)
        assertFalse(budget.expired(60_999))
        assertTrue(budget.expired(61_000))
    }

    @Test fun `a rendered picture permits a new bounded recovery cycle later`() {
        val budget = PlaybackStartupBudget()
        budget.start(0); budget.rendered()
        assertFalse(budget.expired(1_000_000))
        budget.recover(1_000_000)
        assertTrue(budget.expired(1_060_000))
    }

    @Test fun `manual user retry resets the budget and closing cancels it`() {
        val budget = PlaybackStartupBudget()
        budget.start(0); budget.start(50_000)
        assertFalse(budget.expired(60_000))
        budget.clear()
        assertFalse(budget.expired(500_000))
    }
}
