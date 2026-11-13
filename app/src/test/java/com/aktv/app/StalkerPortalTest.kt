package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The portal's conversation, not its network: a MAG box handshakes for a token, ties it to the device with
 * `get_profile`, then asks for the catalogue and — per channel — for a link. A portal that sat idle answers
 * with an expired token, which has to be renewed exactly once rather than surfacing as a dead section.
 */
class StalkerPortalTest {

    private val account = StalkerAccount("http://portal.example/stalker_portal/c/", "00:1A:79:EE:03:9F", "SERIAL1", "DEVICE1")

    private class FakeHttp(private val answer: (String) -> Pair<Int, String>) : TgTransport {
        val urls = ArrayList<String>()
        val headers = ArrayList<Map<String, String>>()
        override fun get(url: String, headers: Map<String, String>): TgResponse {
            urls.add(url); this.headers.add(headers)
            val (code, body) = answer(url)
            return TgResponse(code, body.toByteArray(Charsets.UTF_8))
        }
        fun actions(): List<String> = urls.map { url -> url.substringAfter("action=").substringBefore('&') }
    }

    private val catalogue = """{"js":{"data":[
        {"id":"101","name":"Arena Sports","cmd":"ffmpeg http://cdn/101","tv_genre_id":"5"},
        {"id":"102","name":"Nova News","cmd":"ffmpeg http://cdn/102","tv_genre_id":"6"}
    ]}}"""

    @Test
    fun `a portal is asked to handshake, then identified, then listed`() {
        val http = FakeHttp { url ->
            when {
                "action=handshake" in url -> 200 to """{"js":{"token":"T1"}}"""
                "action=get_profile" in url -> 200 to """{"js":{"id":"1"}}"""
                "action=get_all_channels" in url -> 200 to catalogue
                else -> 404 to ""
            }
        }
        val list = StalkerPortal(account, http).channels()

        assertEquals(listOf("handshake", "get_profile", "get_all_channels"), http.actions())
        assertEquals(2, list.size)
        assertEquals("Arena Sports", list[0].name)
        // The catalogue call is authorised, and the device identity travels in the cookie and the headers.
        val catalogueCall = http.headers.last()
        assertEquals("Bearer T1", catalogueCall["Authorization"])
        assertTrue("the MAC belongs in the cookie: ${catalogueCall["Cookie"]}",
            catalogueCall["Cookie"].orEmpty().contains("mac=00%3A1A%3A79%3AEE%3A03%3A9F"))
        assertTrue("a portal only answers a set-top box: ${catalogueCall["User-Agent"]}",
            catalogueCall["User-Agent"].orEmpty().contains("MAG200"))
        assertTrue(http.urls.first().startsWith("http://portal.example/stalker_portal/c/server/load.php?"))
    }

    @Test
    fun `an expired token is renewed once and the call is repeated`() {
        var handshakes = 0
        var catalogueCalls = 0
        val http = FakeHttp { url ->
            when {
                "action=handshake" in url -> { handshakes++; 200 to """{"js":{"token":"T$handshakes"}}""" }
                "action=get_profile" in url -> 200 to """{"js":{"id":"1"}}"""
                "action=get_all_channels" in url -> {
                    catalogueCalls++
                    if (catalogueCalls == 1) 200 to """{"js":{"error":"Authorization failed."}}"""
                    else 200 to catalogue
                }
                else -> 404 to ""
            }
        }
        val list = StalkerPortal(account, http).channels()

        assertEquals("the token is renewed exactly once", 2, handshakes)
        assertEquals(2, list.size)
        assertEquals("Bearer T2", http.headers.last()["Authorization"])
    }

    @Test
    fun `a portal catalogue is allowed a bigger body than a website player response`() {
        // get_all_channels is the whole subscription in one document; the 4 MB player budget refused it.
        assertTrue("portal budget ${StalkerPortal.MAX_CATALOGUE_BYTES} vs player ${TgProtocol.MAX_BODY}",
            StalkerPortal.MAX_CATALOGUE_BYTES > TgProtocol.MAX_BODY)
    }

    @Test
    fun `create_link turns a channel handle into a playable url`() {
        val http = FakeHttp { url ->
            when {
                "action=handshake" in url -> 200 to """{"js":{"token":"T1"}}"""
                "action=create_link" in url -> 200 to """{"js":{"cmd":"ffmpeg http://cdn/101/index.m3u8"}}"""
                else -> 200 to """{"js":{}}"""
            }
        }
        assertEquals("http://cdn/101/index.m3u8", StalkerPortal(account, http).link("ffmpeg http://portal/101"))
        assertTrue("the channel handle is sent to the portal",
            http.urls.last().contains("cmd=ffmpeg+http%3A%2F%2Fportal%2F101"))
    }

    @Test
    fun `the api is found one level up when the client path has none`() {
        // Exactly what a real panel does: http://host/stalker_portal/c/ has no server/load.php in it.
        val http = FakeHttp { url ->
            when {
                "action=handshake" in url && url.startsWith("http://portal.example/stalker_portal/server/load.php?") ->
                    200 to """{"js":{"token":"T1"}}"""
                "action=handshake" in url -> 404 to ""
                "action=get_profile" in url -> 200 to """{"js":{"id":"1"}}"""
                "action=get_all_channels" in url -> 200 to catalogue
                else -> 404 to ""
            }
        }
        val portal = StalkerPortal(account, http)

        assertEquals(2, portal.channels().size)
        assertEquals("http://portal.example/stalker_portal/server/load.php", portal.workingEndpoint)
        assertEquals("the nested guess is tried once, then left alone", 1,
            http.urls.count { it.contains("/stalker_portal/c/server/load.php") })
    }

    @Test
    fun `a remembered address is used without rediscovering the portal`() {
        val remembered = "http://portal.example/stalker_portal/server/load.php"
        val http = FakeHttp { url ->
            when {
                "action=handshake" in url -> 200 to """{"js":{"token":"T1"}}"""
                "action=get_profile" in url -> 200 to """{"js":{"id":"1"}}"""
                "action=get_all_channels" in url -> 200 to catalogue
                else -> 404 to ""
            }
        }
        val portal = StalkerPortal(account, http, knownEndpoint = remembered)

        portal.channels()
        assertTrue("every call goes to the remembered address: ${http.urls}",
            http.urls.all { it.startsWith("$remembered?") })
    }

    @Test
    fun `a portal that refuses in words is reported in those words`() {
        val http = FakeHttp { url ->
            if ("action=handshake" in url) 200 to """{"js":{"error":"Device is blocked"}}""" else 404 to ""
        }
        try {
            StalkerPortal(account, http).channels()
            fail("a blocked device must not look like an empty section")
        } catch (e: StalkerException) {
            assertEquals("Device is blocked", e.portalDetail)
        }
    }

    @Test
    fun `a refusal is reported in the portal's own words`() {
        val http = FakeHttp { url ->
            when {
                "action=handshake" in url -> 200 to """{"js":{"token":"T1"}}"""
                "action=get_all_channels" in url -> 200 to """{"js":{"error":"Limit of connections exceeded"}}"""
                else -> 200 to """{"js":{}}"""
            }
        }
        try {
            StalkerPortal(account, http).channels()
            fail("an empty catalogue must not look like a working section")
        } catch (e: StalkerException) {
            assertEquals("Limit of connections exceeded", e.portalDetail)
        }
    }

    @Test
    fun `stale remembered endpoint falls back to discovery and working maxTV defaults match bound serial`() {
        assertEquals("http://new.maxtv.be/stalker_portal/c/", StalkerAccount.DEFAULT_PORTAL)
        assertEquals("00:1A:79:EE:03:9F", StalkerAccount.DEFAULT_MAC)
        assertEquals("92180365D21FE", StalkerAccount.DEFAULT_SERIAL)

        val staleRemembered = "http://portal.example/old/server/load.php"
        val http = FakeHttp { url ->
            when {
                url.startsWith("$staleRemembered?") -> 404 to ""
                url.contains("/stalker_portal/server/load.php") && "action=handshake" in url -> 200 to """{"js":{"token":"T2"}}"""
                url.contains("/stalker_portal/server/load.php") && "action=get_profile" in url -> 200 to """{"js":{"id":"1"}}"""
                url.contains("/stalker_portal/server/load.php") && "action=get_all_channels" in url -> 200 to catalogue
                else -> 404 to ""
            }
        }
        val portal = StalkerPortal(account, http, knownEndpoint = staleRemembered)
        assertEquals(2, portal.channels().size)
        assertEquals("http://portal.example/stalker_portal/server/load.php", portal.workingEndpoint)
        assertTrue(http.headers.last()["Cookie"].orEmpty().contains("timezone=Europe/London"))
    }
}
