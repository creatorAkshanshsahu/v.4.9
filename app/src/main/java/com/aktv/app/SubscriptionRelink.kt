package com.aktv.app

/**
 * Keeps a maxTV / Fusion / StarShare channel on its own subscription when the stream drops mid-watch.
 *
 * Portal and Xtream links carry a short-lived play token: after a few minutes the server refuses the
 * next playlist/segment request (HTTP 401/403/404/410) or simply ends the stream. That is not a reason
 * to change servers; the same portal will hand out a fresh link for the same channel. This policy
 * decides whether to ask again and how long to wait:
 *  - only after the channel has actually played (start-up failures keep the normal fallback chain),
 *  - at most [maxConsecutive] relinks without a stable stretch in between, with a growing gap,
 *  - a stable stretch of playback ([stable]) restores the whole budget, so a channel can be watched
 *    for hours across any number of token expiries.
 */
internal class SubscriptionRelink(private val maxConsecutive: Int = 4, private val gapMs: Long = 2_500) {
    private var consecutive = 0
    private var lastAt = Long.MIN_VALUE

    fun reset() { consecutive = 0; lastAt = Long.MIN_VALUE }

    /** Playback ran cleanly for a while: the next drop starts a fresh budget. */
    fun stable() { consecutive = 0 }

    val remaining: Int get() = (maxConsecutive - consecutive).coerceAtLeast(0)

    /** Delay before the next same-subscription relink, or null when the budget is spent. */
    fun next(now: Long, played: Boolean): Long? {
        if (!played || consecutive >= maxConsecutive) return null
        consecutive++
        // First relink is immediate; repeated drops back off (2.5 s, 5 s, 7.5 s) so a struggling
        // portal is not hammered.
        val earliest = if (lastAt == Long.MIN_VALUE || consecutive == 1) now else lastAt + gapMs * (consecutive - 1)
        val at = maxOf(now, earliest)
        lastAt = at
        return at - now
    }
}
