package com.aktv.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TgResolverTest {
    private val old = "a".repeat(64)
    private val updated = "b".repeat(64)
    private val site = TgResolver.SITE
    private fun response(body: String, code: Int = 200, type: String = "application/json") = TgResponse(code, body.toByteArray(), type)
    private fun stream(id: String) = """{"id":"$id", "sources":["https://media.example/$id.mpd"],"drm":null}"""
    private fun idFromPayload(url: String): String {
        val payload = URI(url).rawQuery.split('&').first { it.startsWith("payload=") }.substringAfter('=')
        return JSONObject(URLDecoder.decode(payload, "UTF-8")).getJSONObject("t").getJSONObject("p")
            .getJSONArray("v").getJSONObject(0).getJSONObject("p").getJSONArray("v").getJSONObject(0).getString("s")
    }
    private class Fake(val handler: (String, Map<String, String>) -> TgResponse) : TgTransport {
        val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>): TgResponse {
            requests.add(url to headers.toMap())
            return handler(url, headers)
        }
    }

    @Test fun `known player function starts without contacting a backup or the public API`() {
        val http = Fake { url, _ -> assertTrue(url.contains("/_serverFn/$old")); response(stream(idFromPayload(url))) }
        TgResolver(http, initialFunction = old).use { resolver ->
            assertEquals("https://media.example/872.mpd", resolver.resolve("872").single().url)
            assertEquals(1, http.requests.size)
            val headers = http.requests.single().second
            assertEquals(site, headers["Origin"])
            assertEquals("$site/watch/872", headers["Referer"])
            assertEquals("true", headers["x-tsr-serverfn"])
        }
    }

    @Test fun `redeploy finds the new function through a lazy player chunk and persists only its id`() {
        val saved = ArrayList<String>()
        val http = Fake { url, _ -> when (URI(url).path) {
            "/_serverFn/$old" -> response("", 404)
            "/watch/872" -> response("""<script src='/assets/main-new.js'></script>""", type = "text/html")
            "/assets/main-new.js" -> response("""const load=()=>import('./TgPlayer-new.js');""")
            "/assets/TgPlayer-new.js" -> response("""const rpc=getFn("$updated");""")
            "/_serverFn/$updated" -> response(stream(idFromPayload(url)))
            else -> throw AssertionError("Unexpected metadata request: ${URI(url).path}")
        } }
        TgResolver(http, initialFunction = old, saveFunction = { saved.add(it) }).use { resolver ->
            assertEquals("https://media.example/872.mpd", resolver.resolve("872").single().url)
            assertEquals(listOf(updated), saved)
            val before = http.requests.size
            resolver.resolve("1106")
            assertEquals(before + 1, http.requests.size)
            assertTrue(http.requests.last().first.contains("/_serverFn/$updated"))
        }
    }

    @Test fun `player chunks take priority over unrelated functions in a large entry bundle`() {
        val unrelated = (1..12).joinToString(";") { "rpc(\"${it.toString(16).padStart(64, '0')}\")" }
        val http = Fake { url, _ -> when (URI(url).path) {
            "/watch/872" -> response("""<script src='/assets/main.js'></script>""")
            "/assets/main.js" -> response(unrelated + ";import('./servers-new.js')")
            "/assets/servers-new.js" -> response("""rpc("$updated")""")
            "/_serverFn/$updated" -> response(stream("872"))
            else -> response("", 404)
        } }
        TgResolver(http).use { resolver ->
            assertEquals("https://media.example/872.mpd", resolver.resolve("872").single().url)
            assertTrue(http.requests.any { URI(it.first).path == "/_serverFn/$updated" })
        }
    }

    @Test fun `manual fresh resolution discovers a deployment even when the old endpoint still responds`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/_serverFn/$old" -> response(stream("872"))
            "/watch/872" -> response("""<script src='/assets/servers-new.js'></script>""")
            "/assets/servers-new.js" -> response("""rpc("$updated")""")
            "/_serverFn/$updated" -> response("""{"id":"872","sources":["https://media.example/new.m3u8"]}""")
            else -> response("", 404)
        } }
        TgResolver(http, initialFunction = old).use { resolver ->
            assertTrue(resolver.resolve("872").single().url.endsWith("872.mpd"))
            assertTrue(resolver.resolve("872", fresh = true).single().url.endsWith("new.m3u8"))
            assertEquals(1, http.requests.count { URI(it.first).path == "/watch/872" })
            assertEquals("no-cache, no-store", http.requests.last().second["Cache-Control"])
        }
    }

    @Test fun `fast incomplete channel function cannot hide the actual player servers`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/watch/872" -> response("""<script src='/assets/servers.js'></script>""")
            "/assets/servers.js" -> response("""const basic=rpc("$old"); const player=rpc("$updated");""")
            "/_serverFn/$old" -> response(stream("872"))
            "/_serverFn/$updated" -> {
                Thread.sleep(30)
                response("""{"result":{"primary":{"url":"https://media.example/872.mpd"},"backups":[{"url":"https://relay.example/872.m3u8","type":"hls"}]}}""")
            }
            else -> response("", 404)
        } }
        val saved = ArrayList<String>()
        TgResolver(http, saveFunction = { saved.add(it) }).use { resolver ->
            assertEquals(2, resolver.resolve("872").size)
            assertEquals(listOf(updated), saved)
        }
    }

    @Test fun `V2 numeric ids never call the V1 player resolver`() {
        val http = Fake { url, _ ->
            assertEquals("/api/public/v2/channels/872", URI(url).path)
            response("""{"version":2,"id":"872","servers":[{"label":"TG relay","type":"hls","playable":"https://relay.example/play?u=abc%2Bdef&r=keep"}]}""")
        }
        TgResolver(http, initialFunction = old).use { resolver ->
            val source = resolver.resolve("872", v2 = true).single()
            assertEquals("hls", source.type)
            assertEquals("https://relay.example/play?u=abc%2Bdef&r=keep", source.url)
            assertEquals(1, http.requests.size)
        }
    }

    @Test fun `cache namespace separates V1 and V2`() {
        val http = Fake { url, _ ->
            if (url.contains("/v2/")) response("""{"id":"51","servers":[{"playable":"https://media.example/v2.m3u8"}]}""")
            else response(stream("51"))
        }
        TgResolver(http, initialFunction = old).use { resolver ->
            assertTrue(resolver.resolve("51").single().url.endsWith("51.mpd"))
            assertTrue(resolver.resolve("51", v2 = true).single().url.endsWith("v2.m3u8"))
            assertEquals(2, http.requests.size)
        }
    }

    @Test fun `metadata cache expires before its signed token and fresh bypasses it`() {
        var clock = 1_000L
        val http = Fake { _, _ -> response("""{"id":"51","sources":["https://media.example/a.m3u8?__hdnea__=st=1~exp=15~hmac=fixture"]}""") }
        TgResolver(http, now = { clock }).use { resolver ->
            resolver.resolve("51", v2 = true)
            clock = 3_000L; resolver.resolve("51", v2 = true)
            assertEquals(1, http.requests.size)
            clock = 6_000L; resolver.resolve("51", v2 = true)
            assertEquals(2, http.requests.size)
            resolver.resolve("51", v2 = true, fresh = true)
            assertEquals(3, http.requests.size)
            assertTrue(http.requests.last().first.contains("_aktv="))
        }
    }

    @Test fun `a failed discovery is retried instead of cached for minutes`() {
        var available = false
        val http = Fake { url, _ -> when {
            URI(url).path == "/watch/872" && available -> response("""<script src='/assets/servers-new.js'></script>""")
            URI(url).path == "/assets/servers-new.js" -> response("""rpc("$updated")""")
            URI(url).path == "/_serverFn/$updated" -> response(stream("872"))
            else -> response("", 502)
        } }
        TgResolver(http).use { resolver ->
            try { resolver.resolve("872"); fail("Expected unavailable metadata") } catch (_: TgResolveException) { }
            available = true
            assertEquals("https://media.example/872.mpd", resolver.resolve("872").single().url)
        }
    }

    @Test fun `public API is a native metadata fallback not a different player`() {
        val http = Fake { url, _ ->
            if (URI(url).path == "/api/public/channels/872") response(stream("872")) else response("", 404)
        }
        TgResolver(http).use { resolver ->
            assertEquals("https://media.example/872.mpd", resolver.resolve("872").single().url)
            assertTrue(http.requests.all { URI(it.first).host == "livetgtv.lovable.app" })
        }
    }

    @Test fun `a candidate returning another channel is rejected and not persisted`() {
        val saved = ArrayList<String>()
        val http = Fake { url, _ -> when (URI(url).path) {
            "/watch/872" -> response("""<script src='/assets/servers.js'></script>""")
            "/assets/servers.js" -> response("""rpc("$updated")""")
            "/_serverFn/$updated" -> response(stream("873"))
            "/api/public/channels/872" -> response(stream("872"))
            else -> response("", 404)
        } }
        TgResolver(http, saveFunction = { saved.add(it) }).use { resolver ->
            assertTrue(resolver.resolve("872").single().url.endsWith("872.mpd"))
            assertTrue(saved.isEmpty())
        }
    }

    @Test fun `every advertised Star Sports id and Sony YAY Hindi use their exact channel id`() {
        // IDs from TG's public Sports/star and Sony Yay catalogues. No name-prefix fallback to other languages.
        val ids = listOf("460", "1123", "461", "1114", "1106", "1142", "1108", "362", "1109", "1141",
            "1984", "1985", "1389", "3277", "1124", "3278", "1651", "1650", "1122", "3273", "2853", "3274", "2852", "872")
        val http = Fake { url, _ -> response(stream(idFromPayload(url))) }
        TgResolver(http, initialFunction = old).use { resolver ->
            ids.forEach { id -> assertEquals("https://media.example/$id.mpd", resolver.resolve(id).single().url) }
            assertEquals(ids.size, http.requests.size)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun `channel ids cannot inject a path or query`() {
        TgResolver(Fake { _, _ -> throw AssertionError("Should not request") }).use { it.resolve("../872?other=1") }
    }

    @Test fun `interrupted channel resolution cannot publish or cache a stale result`() {
        val entered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val http = Fake { _, _ -> entered.countDown(); Thread.sleep(30_000); response(stream("872")) }
        TgResolver(http, initialFunction = old).use { resolver ->
            try {
                val future = executor.submit<List<TgStream>> { resolver.resolve("872") }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                future.cancel(true)
                executor.shutdown()
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
                assertTrue(future.isCancelled)
                assertEquals(1, http.requests.size)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `cold start follows the deployed backtick server function instead of incomplete public metadata`() {
        val http = Fake { url, _ -> when (URI(url).path) {
            "/watch/1106" -> response("""<script src='/assets/watch-current.js'></script>""", type = "text/html")
            "/assets/watch-current.js" -> response("""const load=()=>import(`./servers-current.js`);""")
            "/assets/servers-current.js" -> response("""var r=n({method:`GET`}).handler(t(`$updated`));""")
            "/_serverFn/$updated" -> {
                assertEquals("1106", idFromPayload(url))
                response("""{"result":{"id":"1106","urls":["https://media.example/star.mpd"],
                    "keyId":"${"a".repeat(32)}","key":"${"b".repeat(32)}",
                    "cookie":"__hdnea__=fixture-primary",
                    "backups":[{"label":"Server 2","urls":["https://relay.example/star.m3u8"],"cookie":null,"keyId":null,"key":null}]}}""")
            }
            "/api/public/channels/1106" -> throw AssertionError("Must use TG's full server configuration")
            else -> response("", 404)
        } }
        TgResolver(http).use { resolver ->
            val sources = resolver.resolve("1106")
            assertEquals(listOf("https://media.example/star.mpd", "https://relay.example/star.m3u8"), sources.map { it.url })
            assertNotNull(ClearKey.license(sources[0].drm!!))
            assertEquals("__hdnea__=fixture-primary", sources[0].cookie)
            assertNull(sources[1].drm)
            assertEquals("", sources[1].cookie)
        }
    }

}
