package com.aktv.app

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Synthetic request tests for the observed TG/JJTV rules. These do not decrypt or play a channel. */
class TgWebsiteRequestParityTest {
    private val root = "https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_HD1_Hindi_BTS/WDVLive/"
    private fun source(origin: String = TgResolver.SITE) = TgStream("Server 1", root + "index.mpd?__hdnea__=catalog-old", "dash",
        cookie = "__hdnea__=current-channel", requestOrigin = origin)

    @Test fun `TG and JJTV reapply current authentication to every request in their advertised Jio channel`() {
        for (origin in listOf(TgResolver.SITE, JjtvResolver.SITE)) {
            val policy = StreamRequestPolicy(source(origin))
            for (resource in listOf("index.mpd", "init.mp4", "video.m4s", "audio.m4s")) {
                assertEquals(root + resource + "?__hdnea__=current-channel",
                    policy.request(root + resource + "?__hdnea__=embedded-old").url)
            }
        }
    }
    @Test fun `refreshing Jio authentication retains other signed query bytes and exactly one auth parameter`() {
        val policy = StreamRequestPolicy(source())
        val url = root + "video.m4s?__hdnea__=old&sig=a%2fb%2B+c&track=1&__hdnea__=duplicate#part"
        assertEquals(root + "video.m4s?sig=a%2fb%2B+c&track=1&__hdnea__=current-channel#part", policy.request(url).url)
    }
    @Test fun `a Star channel cannot overwrite or lend authentication to a different channel on the same CDN`() {
        val policy = StreamRequestPolicy(source())
        val other = "https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_1_Hindi_BTS/WDVLive/video.m4s"
        assertEquals(other + "?__hdnea__=other-channel", policy.request(other + "?__hdnea__=other-channel").url)
        assertEquals(other, policy.request(other).url)
    }
    @Test fun `a relay keeps its own query and only a successful CDN redirect establishes the channel scope`() {
        val relay = TgStream("Server 2", "https://allinonereborn2.online/jiotv-m3u/jtv.mpd?id=1108", "dash",
            cookie = "__hdnea__=metadata-cookie")
        val policy = StreamRequestPolicy(relay)
        assertEquals(relay.url, policy.request(relay.url).url)
        val segment = root + "video.m4s?__hdnea__=embedded-old"
        assertEquals(segment, policy.request(segment).url)
        policy.observed(relay.url, root + "index.mpd?__hdnea__=redirect-current")
        assertEquals(root + "video.m4s?__hdnea__=redirect-current", policy.request(segment).url)
        assertEquals(relay.url, policy.request(relay.url).url)
    }
    @Test fun `without an explicit website cookie even a Jio manifest cannot override independent child signatures`() {
        val stream = source().copy(cookie = "")
        val policy = StreamRequestPolicy(stream)
        policy.observed(stream.url, stream.url)
        val child = root + "video.m4s?__hdnea__=independent"
        assertEquals(child, policy.request(child).url)
    }
    @Test fun `a non TG or JJTV source does not acquire this website specific query replacement rule`() {
        val stream = source("https://another-player.example")
        val policy = StreamRequestPolicy(stream)
        policy.observed(stream.url, root + "index.mpd?__hdnea__=current-channel")
        val child = root + "video.m4s?__hdnea__=independent"
        assertEquals(child, policy.request(child).url)
    }
    @Test fun `lookalike and alternate CDN origins never receive this channels authentication`() {
        val policy = StreamRequestPolicy(source())
        for (url in listOf(root.replace("jiotvpllive.cdn.jio.com", "jiotvpllive.cdn.jio.com.other.example"),
            root.replace("jiotvpllive", "jiotvmblive"), root.replace("https:", "http:"),
            root.replace(".com/", ".com:8443/"))) {
            assertEquals(url + "video.m4s", policy.request(url + "video.m4s").url)
        }
    }
    @Test fun `an independent generic CDN signature is still preserved with an explicit cookie`() {
        val stream = source().copy(url = "https://media.example/live/index.mpd")
        val policy = StreamRequestPolicy(stream)
        val child = "https://media.example/live/video.m4s?__hdnea__=independent"
        assertEquals(child, policy.request(child).url)
    }

    @Test fun `TG cross-origin relay fetch sends null Origin then direct segments restore the page origin`() {
        MockWebServer().use { relay -> MockWebServer().use { cdn ->
            relay.start(); cdn.start()
            relay.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/index.mpd?__hdnea__=cdn-token")))
            cdn.enqueue(MockResponse().setBody("manifest")); cdn.enqueue(MockResponse().setBody("segment"))
            val stream = TgStream("Server 2", relay.url("/stream?id=1108").toString(), "dash")
            val client = StreamHttp.client(stream)
            client.newCall(Request.Builder().url(stream.url).build()).execute().close()
            client.newCall(Request.Builder().url(cdn.url("/video.m4s")).build()).execute().close()
            val first = relay.takeRequest(2, TimeUnit.SECONDS)!!
            val redirected = cdn.takeRequest(2, TimeUnit.SECONDS)!!
            val segment = cdn.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(TgResolver.SITE, first.getHeader("Origin"))
            assertEquals("null", redirected.getHeader("Origin"))
            assertEquals(TgResolver.SITE, segment.getHeader("Origin"))
            assertEquals(TgResolver.SITE + "/", redirected.getHeader("Referer"))
            assertEquals("cdn-token", segment.requestUrl!!.queryParameter("__hdnea__"))
        } }
    }
    @Test fun `same-origin redirect does not taint the Origin header`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/final.mpd")))
            server.enqueue(MockResponse().setBody("manifest"))
            val stream = TgStream("TG", server.url("/start.mpd").toString(), "dash")
            StreamHttp.client(stream).newCall(Request.Builder().url(stream.url).build()).execute().close()
            assertEquals(TgResolver.SITE, server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Origin"))
            assertEquals(TgResolver.SITE, server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Origin"))
        }
    }
    @Test fun `cross-origin taint survives a redirect back to the original server but not a new request`() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/middle")))
            second.enqueue(MockResponse().setResponseCode(302).setHeader("Location", first.url("/final.mpd")))
            first.enqueue(MockResponse().setBody("manifest")); first.enqueue(MockResponse().setBody("segment"))
            val stream = TgStream("TG", first.url("/start").toString(), "dash")
            val client = StreamHttp.client(stream)
            client.newCall(Request.Builder().url(stream.url).build()).execute().close()
            client.newCall(Request.Builder().url(first.url("/segment")).build()).execute().close()
            assertEquals(TgResolver.SITE, first.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Origin"))
            assertEquals("null", second.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Origin"))
            assertEquals("null", first.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Origin"))
            assertEquals(TgResolver.SITE, first.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Origin"))
        } }
    }
    @Test fun `JJTV browser redirects retain no Referer and taint only the redirect request`() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/final.mpd")))
            second.enqueue(MockResponse().setBody("manifest"))
            val stream = TgStream("JJTV", first.url("/start").toString(), "dash", requestOrigin = JjtvResolver.SITE, omitReferrer = true)
            StreamHttp.client(stream).newCall(Request.Builder().url(stream.url).build()).execute().close()
            val initial = first.takeRequest(2, TimeUnit.SECONDS)!!
            val redirected = second.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(JjtvResolver.SITE, initial.getHeader("Origin"))
            assertEquals("null", redirected.getHeader("Origin"))
            assertNull(initial.getHeader("Referer")); assertNull(redirected.getHeader("Referer"))
        } }
    }
    @Test fun `declared native header profiles are not silently converted to browser redirect behavior`() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/final.mpd")))
            second.enqueue(MockResponse().setBody("manifest"))
            val stream = TgStream("JJTV", first.url("/start").toString(), "dash",
                headers = mapOf("User-Agent" to "declared-player", "Origin" to JjtvResolver.SITE), requestOrigin = JjtvResolver.SITE)
            StreamHttp.client(stream).newCall(Request.Builder().url(stream.url).build()).execute().close()
            first.takeRequest(2, TimeUnit.SECONDS)
            assertEquals(JjtvResolver.SITE, second.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Origin"))
        } }
    }
}
