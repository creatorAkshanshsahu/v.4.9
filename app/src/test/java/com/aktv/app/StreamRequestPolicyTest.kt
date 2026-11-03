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
        policy.observed(relay, signed)
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

    @Test fun `device wall time cannot silently turn a signed session into unsigned segment requests`() {
        val start = "https://media.example/a.mpd?__hdnea__=st=1~exp=20~hmac=fixture"
        val policy = StreamRequestPolicy(TgStream("TG", start, "dash"))
        assertEquals("https://media.example/seg.m4s?__hdnea__=st=1~exp=20~hmac=fixture", policy.request("https://media.example/seg.m4s").url)
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
        policy.observed(url, "https://cdn.example/live.mpd?__hdnea__=cdn-cookie")
        assertEquals("https://cdn.example/segment.m4s?__hdnea__=cdn-cookie", policy.request("https://cdn.example/segment.m4s").url)
    }

    @Test fun `a relay name inside a query does not disable authentication for a normal CDN`() {
        val url = "https://cdn.example/live.mpd?name=allinonereborn"
        val policy = StreamRequestPolicy(TgStream("Server 1", url, "dash", cookie = "__hdnea__=site-cookie"))
        assertEquals(url + "&__hdnea__=site-cookie", policy.request(url).url)
    }


    @Test fun `JJTV provenance survives redirects but its source-specific headers and cookie do not`() {
        val source = TgStream("JJTV", "https://relay.example/live.mpd", "dash",
            headers = mapOf("User-Agent" to "plaYtv/7.1.5", "Referer" to "https://www.jiotv.com/", "Authorization" to "relay-only"),
            cookie = "__hdnea__=relay-cookie", requestOrigin = JjtvResolver.SITE)
        val policy = StreamRequestPolicy(source)
        assertEquals("plaYtv/7.1.5", policy.request(source.url).headers["User-Agent"])
        val redirected = policy.request("https://cdn.example/live.mpd")
        assertEquals(JjtvResolver.SITE, redirected.headers["Origin"])
        assertEquals(JjtvResolver.SITE + "/", redirected.headers["Referer"])
        assertNull(redirected.headers["Authorization"])
        assertNull(redirected.headers["User-Agent"])
        assertEquals("https://cdn.example/live.mpd", redirected.url)
    }

    @Test fun `TG relay exceptions do not replace the JJTV website request policy`() {
        val source = TgStream("JJTV", "https://allinonereborn2.online/fixture.mpd?id=872", "dash",
            cookie = "__hdnea__=jjtv-cookie", requestOrigin = JjtvResolver.SITE)
        assertEquals(source.url + "&__hdnea__=jjtv-cookie", StreamRequestPolicy(source).request(source.url).url)
    }

    @Test fun `JJTV replaces a stale playlist signature with the freshly selected channel cookie`() {
        val source = TgStream("JJTV", "https://jiotvpllive.cdn.jio.com/fixture.mpd?__hdnea__=old&keep=a%2Bb", "dash",
            cookie = "__hdnea__=new-channel-token", requestOrigin = JjtvResolver.SITE)
        assertEquals("https://jiotvpllive.cdn.jio.com/fixture.mpd?keep=a%2Bb&__hdnea__=new-channel-token", StreamRequestPolicy(source).request(source.url).url)
    }

    @Test fun `independent audio authentication cannot poison unsigned video requests`() {
        val policy = StreamRequestPolicy(TgStream("JJTV", url, "dash", requestOrigin = JjtvResolver.SITE))
        val audio = "https://media.example/live/audio.m4s?__hdnea__=audio-only"
        policy.observed(audio, audio)
        assertEquals(token, StreamRequestPolicy.tokenOf(policy.request("https://media.example/live/video.m4s").url))
        assertEquals(audio, policy.request(audio).url)
    }

    @Test fun `the final manifest URI can renew its token after an initial redirect`() {
        val relay = "https://relay.example/stream?id=1108"
        val policy = StreamRequestPolicy(TgStream("JJTV", relay, "dash"))
        val final = "https://media.example/live/index.mpd?__hdnea__=first"
        policy.observed(relay, final)
        policy.observed(final, "https://media.example/live/index.mpd?__hdnea__=renewed")
        assertEquals("renewed", StreamRequestPolicy.tokenOf(policy.request("https://media.example/live/video.m4s").url))
    }

    @Test fun `a manifest fragment omitted by the HTTP stack does not prevent cookie refresh`() {
        val source = TgStream("JJTV", "https://media.example/live.mpd?__hdnea__=old#video", "dash", cookie = "__hdnea__=fresh")
        val policy = StreamRequestPolicy(source)
        assertEquals("fresh", StreamRequestPolicy.tokenOf(policy.request(source.url.substringBefore('#')).url))
    }

    @Test fun `HLS child playlists retain their own explicitly supplied CDN authentication`() {
        val master = "https://master.example/live.m3u8?__hdnea__=master"
        val policy = StreamRequestPolicy(TgStream("TG", master, "hls"))
        val variant = "https://video.example/variant.m3u8?__hdnea__=video-playlist"
        policy.observed(variant, variant, childPlaylist = true)
        assertEquals("video-playlist", StreamRequestPolicy.tokenOf(policy.request("https://video.example/segment.ts").url))
        val segment = "https://video.example/one.ts?__hdnea__=one-segment"
        policy.observed(segment, segment)
        assertEquals("video-playlist", StreamRequestPolicy.tokenOf(policy.request("https://video.example/two.ts").url))
        assertEquals("master", StreamRequestPolicy.tokenOf(policy.request("https://master.example/other.ts").url))
    }

    @Test fun `a DASH session cannot learn credentials from an unrelated playlist response`() {
        val policy = StreamRequestPolicy(TgStream("JJTV", url, "dash", requestOrigin = JjtvResolver.SITE))
        val other = "https://other.example/list.m3u8?__hdnea__=other"
        policy.observed(other, other, childPlaylist = true)
        assertEquals("https://other.example/segment", policy.request("https://other.example/segment").url)
    }

}
