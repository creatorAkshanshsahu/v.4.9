package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class LiveProvidersTest {
    @Test fun `untried V1 channels start on JJTV rather than inheriting another channels TG success`() {
        assertEquals(listOf(LiveProvider.JJTV, LiveProvider.TG, LiveProvider.JIOM3U), LiveProviders.order(false, null))
        assertEquals(listOf(LiveProvider.JJTV, LiveProvider.TG, LiveProvider.JIOM3U), LiveProviders.order(false, "https://unknown.example"))
    }

    @Test fun `a known working JJTV source is tried first without removing TG recovery`() {
        assertEquals(listOf(LiveProvider.JJTV, LiveProvider.TG, LiveProvider.JIOM3U), LiveProviders.order(false, JjtvResolver.SITE))
    }

    @Test fun `the playlist provider is only ever a fallback until it is the remembered one`() {
        assertEquals(LiveProvider.JJTV, LiveProviders.order(false, null).first())
        assertEquals(LiveProvider.TG, LiveProviders.order(false, TgResolver.SITE).first())
        assertEquals(LiveProvider.JIOM3U, LiveProviders.order(false, JioM3uResolver.SITE).first())
    }

    @Test fun `V2 must never reinterpret its numeric channel ids as Jio ids even with a JJTV request`() {
        assertEquals(listOf(LiveProvider.TG), LiveProviders.order(true, JjtvResolver.SITE, LiveProvider.JJTV))
    }

    @Test fun `remembered source identity separates providers but does not retain expiring credentials`() {
        val original = TgStream("Server 1", "https://media.example/live.mpd?__hdnea__=first", "dash", cookie = "first")
        val refreshed = original.copy(url = "https://media.example/live.mpd?__hdnea__=second", cookie = "second")
        val otherProvider = original.copy(requestOrigin = JjtvResolver.SITE)
        assertEquals(LiveProviders.sourceKey(original), LiveProviders.sourceKey(refreshed))
        assertNotEquals(LiveProviders.sourceKey(original), LiveProviders.sourceKey(otherProvider))
        assertEquals(64, LiveProviders.sourceKey(original).length)
    }

    @Test fun `a working TG source is retained for that channel only`() {
        assertEquals(listOf(LiveProvider.TG, LiveProvider.JJTV, LiveProvider.JIOM3U), LiveProviders.order(false, TgResolver.SITE))
        assertEquals(listOf(LiveProvider.JJTV, LiveProvider.TG, LiveProvider.JIOM3U), LiveProviders.order(false, null))
    }

    @Test fun `explicit JJTV choice overrides even a previously remembered TG source`() {
        assertEquals(listOf(LiveProvider.JJTV, LiveProvider.TG, LiveProvider.JIOM3U), LiveProviders.order(false, TgResolver.SITE, LiveProvider.JJTV))
        assertEquals(listOf(LiveProvider.TG, LiveProvider.JJTV, LiveProvider.JIOM3U), LiveProviders.order(false, JjtvResolver.SITE, LiveProvider.TG))
    }

    @Test fun `provider identity uses its explicit origin rather than an upstream server label`() {
        val source = TgStream("Server 2", "https://media.example/live.mpd", "dash")
        assertEquals(LiveProvider.TG, LiveProviders.provider(source))
        assertEquals(LiveProvider.JJTV, LiveProviders.provider(source.copy(requestOrigin = JjtvResolver.SITE)))
    }
}
