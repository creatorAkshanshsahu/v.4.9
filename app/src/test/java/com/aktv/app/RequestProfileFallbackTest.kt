package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestProfileFallbackTest {
    private fun source(origin: String, browser: Boolean, label: String) = TgStream(
        label, "https://media.example/bpk-tv/chan/live.mpd", "dash",
        requestOrigin = origin, omitReferrer = browser
    )

    private val jjtv = JjtvResolver.SITE
    private val browser = source(jjtv, true, "JJTV · Server 1 · browser request")
    private val native = source(jjtv, false, "JJTV · Server 1 · native request")

    @Test fun `JJTV starts with the observed browser profile instead of a remembered failing native profile`() {
        assertEquals(0, RequestProfileFallback.initialIndex(listOf(browser, native), rememberedIndex = 1))
        assertEquals(0, RequestProfileFallback.initialIndex(listOf(browser, native), rememberedIndex = -1))
        assertEquals(0, RequestProfileFallback.initialIndex(listOf(browser, native), rememberedIndex = 0))
        assertEquals(1, RequestProfileFallback.initialIndex(listOf(native), rememberedIndex = 1))
    }

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

    @Test fun `a single advertised style clamps a missing previous identity to index zero`() {
        val only = listOf(browser)
        assertEquals(0, RequestProfileFallback.preferredIndex(only, browser) { false })
    }

    @Test fun `the alternative style wins over the same source when both exist`() {
        val sources = listOf(browser, native)
        assertEquals(1, RequestProfileFallback.preferredIndex(sources, browser) { it.label == browser.label })
    }

    @Test fun `no refused source falls back to the previous identity instead of crashing`() {
        assertEquals(1, RequestProfileFallback.preferredIndex(listOf(browser, native), null) { it.label == native.label })
        assertEquals(-1, RequestProfileFallback.alternative(listOf(browser, native), null))
    }
}
