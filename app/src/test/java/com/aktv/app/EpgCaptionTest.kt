package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgCaptionTest {
    private fun prog(title: String) = Programme(title, 0L, 0L, "", "", "", "", "")

    @Test fun `a now-playing programme captions its title`() {
        assertEquals("Taarak Mehta", Epg.captionFor(prog("Taarak Mehta")))
    }

    @Test fun `titles are trimmed`() {
        assertEquals("Nick", Epg.captionFor(prog("  Nick  ")))
    }

    @Test fun `nothing on now captions nothing`() {
        assertNull(Epg.captionFor(null))
        assertNull(Epg.captionFor(prog("")))
        assertNull(Epg.captionFor(prog("   ")))
    }
}
