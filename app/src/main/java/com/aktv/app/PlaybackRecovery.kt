package com.aktv.app

/** One attempt per native source, plus one fresh configuration. Reset only on user action or stable playback. */
internal class PlaybackRecovery {
    sealed class Action {
        data class Source(val index: Int) : Action()
        object Refresh : Action()
        object Unavailable : Action()
    }
    private val failed = HashSet<Int>()
    private var refreshed = false

    fun reset() { failed.clear(); refreshed = false }

    fun failed(index: Int, count: Int, refreshFirst: Boolean = false): Action {
        if (index in 0 until count) failed.add(index)
        if (refreshFirst && !refreshed) return refresh()
        for (step in 1..count) {
            val next = (index + step) % count
            if (next !in failed) return Action.Source(next)
        }
        return if (!refreshed) refresh() else Action.Unavailable
    }
    private fun refresh(): Action {
        refreshed = true; failed.clear()
        return Action.Refresh
    }
}
