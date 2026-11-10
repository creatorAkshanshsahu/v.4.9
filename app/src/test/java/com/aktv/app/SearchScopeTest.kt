package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** The search box follows the section it was opened from, not the tab id. */
class SearchScopeTest {

    @Test
    fun `channel sections search channels`() {
        for (tabId in listOf(0, 1, 2, 5, 6)) {
            assertEquals("tab $tabId must search channels", SearchScope.CHANNELS, SearchScope.of(tabId))
        }
    }

    @Test
    fun `movies search movies and series search series`() {
        assertEquals(SearchScope.MOVIES, SearchScope.of(3))
        assertEquals(SearchScope.SERIES, SearchScope.of(4))
    }

    @Test
    fun `only the two shelves are video searches`() {
        assertEquals(true, SearchScope.isVod(SearchScope.MOVIES))
        assertEquals(true, SearchScope.isVod(SearchScope.SERIES))
        assertEquals(false, SearchScope.isVod(SearchScope.CHANNELS))
    }
}
