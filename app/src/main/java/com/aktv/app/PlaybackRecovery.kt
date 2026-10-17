package com.aktv.app

/** Current servers first, then one additional native provider, then one fresh pass. No player switching. */
internal class PlaybackRecovery {
    sealed class Action {
        data class Source(val index: Int) : Action()
        data class MoreSources(val fresh: Boolean) : Action()
        object Refresh : Action()
        object Unavailable : Action()
    }
    private val failed = HashSet<Int>()
    private var refreshed = false
    private var moreRequested = false

    fun reset() { failed.clear(); refreshed = false; moreRequested = false }

    /** Retire every source from a provider whose metadata/startup window elapsed. The other provider
     * and one fresh pass remain available, but cycling back cannot restart this same failed window.
     */
    fun skip(indices: Iterable<Int>) { failed.addAll(indices.filter { it >= 0 }) }

    fun failed(index: Int, count: Int, canLoadMore: Boolean = false): Action {
        if (index in 0 until count) failed.add(index)
        for (step in 1..count) {
            val next = (index + step) % count
            if (next !in failed) return Action.Source(next)
        }
        if (canLoadMore && !moreRequested) {
            moreRequested = true
            return Action.MoreSources(fresh = refreshed)
        }
        return if (!refreshed) refresh() else Action.Unavailable
    }
    private fun refresh(): Action {
        refreshed = true; moreRequested = false; failed.clear()
        return Action.Refresh
    }
}
