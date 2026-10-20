package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestProfileFallbackTest {
    private fun source(origin: String, browser: Boolean, label: String) = TgStream(
        label, "https://media.example/bpk-tv/chan/live.mpd", "dash",
        requestOrigin = origin, omitReferrer = browser
    )

    private val jjtv = JjtvResolver.SITE
    private val browser = source(jjtv, true, "JJTV · Server 1 · browser request")
    private val native = source(jjtv, false, "JJTV · Server 1 · native request")

    @Test fun `a refused browser-style request moves to the declared native style`() {
        assertEquals(1, RequestProfileFallback.alternative(listOf(browser, native), browser))
    }

    @Test fun `a refused native-style request moves back to the browser style`() {
        assertEquals(0, RequestProfileFallback.alternative(listOf(browser, native), native))
    }

    @Test fun `another provider is never treated as the alternative style`() {
        val tgBrowser = source(TgResolver.SITE, true, "Server 1")
        assertEquals(-1, RequestProfileFallback.alternative(listOf(tgBrowser), browser))
    }

    @Test fun `a single advertised style keeps the previous source identity`() {
        val only = listOf(browser)
        assertEquals(0, RequestProfileFallback.preferredIndex(only, browser) { it.label == browser.label })
    }

    @Test fun `the alternative style wins over the same source when both exist`() {
        val sources = listOf(browser, native)
        assertEquals(1, RequestProfileFallback.preferredIndex(sources, browser) { it.label == browser.label })
    }

    @Test fun `a single advertised request style clamps to the first source instead of -1`() {
        // Every TG channel advertises one request style, so no alternative exists and the
        // delayed-retry caller shape ({ false }) must still receive a usable index.
        val tgOnly = listOf(source(TgResolver.SITE, false, "Server 1"), source(TgResolver.SITE, false, "Server 2"))
        val refused = tgOnly[1]
        assertEquals(-1, RequestProfileFallback.alternative(tgOnly, refused))
        val index = RequestProfileFallback.preferredIndex(tgOnly, refused) { false }
        assertEquals(0, index)
        assertTrue(index in tgOnly.indices)
    }

    @Test fun `preferredIndex never returns a negative index for any caller shape`() {
        val tgOnly = listOf(source(TgResolver.SITE, false, "Server 1"))
        for (refused in listOf(tgOnly[0], null)) {
            val index = RequestProfileFallback.preferredIndex(tgOnly, refused) { false }
            assertEquals(0, index)
            assertTrue(index in tgOnly.indices)
        }
    }

    @Test fun `no refused source falls back to the previous identity instead of crashing`() {
        assertEquals(1, RequestProfileFallback.preferredIndex(listOf(browser, native), null) { it.label == native.label })
        assertEquals(-1, RequestProfileFallback.alternative(listOf(browser, native), null))
    }
}
