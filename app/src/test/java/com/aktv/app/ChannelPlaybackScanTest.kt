package com.aktv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPlaybackScanTest {
    private fun result(id: String, name: String, outcome: ChannelPlaybackOutcome, finding: String = "") =
        ChannelPlaybackResult(id, id, name, "Entertainment", false, outcome, finding = finding)

    @Test fun reportSeparatesConfirmedFailuresTimeoutsAndUntested() {
        val report = ChannelPlaybackScan.report(listOf(
            result("and", "And TV", ChannelPlaybackOutcome.WORKING, "Rendered first frame"),
            result("bad", "Bad News", ChannelPlaybackOutcome.FAILED, "HTTP 403"),
            result("slow", "Slow TV", ChannelPlaybackOutcome.NO_FRAME),
            result("new", "New TV", ChannelPlaybackOutcome.NOT_CHECKED)
        ), completed = false)

        assertTrue(report.contains("Working: 1   Failed: 1   No frame: 1   Not checked: 1"))
        assertTrue(report.contains("Rendered first frame"))
        assertTrue(report.contains("NO FRAME is inconclusive"))
        assertTrue(report.contains("[new] New TV"))
        assertTrue(report.contains("Partial / incomplete scan"))
    }

    @Test fun reportNeverAcceptsUrlOrCredentialLikeFinding() {
        val unsafe = "https://example.invalid/live.m3u8?token=secret"
        val result = result("1", "Channel", ChannelPlaybackOutcome.FAILED, unsafe)
        val report = ChannelPlaybackScan.report(listOf(result), completed = true)

        assertFalse(report.contains("example.invalid"))
        assertFalse(report.contains("secret"))
        assertTrue(report.contains("Playback did not reach a confirmed first frame"))
    }

    @Test fun scanDeadlineIsBoundedAndWithinTheNormalStartupBudget() {
        assertTrue(ChannelPlaybackScan.CHANNEL_DEADLINE_MS in 1_000L..60_000L)
        assertTrue(ChannelPlaybackScan.FRAME_SETTLE_MS < ChannelPlaybackScan.CHANNEL_DEADLINE_MS)
    }
}
