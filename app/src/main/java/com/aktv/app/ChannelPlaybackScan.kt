package com.aktv.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class ChannelPlaybackOutcome { NOT_CHECKED, WORKING, FAILED, NO_FRAME }

internal data class ChannelPlaybackResult(
    val key: String,
    val id: String,
    val name: String,
    val category: String,
    val alwaysOn: Boolean,
    val outcome: ChannelPlaybackOutcome,
    val checkedAt: Long = 0L,
    val finding: String = "",
    val provider: String = "",
    val server: Int = 0
)

/** Copy-safe summaries for an on-device playback scan; never stores or prints stream credentials. */
internal object ChannelPlaybackScan {
    const val CHANNEL_DEADLINE_MS = 18_000L
    const val FRAME_SETTLE_MS = 1_200L

    fun safeFinding(value: String): String = value.takeIf {
        it.length <= 120 && it.matches(Regex("[A-Za-z0-9 _.:;()/-]*"))
    } ?: "Playback did not reach a confirmed first frame"

    fun report(
        results: List<ChannelPlaybackResult>,
        completed: Boolean,
        scanning: Boolean = false,
        currentIndex: Int = -1
    ): String = buildString {
        val working = results.count { it.outcome == ChannelPlaybackOutcome.WORKING }
        val failed = results.count { it.outcome == ChannelPlaybackOutcome.FAILED }
        val noFrame = results.count { it.outcome == ChannelPlaybackOutcome.NO_FRAME }
        val unchecked = results.count { it.outcome == ChannelPlaybackOutcome.NOT_CHECKED }
        append("STREAMR · on-device channel playback check\n")
        append(if (scanning) "Scan in progress" else if (completed) "Scan complete" else "Partial / incomplete scan")
        append(" · ${results.size} channels\n")
        append("Working: $working   Failed: $failed   No frame: $noFrame   Not checked: $unchecked\n")
        if (scanning && currentIndex in results.indices) {
            append("\nCurrently testing ${currentIndex + 1}/${results.size}: ${results[currentIndex].name}\n")
        }
        append("\nWORKING means this device rendered a video frame (or confirmed advancing audio) during the test. FAILED means the app exhausted its normal source/provider recovery. NO FRAME is inconclusive: no frame arrived within ${CHANNEL_DEADLINE_MS / 1000} seconds. Availability can change later.\n")
        results.forEach { result ->
            append("\n[${result.id}] ${result.name} · ")
            append(if (result.alwaysOn) "24/7 · " else "${result.category} · ")
            append(when (result.outcome) {
                ChannelPlaybackOutcome.NOT_CHECKED -> "NOT CHECKED"
                ChannelPlaybackOutcome.WORKING -> "WORKING"
                ChannelPlaybackOutcome.FAILED -> "FAILED"
                ChannelPlaybackOutcome.NO_FRAME -> "NO FRAME"
            })
            val testedAt = if (result.checkedAt > 0L) runCatching {
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(result.checkedAt))
            }.getOrNull() else null
            if (testedAt != null) append(" · $testedAt")
            if (result.provider.isNotBlank()) append(" · ${result.provider}${if (result.server > 0) " server ${result.server}" else ""}")
            if (result.finding.isNotBlank()) append(" · ${safeFinding(result.finding)}")
        }
        append("\n\nThis report contains no stream URLs, cookies, request headers, or DRM keys.")
    }
}
