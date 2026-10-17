package com.aktv.app

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class StreamHttpTest {
    @Test fun `native transport follows signed redirects without leaking relay headers or breaking segment queries`() {
        MockWebServer().use { relay ->
            MockWebServer().use { cdn ->
                relay.start(); cdn.start()
                val token = "st=1~exp=4102444800~acl=/*~hmac=redirect-fixture"
                relay.enqueue(MockResponse().setResponseCode(302)
                    .setHeader("Location", cdn.url("/manifest?route=manifest&__hdnea__=$token")))
                cdn.enqueue(MockResponse().setBody("test manifest bytes"))
                cdn.enqueue(MockResponse().setBody("test segment bytes"))

                val start = relay.url("/stream?channel=1106").toString()
                val client = StreamHttp.client(TgStream("TG", start, "dash", headers = mapOf(
                    "Authorization" to "relay-only", "Cookie" to "relay-session", "User-Agent" to "TG current player")))
                client.newCall(Request.Builder().url(start).build()).execute().use {
                    assertTrue(it.isSuccessful); requireNotNull(it.body).string()
                }
                client.newCall(Request.Builder().url(cdn.url("/segment?route=seg&u=a%2Bb"))
                    .header("Range", "bytes=4-7").build()).execute().use {
                    assertTrue(it.isSuccessful); requireNotNull(it.body).string()
                }

                val relayRequest = requireNotNull(relay.takeRequest(2, TimeUnit.SECONDS))
                val manifest = requireNotNull(cdn.takeRequest(2, TimeUnit.SECONDS))
                val segment = requireNotNull(cdn.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("relay-only", relayRequest.getHeader("Authorization"))
                assertEquals("relay-session", relayRequest.getHeader("Cookie"))
                assertEquals("TG current player", relayRequest.getHeader("User-Agent"))
                for (request in listOf(manifest, segment)) {
                    assertNull(request.getHeader("Authorization"))
                    assertNull(request.getHeader("Cookie"))
                    assertEquals(TgHttp.USER_AGENT, request.getHeader("User-Agent"))
                }
                assertEquals("route=seg&u=a%2Bb&__hdnea__=$token", requireNotNull(segment.requestUrl).encodedQuery)
                assertEquals("bytes=4-7", segment.getHeader("Range"))
            }
        }
    }

    @Test fun `a rejected response cannot teach the session a new token`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(403))
            server.start()
            val client = StreamHttp.client(TgStream("TG", server.url("/start").toString(), "dash"))
            client.newCall(Request.Builder().url(server.url("/rejected?__hdnea__=bad")).build()).execute().close()
            client.newCall(Request.Builder().url(server.url("/segment")).build()).execute().close()
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val segment = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertNull(requireNotNull(segment.requestUrl).encodedQuery)
        }
    }

    @Test fun `browser style sends Chrome and omits Referer rather than sending an empty header`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("manifest"))
            server.enqueue(MockResponse().setBody("segment"))
            val source = TgStream("JJTV", server.url("/manifest.mpd").toString(), "dash",
                headers = mapOf("User-Agent" to "plaYtv/7.1.5", "Referer" to "https://www.jiotv.com/"),
                cookie = "__hdnea__=fixture", requestOrigin = JjtvResolver.SITE)
            val browser = JjtvRequestProfiles.variants(source).first()
            val client = StreamHttp.client(browser)
            client.newCall(Request.Builder().url(browser.url).header("Referer", "https://must-not-leak.example/").build()).execute().close()
            client.newCall(Request.Builder().url(server.url("/segment.m4s?keep=a%2Bb")).header("Range", "bytes=0-9").build()).execute().close()
            val manifest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val segment = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            for (request in listOf(manifest, segment)) {
                assertNull(request.getHeader("Referer"))
                assertEquals(TgHttp.USER_AGENT, request.getHeader("User-Agent"))
                assertEquals(JjtvResolver.SITE, request.getHeader("Origin"))
            }
            assertEquals("keep=a%2Bb&__hdnea__=fixture", requireNotNull(segment.requestUrl).encodedQuery)
            assertEquals("bytes=0-9", segment.getHeader("Range"))
        }
    }

    @Test fun `declared native header style remains available for existing working channels`() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("manifest"))
            val source = TgStream("JJTV", server.url("/manifest.mpd").toString(), "dash",
                headers = mapOf("User-Agent" to "plaYtv/7.1.5", "Referer" to "https://www.jiotv.com/"), requestOrigin = JjtvResolver.SITE)
            val native = JjtvRequestProfiles.variants(source)[1]
            StreamHttp.client(native).newCall(Request.Builder().url(native.url).build()).execute().close()
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("plaYtv/7.1.5", request.getHeader("User-Agent"))
            assertEquals("https://www.jiotv.com/", request.getHeader("Referer"))
        }
    }

}
