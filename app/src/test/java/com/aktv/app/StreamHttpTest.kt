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
}
