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
        val sources = JjtvResolver(http).resolve("872")
        val source = sources.first()
        assertEquals(2, sources.size)
        assertTrue(source.omitReferrer)
        assertTrue(source.url.contains("fixture-872-"))
        assertEquals(2, ClearKey.pairs(requireNotNull(source.drm)).size)
        assertEquals("__hdnea__=general", source.cookie)
        assertEquals(site, source.requestOrigin)
        assertNull(source.headers["User-Agent"])
        assertEquals("plaYtv/7.1.5", sources[1].headers["User-Agent"])
        assertEquals(3, http.requests.size)
        assertTrue(http.requests.all { it.second["Referer"] == null })
        assertTrue(http.requests.filter { URI(it.first).host == URI(site).host }.all { it.second["Origin"] == null })
        assertEquals(site, http.requests.first { URI(it.first).path == "/general.json" }.second["Origin"])
        assertEquals("id=872", URI(http.requests.first().first).query)
        assertTrue(http.requests.all { it.second["Cache-Control"] == "no-cache, no-store" })
    }

    @Test fun `Star never requests or substitutes the general cookie endpoint`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("[${channel("1106", star = true)}]")
            "/star.json" -> response(stars("1106"))
            else -> throw AssertionError("PL sources must not use a general cookie or an embed")
        } }
        val source = JjtvResolver(http).resolve("1106").first()
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
        ids.forEach { id -> assertEquals("__hdnea__=only-$id", resolver.resolve(id).first().cookie) }
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
        assertEquals("__hdnea__=channel-1106", resolver.resolve("1106").first().cookie)
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
        val resolver = JjtvResolver(http, now = { clock }, elapsed = { clock })
        resolver.resolve("872")
        clock = 3_000; resolver.resolve("872")
        assertEquals(1, http.count("/general.json"))
        clock = 6_000; exp = 100
        assertTrue(resolver.resolve("872").first().cookie.contains("exp=100"))
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
        val resolver = JjtvResolver(http, now = { clock }, elapsed = { clock })
        resolver.resolve("872")
        clock = 20_000; resolver.resolve("873")
        assertEquals(1, http.count("/general.json"))
        clock = 31_001; generation = 2
        assertEquals("__hdnea__=generation-2", resolver.resolve("873").first().cookie)
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
        assertTrue(resolver.resolve("872").first().url.contains("v1.mpd"))
        version = 2
        assertTrue(resolver.resolve("872", fresh = true).first().url.contains("v2.mpd"))
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
        val resolver = JjtvResolver(http, now = { clock }, elapsed = { clock })
        resolver.resolve("872")
        clock = 290_000; resolver.resolve("873")
        clock = 302_000; version = 2
        assertTrue(resolver.resolve("872").first().url.contains("v2.mpd"))
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
        assertTrue(JjtvResolver(http).resolve("872").first().url.contains("fixture-872"))
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
    @Test fun `a refused JJTV page exposes a typed HTTP status rather than a decoder error`() {
        val http = Fake { _, _ -> TgResponse(403, ByteArray(0)) }
        try { JjtvResolver(http).resolve("1108"); fail("Expected the page rejection") }
        catch (error: JjtvHttpException) { assertEquals(403, error.status) }
    }

    @Test fun `a nearly expired token fetched from the network is refreshed before slow decoder startup`() {
        var cookieCalls = 0
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("[${channel("872")}]" )
            "/general.json" -> response(primary(if (++cookieCalls == 1) "exp=105~hmac=old" else "exp=500~hmac=current"))
            else -> throw AssertionError("Unexpected endpoint")
        } }
        val source = JjtvResolver(http, now = { 100_000 }, elapsed = { 0 }).resolve("872").first()
        assertTrue(source.cookie.contains("hmac=current"))
        assertEquals(2, cookieCalls)
        assertNull(URI(http.requests.last().first).query)
        assertEquals("no-cache, no-store", http.requests.last().second["Cache-Control"])
    }

    @Test fun `a cookie service that keeps returning stale data is fetched at most twice`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("[${channel("872")}]" )
            "/general.json" -> response(primary("exp=50~hmac=old"))
            else -> throw AssertionError("Unexpected endpoint")
        } }
        val source = JjtvResolver(http, now = { 100_000 }, elapsed = { 0 }).resolve("872").first()
        assertEquals(2, http.count("/general.json"))
        // The CDN decides validity. The client neither makes up a new token nor strips the supplied one.
        assertEquals("exp=50~hmac=old", StreamRequestPolicy.tokenOf(StreamRequestPolicy(source).request(source.url).url))
    }

    @Test fun `HTTP time handles a TV whose wall clock is far ahead and still reuses only a short cache`() {
        var elapsed = 0L
        val wall = 4_102_444_800_000L
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page()).copy(serverTimeMs = 100_000 + elapsed)
            "/channels.json" -> response("[${channel("872")}]" ).copy(serverTimeMs = 100_000 + elapsed)
            "/general.json" -> response(primary("exp=500~hmac=current")).copy(serverTimeMs = 100_000 + elapsed)
            else -> throw AssertionError("Unexpected endpoint")
        } }
        val resolver = JjtvResolver(http, now = { wall }, elapsed = { elapsed })
        val first = resolver.resolve("872")
        elapsed = 10_000
        assertSame(first, resolver.resolve("872"))
        assertEquals(1, http.count("/general.json"))
        elapsed = 31_000
        resolver.resolve("872")
        assertEquals(2, http.count("/general.json"))
    }

    @Test fun `moving the wall clock backwards cannot keep channel credentials cached indefinitely`() {
        var wall = 1_000_000L; var elapsed = 0L
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("[${channel("872")}]" )
            "/general.json" -> response(primary())
            else -> throw AssertionError("Unexpected endpoint")
        } }
        val resolver = JjtvResolver(http, now = { wall }, elapsed = { elapsed })
        resolver.resolve("872")
        wall = 1; elapsed = 31_000
        resolver.resolve("872")
        assertEquals(2, http.count("/general.json"))
    }

    @Test fun `explicit refresh uses canonical metadata URLs and no-store rather than invented query parameters`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("[${channel("872")}]" )
            "/general.json" -> response(primary())
            else -> throw AssertionError("Unexpected endpoint")
        } }
        val source = JjtvResolver(http).resolve("872", fresh = true).first()
        assertTrue(http.requests.filter { URI(it.first).path in setOf("/channels.json", "/general.json") }
            .all { URI(it.first).query == null && it.second["Cache-Control"] == "no-cache, no-store" })
        assertFalse(source.url.contains("_aktv_refresh"))
        assertTrue(source.url.endsWith("__hdnea__=old"))
    }

    @Test fun `an endpoint owning a signed query is preserved on every metadata refresh`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page("/channels.json?signature=keep%2Bme"))
            "/channels.json" -> response("[${channel("872")}]" )
            "/general.json" -> response(primary())
            else -> throw AssertionError("Unexpected endpoint")
        } }
        JjtvResolver(http).resolve("872", fresh = true)
        assertTrue(http.requests.first { URI(it.first).path == "/channels.json" }.first.endsWith("?signature=keep%2Bme"))
    }

    @Test fun `a refused Star cookie endpoint retains its HTTP error instead of a generic empty-source error`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("[${channel("1108", star = true)}]" )
            "/star.json" -> TgResponse(503, ByteArray(0))
            else -> throw AssertionError("Unexpected endpoint")
        } }
        try { JjtvResolver(http).resolve("1108"); fail("Expected the token endpoint failure") }
        catch (error: JjtvHttpException) { assertEquals(503, error.status) }
    }

    @Test fun `HTTP denials identify page catalogue and Star cookie stages without response credentials`() {
        for (stage in listOf(JjtvLookupStage.PAGE, JjtvLookupStage.CATALOGUE, JjtvLookupStage.STAR_COOKIE)) {
            val http = Fake { url, _ ->
                val path = URI(url).path
                val denied = when (stage) { JjtvLookupStage.PAGE -> path == "/pind"
                    JjtvLookupStage.CATALOGUE -> path == "/channels.json"; else -> path == "/star.json" }
                if (denied) TgResponse(403, "<html>token=synthetic-secret</html>".toByteArray(), "text/html")
                else when (path) { "/pind" -> response(page()); "/channels.json" -> response("[${channel("1108", star = true)}]")
                    else -> throw AssertionError("Only the advertised required endpoint should be requested") }
            }
            val error = assertThrows(JjtvHttpException::class.java) { JjtvResolver(http).resolve("1108") }
            assertEquals(stage, error.stage); assertEquals(403, error.status)
            assertEquals(HttpReply.Body.HTML, error.reply!!.body)
            assertFalse(error.message.orEmpty().contains("synthetic-secret"))
        }
    }
    @Test fun `an HTML catalogue with HTTP 200 is a metadata format error before keys or media are touched`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/pind" -> response(page())
            "/channels.json" -> response("<html>not a catalogue</html>")
            else -> throw AssertionError("No cookie or media request should be made")
        } }
        val error = assertThrows(JjtvDataException::class.java) { JjtvResolver(http).resolve("872") }
        assertEquals(JjtvLookupStage.CATALOGUE, error.stage)
        assertEquals(200, error.reply!!.status); assertEquals(HttpReply.Body.HTML, error.reply!!.body)
    }
    @Test fun `a declared challenge is reported not executed or followed as player code`() {
        val reply = HttpReply.of(200, "text/html", "<html>challenge</html>".toByteArray(), challenge = true)
        val http = Fake { _, _ -> response("<script src='/challenge.js'></script>").copy(reply = reply) }
        val error = assertThrows(JjtvDataException::class.java) { JjtvResolver(http).resolve("872") }
        assertEquals(JjtvLookupStage.PAGE, error.stage); assertTrue(error.reply!!.challenge)
        assertEquals(1, http.requests.size)
    }

}
