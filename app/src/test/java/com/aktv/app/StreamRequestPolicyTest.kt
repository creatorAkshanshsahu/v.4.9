package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class StreamRequestPolicyTest {
    private val token = "st=1~exp=4102444800~acl=/*~hmac=fixture"
    private val url = "https://media.example/live/a.mpd?channel=872&__hdnea__=$token"

    @Test fun `manifest token follows HLS playlists and DASH segments on the same origin`() {
        val policy = StreamRequestPolicy(TgStream("TG", url, "dash"))
        for (path in listOf("variant.m3u8", "video.m4s", "init.mp4", "key.bin")) {
            assertEquals("https://media.example/live/$path?__hdnea__=$token", policy.request("https://media.example/live/$path").url)
        }
    }

    @Test fun `relay query parameters and encoding are not overwritten by a cookie token`() {
        val url = "https://relay.example/playlist.php?route=seg&u=a%2Fb%2Bc&signature=keep+me"
        val policy = StreamRequestPolicy(TgStream("TG", url, "hls", cookie = "__hdnea__=$token"))
        assertEquals("$url&__hdnea__=$token", policy.request(url).url)
    }

    @Test fun `explicit source cookie refreshes only the initial token and leaves its other parameters`() {
        val start = "https://media.example/a.mpd?__hdnea__=old&signature=keep%2b"
        val policy = StreamRequestPolicy(TgStream("TG", start, "dash", cookie = "__hdnea__=$token"))
        assertEquals("https://media.example/a.mpd?signature=keep%2b&__hdnea__=$token", policy.request(start).url)
        val independentlySigned = "https://media.example/seg.m4s?__hdnea__=its-own-token&x=1"
        assertEquals(independentlySigned, policy.request(independentlySigned).url)
    }

    @Test fun `token and credentials never follow an unrelated origin or a lookalike host`() {
        val policy = StreamRequestPolicy(TgStream("TG", url, "dash", headers = mapOf("Authorization" to "source-secret", "Cookie" to "source-cookie", "X-Token" to "opaque")))
        for (other in listOf("https://other.example/seg", "https://media.example.evil.test/seg", "http://media.example/seg", "https://media.example:8443/seg")) {
            val request = policy.request(other)
            assertEquals(other, request.url)
            assertFalse(request.headers.containsKey("Authorization"))
            assertFalse(request.headers.containsKey("Cookie"))
            assertFalse(request.headers.containsKey("X-Token"))
        }
        assertEquals("source-secret", policy.request(url).headers["Authorization"])
    }

    @Test fun `Star style redirect learns fresh path-scoped auth from the final manifest URL`() {
        val relay = "https://relay.example/api/public/stream/1106"
        val policy = StreamRequestPolicy(TgStream("TG", relay, "dash"))
        assertEquals(relay, policy.request(relay).url)
        val signed = "https://cdn.example/bpk-tv/Star/WDVLive/index.mpd?__hdnea__=$token"
        policy.observed(signed)
        assertEquals("https://cdn.example/bpk-tv/Star/WDVLive/video.m4s?__hdnea__=$token",
            policy.request("https://cdn.example/bpk-tv/Star/WDVLive/video.m4s").url)
        assertEquals(relay, policy.request(relay).url)
        assertEquals("https://third.example/video.m4s", policy.request("https://third.example/video.m4s").url)
    }

    @Test fun `signed URL path query and fragment are preserved`() {
        val exact = "https://media.example//live/%2f/a.mpd?x=%2B&__hdnea__=$token#fragment"
        val policy = StreamRequestPolicy(TgStream("TG", exact, "dash"))
        assertEquals(exact, policy.request(exact).url)
    }

    @Test fun `expired auth is not added to a new segment`() {
        val start = "https://media.example/a.mpd?__hdnea__=st=1~exp=20~hmac=fixture"
        val policy = StreamRequestPolicy(TgStream("TG", start, "dash")) { 21_000L }
        assertEquals("https://media.example/seg.m4s", policy.request("https://media.example/seg.m4s").url)
    }

    @Test fun `expiry supports percent encoded tokens and explicit cookies`() {
        assertEquals(20_000L, StreamRequestPolicy.expiry("https://media.example/a?__hdnea__=st%3D1~exp%3D20~hmac%3Dfixture"))
        assertEquals(25_000L, StreamRequestPolicy.expiry(url, "__hdnea__=st=1~exp=25~hmac=fixture; Path=/"))
    }

    @Test fun `concurrent preview and live sessions never share tokens`() {
        val live = StreamRequestPolicy(TgStream("TG", url, "dash"))
        val preview = StreamRequestPolicy(TgStream("TG", "https://media.example/preview.mpd?__hdnea__=preview", "dash"))
        assertEquals("preview", StreamRequestPolicy.tokenOf(preview.request("https://media.example/seg").url))
        assertEquals(token, StreamRequestPolicy.tokenOf(live.request("https://media.example/seg").url))
    }

    @Test fun `current player headers are honored without allowing HTTP header injection`() {
        val policy = StreamRequestPolicy(TgStream("TG", url, "dash", headers = mapOf(
            "User-Agent" to "current-player", "referer" to "https://player.example/", "Origin" to "https://player.example",
            "X-Bad" to "value\r\nInjected: header", "Host" to "other.example"
        )))
        val request = policy.request(url)
        assertEquals("current-player", request.headers["User-Agent"])
        assertEquals("https://player.example/", request.headers["referer"])
        assertFalse(request.headers.containsKey("Referer"))
        assertFalse(request.headers.containsKey("X-Bad"))
        assertFalse(request.headers.containsKey("Host"))
    }

    @Test fun `the website relay keeps its own query even when TG repeats a Jio cookie`() {
        val url = "https://allinonereborn2.online/jiotv-m3u/jtv.mpd?id=872"
        val policy = StreamRequestPolicy(TgStream("Server 2", url, "dash", cookie = "__hdnea__=site-cookie"))
        assertEquals(url, policy.request(url).url)
        assertEquals("https://allinonereborn2.online/media.m4s?id=keep", policy.request("https://allinonereborn2.online/media.m4s?id=keep").url)
        // A successful redirect can still supply the real CDN's independent credentials.
        policy.observed("https://cdn.example/live.mpd?__hdnea__=cdn-cookie")
        assertEquals("https://cdn.example/segment.m4s?__hdnea__=cdn-cookie", policy.request("https://cdn.example/segment.m4s").url)
    }

    @Test fun `a relay name inside a query does not disable authentication for a normal CDN`() {
        val url = "https://cdn.example/live.mpd?name=allinonereborn"
        val policy = StreamRequestPolicy(TgStream("Server 1", url, "dash", cookie = "__hdnea__=site-cookie"))
        assertEquals(url + "&__hdnea__=site-cookie", policy.request(url).url)
    }

}
