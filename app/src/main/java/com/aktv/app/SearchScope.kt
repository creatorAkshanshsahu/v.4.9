package com.aktv.app

/**
 * What the search box searches, decided by the section it was opened from.
 *
 * The search has its own small numbering — 0 channels, 2 movies, 3 series, 4 OG (movies, series & anime) —
 * which is *not* the tab numbering: tabs are 0 Live TV, 2 JioTV, 3 Movies, 4 Series, 6 maxTV and 8 OG.
 */
internal object SearchScope {
    const val CHANNELS = 0
    const val MOVIES = 2
    const val SERIES = 3
    const val OG = 4
    const val STARSHARE_VOD = 5

    fun of(tabId: Int): Int = when (tabId) {
        3 -> MOVIES
        4 -> SERIES
        8 -> OG
        else -> CHANNELS
    }

    fun isVod(scope: Int) = scope == MOVIES || scope == SERIES || scope == OG || scope == STARSHARE_VOD
}
