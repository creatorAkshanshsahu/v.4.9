package com.aktv.app

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JjtvResolverTest {
    private val site = JjtvResolver.SITE
    private fun response(body: String, code: Int = 200) = TgResponse(code, body.toByteArray())
    private fun page(catalog: String = "/channels.json") = """<script>
        const CHANNELS_URL = '$catalog'; const COOKIE_PRIMARY = 'https://tokens.example/general.json';
        const COOKIE_FALLBACK = ''; const COOKIE_STAR_SPORTS = 'https://tokens.example/star.json';
        request.headers['Referer'] = 'https://www.jiotv.com/'; request.headers['User-Agent'] = 'plaYtv/7.1.5';
        </script><script src='https://ads.example/tracker.js'></script>"""
    private fun channel(id: String, star: Boolean = false, version: Int = 1) = """{"id":"$id","name":"Channel $id",
        "url":"https://${if (star) "jiotvpllive.cdn.jio.com" else "media.example"}/fixture-$id-v$version.mpd?__hdnea__=old",
        "keys":[{"keyId":"${"1".repeat(32)}","key":"${"2".repeat(32)}"},{"keyId":"${"3".repeat(32)}","key":"${"4".repeat(32)}"}]}"""
    private fun primary(value: String = "general") = """[{"last_updated":"today"},{"cookie":"__hdnea__=$value"}]"""
    private fun stars(id: String) = """{"failed_results":[{"channel_id":"$id","error_details":{"http_code":450,"final_url":"https://jiotvpllive.cdn.jio.com/fixture-$id.mpd?__hdnea__=channel-$id"}}]}"""
    private class Fake(val handler: (String, Map<String, String>) -> TgResponse) : TgTransport {
        val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>): TgResponse {
            requests.add(url to headers.toMap())
            return handler(url, headers)
        }
        fun count(path: String) = requests.count { URI(it.first).path == path }
    }

    @Test fun `Sony Hindi resolves exact keys and fresh general cookie without scripts or adverts`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("[${channel("872")},${channel("873")}]")
            "/general.json" -> response(primary())
            else -> throw AssertionError("Unexpected metadata endpoint")
        } }
        val source = JjtvResolver(http).resolve("872").single()
        assertTrue(source.url.contains("fixture-872-"))
        assertEquals(2, ClearKey.pairs(requireNotNull(source.drm)).size)
        assertEquals("__hdnea__=general", source.cookie)
        assertEquals(site, source.requestOrigin)
        assertEquals("plaYtv/7.1.5", source.headers["User-Agent"])
        assertEquals(3, http.requests.size)
        assertTrue(http.requests.all { it.second["Origin"] == site && it.second["Referer"] == "$site/pind?id=872" })
        assertTrue(http.requests.all { it.second["Cache-Control"] == "no-cache, no-store" })
    }

    @Test fun `Star never requests or substitutes the general cookie endpoint`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("[${channel("1106", star = true)}]")
            "/star.json" -> response(stars("1106"))
            else -> throw AssertionError("PL sources must not use a general cookie or an embed")
        } }
        val source = JjtvResolver(http).resolve("1106").single()
        assertEquals("__hdnea__=channel-1106", source.cookie)
        assertEquals(2, ClearKey.pairs(requireNotNull(source.drm)).size)
        assertEquals(0, http.count("/general.json"))
    }

    @Test fun `each requested Star variant takes only its exact id from a shared cookie document`() {
        val ids = listOf("1106", "1108", "1984", "3277")
        val catalog = ids.joinToString(prefix = "[", postfix = "]") { channel(it, star = true) }
        val tokenData = ids.joinToString(prefix = "{\"successful_results\":[", postfix = "]}") {
            """{"channel_id":"$it","final_url":"https://cdn.example/a?__hdnea__=only-$it"}"""
        }
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response(catalog)
            "/star.json" -> response(tokenData)
            else -> throw AssertionError("Unexpected metadata endpoint")
        } }
        val resolver = JjtvResolver(http)
        ids.forEach { id -> assertEquals("__hdnea__=only-$id", resolver.resolve(id).single().cookie) }
        assertEquals(1, http.count("/star.json"))
        assertEquals(1, http.count("/channels.json"))
    }

    @Test fun `a missing Star cookie is not negatively cached and cannot borrow another channel`() {
        var available = false
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("[${channel("1106", star = true)}]")
            "/star.json" -> response(stars(if (available) "1106" else "1108"))
            else -> throw AssertionError("No general cookie fallback is allowed")
        } }
        val resolver = JjtvResolver(http)
        try { resolver.resolve("1106"); fail("Expected missing channel-specific credential") } catch (_: IOException) { }
        available = true
        assertEquals("__hdnea__=channel-1106", resolver.resolve("1106").single().cookie)
        assertEquals(2, http.count("/star.json"))
    }

    @Test fun `source and cookie caches expire before the selected signed token`() {
        var clock = 1_000L
        var exp = 15
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("[${channel("872")}]")
            "/general.json" -> response(primary("st=1~exp=$exp~hmac=fixture"))
            else -> throw AssertionError("Unexpected metadata endpoint")
        } }
        val resolver = JjtvResolver(http, now = { clock })
        resolver.resolve("872")
        clock = 3_000; resolver.resolve("872")
        assertEquals(1, http.count("/general.json"))
        clock = 6_000; exp = 100
        assertTrue(resolver.resolve("872").single().cookie.contains("exp=100"))
        assertEquals(2, http.count("/general.json"))
        assertEquals(1, http.count("/channels.json"))
    }

    @Test fun `zapping to another channel does not extend the original credential cache lifetime`() {
        var clock = 1_000L
        var generation = 1
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("[${channel("872")},${channel("873")}]")
            "/general.json" -> response(primary("generation-$generation"))
            else -> throw AssertionError("Unexpected metadata endpoint")
        } }
        val resolver = JjtvResolver(http, now = { clock })
        resolver.resolve("872")
        clock = 20_000; resolver.resolve("873")
        assertEquals(1, http.count("/general.json"))
        clock = 31_001; generation = 2
        assertEquals("__hdnea__=generation-2", resolver.resolve("873").single().cookie)
        assertEquals(2, http.count("/general.json"))
    }

    @Test fun `manual refresh discovers changed endpoints instead of keeping old URLs or keys`() {
        var version = 1
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page("/channels-$version.json"))
            "/channels-1.json", "/channels-2.json" -> response("[${channel("872", version = version)}]")
            "/general.json" -> response(primary())
            else -> throw AssertionError("Unexpected metadata endpoint")
        } }
        val resolver = JjtvResolver(http)
        assertTrue(resolver.resolve("872").single().url.contains("v1.mpd"))
        version = 2
        assertTrue(resolver.resolve("872", fresh = true).single().url.contains("v2.mpd"))
        assertEquals(2, http.count("/pind"))
        assertEquals(1, http.count("/channels-2.json"))
    }

    @Test fun `a changed player config invalidates a still-young catalogue from the old endpoint`() {
        var clock = 1_000L
        var version = 1
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page("/channels-$version.json"))
            "/channels-1.json", "/channels-2.json" -> response("[${channel("872", version = version)},${channel("873", version = version)}]")
            "/general.json" -> response(primary())
            else -> throw AssertionError("Unexpected metadata endpoint")
        } }
        val resolver = JjtvResolver(http, now = { clock })
        resolver.resolve("872")
        clock = 290_000; resolver.resolve("873")
        clock = 302_000; version = 2
        assertTrue(resolver.resolve("872").single().url.contains("v2.mpd"))
        assertEquals(1, http.count("/channels-2.json"))
    }

    @Test fun `a lazy same-origin player chunk can supply redeployed configuration`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response("""<script src='/assets/player-latest.js'></script><script src='https://ads.example/ad.js'></script>""")
            "/assets/player-latest.js" -> response(page())
            "/channels.json" -> response("[${channel("872")}]")
            "/general.json" -> response(primary())
            else -> throw AssertionError("Only current same-site player configuration may be followed")
        } }
        assertTrue(JjtvResolver(http).resolve("872").single().url.contains("fixture-872"))
        assertEquals(1, http.count("/assets/player-latest.js"))
    }

    @Test fun `missing exact id does not remap Hindi to a neighbouring language`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("[${channel("873")}]")
            else -> throw AssertionError("Must stop before looking up another channel's credentials")
        } }
        try { JjtvResolver(http).resolve("872"); fail("Expected exact-ID failure") } catch (_: IOException) { }
    }

    @Test(expected = IllegalArgumentException::class) fun `ids cannot inject request paths or queries`() {
        JjtvResolver(Fake { _, _ -> throw AssertionError("No request expected") }).resolve("../872?other=1")
    }

    @Test fun `cancelled metadata resolution cannot return a previous channel`() {
        val entered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val http = Fake { _, _ -> entered.countDown(); Thread.sleep(30_000); response(page()) }
        try {
            val future = executor.submit<List<TgStream>> { JjtvResolver(http).resolve("872") }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            future.cancel(true); executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(future.isCancelled)
            assertEquals(1, http.requests.size)
        } finally { executor.shutdownNow() }
    }
}
