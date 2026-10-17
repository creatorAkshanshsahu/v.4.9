package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class LiveProvidersTest {
    @Test fun `TG is first until another native provider actually renders a frame`() {
        assertEquals(listOf(LiveProvider.TG, LiveProvider.JJTV), LiveProviders.order(false, null))
        assertEquals(listOf(LiveProvider.TG, LiveProvider.JJTV), LiveProviders.order(false, "https://unknown.example"))
    }

    @Test fun `a known working JJTV source is tried first without removing TG recovery`() {
        assertEquals(listOf(LiveProvider.JJTV, LiveProvider.TG), LiveProviders.order(false, JjtvResolver.SITE))
    }

    @Test fun `V2 must never reinterpret its numeric channel ids as Jio ids`() {
        assertEquals(listOf(LiveProvider.TG), LiveProviders.order(true, JjtvResolver.SITE))
    }

    @Test fun `remembered source identity separates providers but does not retain expiring credentials`() {
        val original = TgStream("Server 1", "https://media.example/live.mpd?__hdnea__=first", "dash", cookie = "first")
        val refreshed = original.copy(url = "https://media.example/live.mpd?__hdnea__=second", cookie = "second")
        val otherProvider = original.copy(requestOrigin = JjtvResolver.SITE)
        assertEquals(LiveProviders.sourceKey(original), LiveProviders.sourceKey(refreshed))
        assertNotEquals(LiveProviders.sourceKey(original), LiveProviders.sourceKey(otherProvider))
        assertEquals(64, LiveProviders.sourceKey(original).length)
    }
}
