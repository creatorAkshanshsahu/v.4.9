package com.aktv.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MetadataFetchTest {
    private val site = JjtvResolver.SITE
    @Test fun `observed page and same-origin catalogue requests omit Origin and Referer`() {
        for (stage in listOf(JjtvLookupStage.PAGE, JjtvLookupStage.SCRIPT, JjtvLookupStage.CATALOGUE)) {
            val request = JjtvMetadataRequests.request("$site/resource?id=362", site, stage)
            assertNull(request.headers["Origin"]); assertNull(request.headers["Referer"])
            assertEquals("$site/resource?id=362", request.url)
            assertEquals("no-cache, no-store", request.headers["Cache-Control"])
        }
    }
    @Test fun `cross-origin credential metadata uses the JJTV origin without a Referer or invented query`() {
        for (stage in listOf(JjtvLookupStage.PRIMARY_COOKIE, JjtvLookupStage.FALLBACK_COOKIE, JjtvLookupStage.STAR_COOKIE)) {
            val url = "https://tokens.example/data.json?signature=keep%2Bme"
            val request = JjtvMetadataRequests.request(url, site, stage)
            assertEquals(site, request.headers["Origin"]); assertNull(request.headers["Referer"])
            assertEquals(url, request.url)
        }
    }
    @Test fun `first same-origin to foreign redirect is not already an opaque origin`() {
        val origin = FetchOrigin(site, "$site/start")
        assertNull(origin.visit("$site/start"))
        assertEquals(site, origin.visit("https://one.example/next"))
        assertEquals(site, origin.visit("https://one.example/final"))
        assertEquals("null", origin.visit("https://two.example/final"))
        assertEquals("null", origin.visit("$site/back"))
    }
    @Test fun `cross-origin redirect taint is local to each fetch and never applied to navigation`() {
        val origin = FetchOrigin(site, "https://one.example/start")
        assertEquals(site, origin.visit("https://one.example/start"))
        assertEquals("null", origin.visit("https://two.example/end"))
        assertEquals(site, FetchOrigin(site, "https://two.example/new").visit("https://two.example/new"))
        val navigation = FetchOrigin(site, "$site/pind", cors = false)
        assertNull(navigation.visit("$site/pind")); assertNull(navigation.visit("https://one.example/page"))
    }
    @Test fun `metadata wire redirects keep no-referrer and correct CORS origin on each hop`() {
        MockWebServer().use { first -> MockWebServer().use { second -> MockWebServer().use { third ->
            first.start(); second.start(); third.start()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/middle")))
            second.enqueue(MockResponse().setResponseCode(302).setHeader("Location", third.url("/final")))
            third.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]"))
            val origin = first.url("/").toString().removeSuffix("/")
            val request = JjtvMetadataRequests.request(first.url("/start?signature=keep%2Bme").toString(), origin, JjtvLookupStage.CATALOGUE)
            val response = TgHttp().get(request)
            assertEquals(2, response.reply!!.redirects)
            val a = first.takeRequest(2, TimeUnit.SECONDS)!!
            val b = second.takeRequest(2, TimeUnit.SECONDS)!!
            val c = third.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(a.getHeader("Origin")); assertEquals(origin, b.getHeader("Origin")); assertEquals("null", c.getHeader("Origin"))
            for (record in listOf(a, b, c)) { assertNull(record.getHeader("Referer")); assertNull(record.getHeader("Cookie")) }
            assertEquals("/start?signature=keep%2Bme", a.path)
        } } }
    }
    @Test fun `denied metadata retains a redacted response classification but not the error body`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "text/html")
                .setHeader("cf-mitigated", "challenge").setBody("<html>cookie=synthetic-private-value</html>"))
            val response = TgHttp().get(JjtvMetadataRequests.request(server.url("/metadata").toString(), site, JjtvLookupStage.STAR_COOKIE))
            assertEquals(403, response.code); assertEquals("text/html", response.contentType)
            assertTrue(response.body.isEmpty()); assertTrue(response.reply!!.challenge)
            assertEquals(HttpReply.Body.HTML, response.reply!!.body)
            assertFalse(response.reply.toString().contains("synthetic-private-value"))
        }
    }
    @Test fun `Origin header omits default ports but comparison still treats them as the same origin`() {
        val origin = FetchOrigin("$site:443", "$site/catalogue")
        assertNull(origin.visit("$site/catalogue"))
        assertEquals(site, origin.visit("https://tokens.example/data"))
        assertNull(FetchOrigin("not a URL", "https://tokens.example/data").visit("https://tokens.example/data"))
    }

}
