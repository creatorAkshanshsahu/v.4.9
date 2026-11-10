package com.aktv.app

/**
 * The home hero turns over on its own, the way an OTT home does: a trending film, a few seconds, the next
 * one. The pacing and the conditions live here so they can be read (and tested) in one place rather than
 * buried in a handler post — the screen itself only asks "may I turn this over now?".
 */
internal object HeroRotation {

    /** The home section's tab id. */
    const val HOME = 0

    /** Long enough to read a title, short enough that the screen feels alive. */
    const val PERIOD_MS = 7_000L

    /**
     * Whether a channel card may take the hero. Only on the channel sections — the home hero belongs to the
     * films, so browsing the live rows there keeps the carousel turning instead of swapping in a channel.
     */
    fun channelHero(tab: Int): Boolean = tab != HOME

    /** The film after this one, wrapping at the end of the shelf. */
    fun next(spot: Int, size: Int): Int = if (size <= 0) 0 else (spot + 1) % size

    /**
     * Whether the hero may advance right now. It may not while the viewer is browsing the shelf (that card
     * owns the hero), while the player or the search screen is up, once the hero has been folded away, on
     * any other section, or when there is nothing to turn over.
     */
    fun allowed(
        tab: Int,
        films: Int,
        heroCollapsed: Boolean,
        browsingShelf: Boolean,
        playing: Boolean,
        searching: Boolean
    ): Boolean = tab == HOME && films > 1 && !heroCollapsed && !browsingShelf && !playing && !searching
}
