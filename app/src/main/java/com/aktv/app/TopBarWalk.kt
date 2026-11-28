package com.aktv.app

/**
 * Where a D-pad press goes while focus sits on the top bar.
 *
 * The bar is `Live TV · JioTV · Movies · Series` followed by the round search button, which is one slot
 * past the last pill. Everything here is slot arithmetic on purpose: the first version of this routing
 * sent RIGHT straight back down to the shelf and swallowed LEFT, which left a television with no way to
 * reach JioTV, Movies or Series at all. Keeping the decision in one pure function means it can be tested
 * without a screen.
 */
internal object TopBarWalk {

    /** The press leaves the bar: nothing of the bar lies further in that direction. */
    const val OFF = -1

    /** The search circle's slot: the slot after the last pill. */
    fun searchSlot(pillCount: Int) = pillCount

    /**
     * The slot LEFT or RIGHT lands on, given the slot focus is on now, or [OFF] when the press would
     * leave the bar. `from` is a pill index or [searchSlot]; `pillCount` is the number of pills.
     */
    fun step(from: Int, pillCount: Int, right: Boolean): Int {
        if (from < 0 || from > searchSlot(pillCount)) return OFF
        val to = if (right) from + 1 else from - 1
        return if (to in 0..searchSlot(pillCount)) to else OFF
    }
}
