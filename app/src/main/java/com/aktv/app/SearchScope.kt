package com.aktv.app

/**
 * What the search box searches, decided by the section it was opened from.
 *
 * The search has its own small numbering — 0 channels, 2 movies, 3 series — which is *not* the tab
 * numbering: tabs are 0 Live TV, 2 JioTV, 3 Movies, 4 Series and 6 maxTV. Handing the tab id straight to
 * the search meant maxTV searched movies, and Movies searched series. Keeping the translation in one pure
 * function is what stops that from happening again.
 */
internal object SearchScope {
    const val CHANNELS = 0
    const val MOVIES = 2
    const val SERIES = 3

    fun of(tabId: Int): Int = when (tabId) {
        3 -> MOVIES
        4 -> SERIES
        else -> CHANNELS
    }

    fun isVod(scope: Int) = scope == MOVIES || scope == SERIES
}
