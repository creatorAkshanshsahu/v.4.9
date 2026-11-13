package com.aktv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ElfuraClientTest {

    @After
    fun resetCache() {
        ElfuraClient.clearCacheForTest()
    }

    private fun encryptEnvelope(clearJson: String, pltoken: String, nonce: String): String {
        val iv = ByteArray(12) { (it + 7).toByte() }
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$pltoken:$nonce:elfura-pb-v1".toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(clearJson.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
    }

    @Test
    fun `movie session and playback resolve native HLS dubbed variants subtitles and proxy fallback`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"pltoken":"tok-movie-1","nonce":"n-77"}""")
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{
                            "ok": true,
                            "primary": {
                                "id": "s1-main",
                                "sourceId": "source-1",
                                "label": "Source 1 · Fast",
                                "type": "hls",
                                "quality": "1080p",
                                "multiAudio": true,
                                "url": "/api/v1/stream?t=tk_primary",
                                "audioTracks": [
                                    {"lang": "hi", "label": "Hindi", "url": "/api/v1/stream?t=tk_hindi"}
                                ],
                                "subtitles": [
                                    {"lang": "en", "label": "English", "url": "/api/v1/sub?id=en1"},
                                    {"lang": "es", "label": "Spanish", "url": "https://subs.example.test/es.srt"}
                                ]
                            }
                        }"""
                    )
            )

            val client = OkHttpClient.Builder().build()
            val movie = FrostTitle(533535, "movie")
            val sources = ElfuraClient.resolve(movie, server.url("/"), client)

            val sessionReq = server.takeRequest()
            assertEquals("POST", sessionReq.method)
            assertEquals("/api/v1/session", sessionReq.requestUrl?.encodedPath)
            val sessionJson = JSONObject(sessionReq.body.readUtf8())
            assertEquals("movie", sessionJson.getString("type"))
            assertEquals("533535", sessionJson.getString("tmdb"))

            val playbackReq = server.takeRequest()
            assertEquals("GET", playbackReq.method)
            assertEquals("movie", playbackReq.requestUrl?.queryParameter("type"))
            assertEquals("533535", playbackReq.requestUrl?.queryParameter("tmdb"))
            assertEquals("n-77", playbackReq.requestUrl?.queryParameter("nonce"))
            assertEquals("tok-movie-1", playbackReq.getHeader("X-Playback-Token"))

            // Primary multi-audio + Hindi dubbed audioTrack + p=1 proxy fallback
            assertEquals(3, sources.size)
            val first = sources[0]
            assertEquals(FrostProvider.OG, first.provider)
            assertEquals(VodFormat.HLS, first.format)
            assertEquals("1080p", first.quality)
            assertEquals("Multi-Audio", first.language)
            assertTrue(first.url.contains("/api/v1/stream?t=tk_primary"))
            assertTrue(first.url.contains("nonce=n-77"))
            assertEquals("tok-movie-1", first.headers["X-Playback-Token"])
            assertEquals(2, first.subtitles.size)
            assertEquals("text/vtt", first.subtitles[0].mime)
            assertEquals("application/x-subrip", first.subtitles[1].mime)

            val dubbed = sources[1]
            assertEquals("Hindi", dubbed.language)
            assertTrue(dubbed.url.contains("t=tk_hindi"))
            assertTrue(dubbed.url.contains("nonce=n-77"))

            val proxy = sources[2]
            assertTrue(proxy.url.contains("p=1"))
            assertTrue(proxy.label.contains("Proxy"))
        }
    }

    @Test
    fun `X-Playback-Token is sent to Elfura stream relay and never leaked to external CDN`() {
        MockWebServer().use { elfura ->
            MockWebServer().use { cdn ->
                elfura.enqueue(MockResponse().setBody("""{"pltoken":"secret-pl-tok","nonce":"nn1"}"""))
                elfura.enqueue(
                    MockResponse().setBody(
                        """{"ok":true,"sources":[{"id":"c1","sourceId":"source-2","type":"hls","url":"/api/v1/stream?t=123"}]}"""
                    )
                )
                elfura.enqueue(MockResponse().setBody("#EXTM3U\n"))
                cdn.enqueue(MockResponse().setBody("segment"))

                val source = ElfuraClient.resolve(
                    FrostTitle(101, "movie"),
                    elfura.url("/"),
                    OkHttpClient()
                ).first()
                elfura.takeRequest() // session
                elfura.takeRequest() // playback

                val transport = FrostHttp.client(source, OkHttpClient())
                transport.newCall(Request.Builder().url(source.url).build()).execute().close()
                val relayReq = elfura.takeRequest()
                assertEquals("secret-pl-tok", relayReq.getHeader("X-Playback-Token"))

                transport.newCall(Request.Builder().url(cdn.url("/seg-1.ts")).build()).execute().close()
                val cdnReq = cdn.takeRequest()
                assertNull(cdnReq.getHeader("X-Playback-Token"))
                assertEquals(ElfuraClient.embedPageUrl(FrostTitle(101, "movie"), elfura.url("/")), cdnReq.getHeader("Referer"))
            }
        }
    }

    @Test
    fun `series episode resolution decrypts AES-256-GCM envelope and boot HTML session fallback`() {
        MockWebServer().use { server ->
            val token = "boot-tok-99"
            val nonce = "boot-nonce-42"
            val clearPayload = """{
                "ok": true,
                "sources": [
                    {
                        "id": "tv-ep",
                        "sourceId": "source-3",
                        "type": "hls",
                        "quality": "720p",
                        "audioLang": "ja",
                        "url": "/api/v1/stream?t=ep_ticket"
                    }
                ]
            }"""
            val packed = encryptEnvelope(clearPayload, token, nonce)

            // Session POST returns 404 -> falls back to /embed/tv/94605/2/5 window.__ELFURA_BOOT__
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false}"""))
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/html").setBody(
                    """<html><head><script>window.__ELFURA_BOOT__ = {"pltoken":"$token","nonce":"$nonce"};</script></head></html>"""
                )
            )
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("s", 1).put("p", packed).toString())
            )

            val episode = FrostTitle(94605, "tv", 2, 5)
            val sources = ElfuraClient.resolve(episode, server.url("/"), OkHttpClient())

            server.takeRequest() // 404 session POST
            val bootReq = server.takeRequest()
            assertEquals("/embed/tv/94605/2/5", bootReq.requestUrl?.encodedPath)
            val pbReq = server.takeRequest()
            assertEquals("tv", pbReq.requestUrl?.queryParameter("type"))
            assertEquals("94605", pbReq.requestUrl?.queryParameter("tmdb"))
            assertEquals("2", pbReq.requestUrl?.queryParameter("s"))
            assertEquals("5", pbReq.requestUrl?.queryParameter("e"))
            assertEquals(token, pbReq.getHeader("X-Playback-Token"))

            assertEquals(2, sources.size)
            assertEquals("Japanese", sources.first().language)
            assertEquals("720p", sources.first().quality)
            assertTrue(sources.first().url.contains("t=ep_ticket"))
            assertTrue(sources.first().url.contains("nonce=$nonce"))
        }
    }

    @Test
    fun `empty fast window recovers slower per-source lanes`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (path == "/api/v1/session") {
                        return MockResponse().setBody("""{"pltoken":"lane-tok","nonce":"lane-n"}""")
                    }
                    if (path == "/api/v1/playback") {
                        val lane = request.requestUrl?.queryParameter("source")
                        return if (lane == "source-4") {
                            MockResponse().setBody(
                                """{"ok":true,"sources":[{"id":"slow4","sourceId":"source-4","type":"hls","url":"/api/v1/stream?t=slow_lane"}]}"""
                            )
                        } else {
                            MockResponse().setBody("""{"ok":true,"sources":[]}""")
                        }
                    }
                    return MockResponse().setResponseCode(404)
                }
            }

            val sources = ElfuraClient.resolve(FrostTitle(550, "movie"), server.url("/"), OkHttpClient())
            assertTrue(sources.isNotEmpty())
            assertTrue(sources.first().url.contains("t=slow_lane"))
            assertTrue(sources.first().label.contains("Source 4"))
        }
    }

    @Test
    fun `catalog parses movies series and anime shelves and supports local search`() {
        val tempDir = Files.createTempDirectory("og-cat-test").toFile()
        try {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        """{
                            "ok": true,
                            "generatedAt": "2026-11-10T00:00:00Z",
                            "movies": [
                                {"tmdbId": "693134", "type": "movie", "title": "Dune: Part Two", "year": "2024", "rating": 8.3,
                                 "overview": "Paul Atreides unites with Chani.", "poster": "https://image.tmdb.org/t/p/w500/p1.jpg", "backdrop": "https://image.tmdb.org/t/p/w1280/b1.jpg"}
                            ],
                            "series": [
                                {"tmdbId": "95396", "type": "tv", "title": "Severance", "year": "2022", "rating": 8.4,
                                 "overview": "Lumon Industries.", "poster": "https://image.tmdb.org/t/p/w500/p2.jpg", "backdrop": "https://image.tmdb.org/t/p/w1280/b2.jpg"}
                            ],
                            "anime": [
                                {"tmdbId": "209867", "type": "anime", "title": "Frieren: Beyond Journey's End", "year": "2023", "rating": 8.9,
                                 "overview": "Elven mage Frieren.", "poster": "https://image.tmdb.org/t/p/w500/p3.jpg", "backdrop": "https://image.tmdb.org/t/p/w1280/b3.jpg"}
                            ]
                        }"""
                    )
                )

                val catalog = ElfuraClient.catalog(tempDir, force = true, baseUrl = server.url("/"), client = OkHttpClient())
                assertEquals(1, catalog.movies.size)
                assertEquals("mv693134", catalog.movies.single().imdb)
                assertEquals("movie", catalog.movies.single().type)

                assertEquals(1, catalog.series.size)
                assertEquals("tv95396", catalog.series.single().imdb)
                assertEquals("series", catalog.series.single().type)

                assertEquals(1, catalog.anime.size)
                assertEquals("tv209867", catalog.anime.single().imdb)
                assertEquals("series", catalog.anime.single().type)
                assertTrue("Anime" in catalog.anime.single().genres)

                val hits = ElfuraClient.searchCatalog("frieren", tempDir, server.url("/"), OkHttpClient())
                assertEquals(listOf("tv209867"), hits.map { it.imdb })
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `episodes fallback reads seasons in order`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pltoken":"ep-tok","nonce":"ep-n"}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{
                        "ok": true,
                        "seasons": [{"seasonNumber": 1, "episodeCount": 2}, {"seasonNumber": 2, "episodeCount": 1}],
                        "episodes": [
                            {"seasonNumber": 1, "episodeNumber": 1, "name": "Ryomen Sukuna", "overview": "Ep 1", "still": "https://image.tmdb.org/t/p/w300/s1.jpg"},
                            {"seasonNumber": 1, "episodeNumber": 2, "name": "For Myself", "overview": "Ep 2", "still": ""}
                        ]
                    }"""
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    """{
                        "ok": true,
                        "episodes": [
                            {"seasonNumber": 2, "episodeNumber": 1, "name": "Hidden Inventory", "overview": "S2E1", "still": ""}
                        ]
                    }"""
                )
            )

            val eps = ElfuraClient.episodes(95479, server.url("/"), OkHttpClient())
            assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), eps.map { it.season to it.episode })
            assertEquals("Ryomen Sukuna", eps.first().name)
        }
    }

    @Test
    fun `expands advertised sourceGroups such as source-8 and source-14 when fast window is empty`() {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/api/v1/session") ->
                            MockResponse().setBody("""{"pltoken":"tok-254679b","nonce":"n-254679b"}""")
                        path.startsWith("/api/v1/playback") && !path.contains("source=") ->
                            MockResponse().setBody(
                                """{"ok":true,"sources":[],"sourceGroups":[{"id":"source-8","count":1},{"id":"source-14","count":1}]}"""
                            )
                        path.contains("source=source-8") ->
                            MockResponse().setBody(
                                """{"ok":true,"sources":[{"id":"s8","sourceId":"source-8","url":"/api/v1/stream?t=s8-hls","type":"hls","quality":"1080p","audioLang":"en"}]}"""
                            )
                        path.contains("source=source-14") ->
                            MockResponse().setBody(
                                """{"ok":true,"sources":[{"id":"s14","sourceId":"source-14","url":"https://bcdn.hakunaymatata.com/resource/1381071.mp4?sign=ok","type":"mp4","quality":"1080p","audioLang":"hi"}]}"""
                            )
                        else -> MockResponse().setBody("""{"ok":true,"sources":[]}""")
                    }
                }
            }

            val sources = ElfuraClient.resolve(FrostTitle(1381071, "movie"), server.url("/"), OkHttpClient())
            assertTrue("Expected both source-8 HLS and source-14 MP4", sources.size >= 2)
            assertTrue(sources.any { it.scraperName == "Source 8" && it.format == VodFormat.HLS })
            val mbSource = sources.first { it.url.contains("hakunaymatata.com") }
            assertEquals(VidLinkClient.MOVIEBOX_REFERER, mbSource.headers["Referer"])
        }
    }

    @Test
    fun `primary full-length MovieBox stream stays ahead of fuzzy Source 2 HLS scraper clip`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pltoken":"tok-934042","nonce":"n-934042"}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{
                        "ok": true,
                        "primary": {
                            "id": "mb-934042",
                            "sourceId": "source-13",
                            "label": "Source 13",
                            "type": "mp4",
                            "quality": "720p",
                            "audioLang": "en",
                            "url": "https://bcdn.hakunaymatata.com/resource/dfd32731e10b3840e90ab233014d2af7.mp4?sign=ok"
                        },
                        "sources": [
                            {
                                "id": "fuzzy-anime",
                                "sourceId": "source-2",
                                "label": "Source 2",
                                "type": "hls",
                                "quality": "1080p",
                                "audioLang": "ja",
                                "url": "/api/v1/stream?t=wrong_anime_short"
                            }
                        ]
                    }"""
                )
            )

            val sources = ElfuraClient.resolve(FrostTitle(934042, "movie"), server.url("/"), OkHttpClient())
            assertTrue(sources.size >= 3)
            // Primary Source 13 (real documentary on bcdnxw mirror) must precede fuzzy Source 2 HLS!
            val first = sources.first()
            assertEquals("Source 13", first.scraperName)
            assertTrue(first.url.startsWith("https://bcdnxw.hakunaymatata.com/resource/dfd32731"))
            assertEquals(VidLinkClient.MOVIEBOX_USER_AGENT, first.headers["User-Agent"])
            val fuzzyIdx = sources.indexOfFirst { it.scraperName.startsWith("Source 2") }
            assertTrue("Fuzzy Source 2 must rank behind primary Source 13", fuzzyIdx >= 3)
        }
    }
}
