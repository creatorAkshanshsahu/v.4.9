package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JioTV playlist is a numbered file the site rebuilds: `jtv33` once answered and then simply stopped
 * existing, which emptied the section. The candidates are what keeps that from happening again.
 */
class JioM3uCandidatesTest {

    @Test
    fun `the shipped playlist is asked first, then the numbers around it`() {
        val urls = JioM3uProtocol.playlistCandidates()
        assertEquals(JioM3uProtocol.PLAYLIST, urls.first())
        assertEquals("a duplicate costs a request, so there is none", urls.size, urls.distinct().size)
        assertTrue("every candidate is the site's own numbered playlist",
            urls.all { JioM3uProtocol.isPlaylistAddress(it) })
        assertTrue("the file moves forward, so the next number is tried",
            urls.contains("${JioM3uProtocol.SITE}/jiotv-m3u/jtv40.m3u8"))
        assertTrue("and one step back still covers a hasty rebuild",
            urls.contains("${JioM3uProtocol.SITE}/jiotv-m3u/jtv38.m3u8"))
    }

    @Test
    fun `a playlist that answered on this device is asked before the shipped one`() {
        val remembered = "${JioM3uProtocol.SITE}/jiotv-m3u/jtv41.m3u8"
        val urls = JioM3uProtocol.playlistCandidates(remembered)
        assertEquals(remembered, urls.first())
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun `an address from anywhere else is never asked`() {
        for (outside in listOf("https://evil.example/jiotv-m3u/jtv39.m3u8", "https://evil.example/x.m3u8", "")) {
            val urls = JioM3uProtocol.playlistCandidates(outside)
            assertFalse("$outside", urls.any { it.contains("evil.example") })
            assertTrue(urls.all { JioM3uProtocol.isPlaylistAddress(it) })
        }
        assertFalse(JioM3uProtocol.isPlaylistAddress("${JioM3uProtocol.SITE}/playlist/jiotv99.m3u"))
    }
}
