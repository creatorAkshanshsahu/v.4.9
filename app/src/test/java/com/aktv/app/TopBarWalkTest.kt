package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The top bar is the only way into JioTV, Movies and Series on a television, and the first version of
 * this routing could not be walked at all: RIGHT dropped back to the shelf and LEFT did nothing, so the
 * remote was stuck on whichever tab happened to be open. These pin the slots down.
 */
class TopBarWalkTest {

    private val pills = 6                 // Live TV, JioTV, maxTV, Movies, Series, OG
    private val search = TopBarWalk.searchSlot(pills)

    @Test
    fun `right walks every pill in order and then the search circle`() {
        var slot = 0
        val seen = ArrayList<Int>()
        while (true) {
            slot = TopBarWalk.step(slot, pills, right = true)
            if (slot == TopBarWalk.OFF) break
            seen.add(slot)
        }
        assertEquals("right has to reach every pill through OG and search", listOf(1, 2, 3, 4, 5, search), seen)
    }

    @Test
    fun `left walks back from search to Live TV`() {
        var slot = search
        val seen = ArrayList<Int>()
        while (true) {
            slot = TopBarWalk.step(slot, pills, right = false)
            if (slot == TopBarWalk.OFF) break
            seen.add(slot)
        }
        assertEquals("left has to come back through every pill", listOf(5, 4, 3, 2, 1, 0), seen)
    }

    @Test
    fun `the ends of the bar are ends, not shortcuts`() {
        assertEquals("right of the search circle leaves the bar", TopBarWalk.OFF, TopBarWalk.step(search, pills, right = true))
        assertEquals("left of Live TV leaves the bar", TopBarWalk.OFF, TopBarWalk.step(0, pills, right = false))
    }

    @Test
    fun `focus that is not on the bar is not moved by it`() {
        assertEquals(TopBarWalk.OFF, TopBarWalk.step(-1, pills, right = true))
        assertEquals(TopBarWalk.OFF, TopBarWalk.step(-1, pills, right = false))
    }
}
