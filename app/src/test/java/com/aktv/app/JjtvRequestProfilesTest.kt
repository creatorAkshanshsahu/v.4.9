package com.aktv.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JjtvRequestProfilesTest {
    private val source = TgStream("JJTV · Server 1", "https://media.example/live.mpd", "dash",
        drm = JSONObject("""{"type":"clearkey","keyId":"${"a".repeat(32)}","key":"${"b".repeat(32)}"}"""),
        headers = mapOf("User-Agent" to "plaYtv/7.1.5", "Referer" to "https://www.jiotv.com/", "Origin" to JjtvResolver.SITE),
        cookie = "__hdnea__=fixture", requestOrigin = JjtvResolver.SITE)

    @Test fun `actual browser request style precedes the already working native style`() {
        val profiles = JjtvRequestProfiles.variants(source)
        assertEquals(2, profiles.size)
        assertTrue(profiles[0].omitReferrer)
        assertNull(profiles[0].headers["User-Agent"])
        assertNull(profiles[0].headers["Referer"])
        assertFalse(profiles[1].omitReferrer)
        assertEquals(source.headers, profiles[1].headers)
    }

    @Test fun `request styles preserve the same channel URL keys and token`() {
        JjtvRequestProfiles.variants(source).forEach { profile ->
            assertEquals(source.url, profile.url)
            assertSame(source.drm, profile.drm)
            assertEquals(source.cookie, profile.cookie)
            assertEquals(source.requestOrigin, profile.requestOrigin)
        }
        assertEquals("plaYtv/7.1.5", source.headers["User-Agent"])
        assertFalse(source.omitReferrer)
    }

    @Test fun `browser mode ignores forbidden header assignments regardless of case`() {
        val browser = JjtvRequestProfiles.variants(source.copy(headers = mapOf(
            "uSeR-aGeNt" to "ignored", "rEfErEr" to "https://ignored.example/", "Origin" to "https://ignored.example/",
            "X-Source" to "retained"))).first()
        val request = StreamRequestPolicy(browser).request(browser.url)
        assertEquals(JjtvResolver.SITE, request.headers["Origin"])
        assertNull(request.headers["Referer"])
        assertEquals("retained", request.headers["X-Source"])
    }

    @Test fun `saved source identity distinguishes the two styles without storing credentials`() {
        val profiles = JjtvRequestProfiles.variants(source)
        assertNotEquals(LiveProviders.sourceKey(profiles[0]), LiveProviders.sourceKey(profiles[1]))
    }

    @Test fun `TG sources do not acquire JJTV request variants`() {
        val tg = source.copy(requestOrigin = TgResolver.SITE)
        assertEquals(listOf(tg), JjtvRequestProfiles.variants(tg))
    }
}
