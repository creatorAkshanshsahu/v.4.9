package com.aktv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class FrostHttpTest {
    private fun source(url: String, headers: Map<String, String>) = FrostSource(FrostProvider.FROST, "synthetic", url,
        VodFormat.DASH, "1080p", "English", null, headers, emptyList())

    @Test fun `resolver asks for exact movie identity without cookies or an embed page`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"sources":[{"url":"https://cdn.example.test/stream?sig=synthetic","type":"dash"}]}"""))
            val sources = FrostResolver(server.url("/")).resolve(FrostTitle(1288069, "movie"))
            assertEquals(VodFormat.DASH, sources.single().format)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/sources?server=tgflix&type=movie&tmdb_id=1288069", request.path)
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
        }
    }
    @Test fun `resolver respects access and rate-limit responses without retrying or exposing bodies`() {
        for (status in listOf(401, 403, 404, 429, 503)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("credential=synthetic-private-value"))
            try { FrostResolver(server.url("/")).resolve(FrostTitle(213562, "tv", 1, 1)); fail("Expected error") }
            catch (error: FrostResolveException) {
                assertEquals(status, error.status)
                assertFalse(error.message.orEmpty().contains("synthetic-private-value"))
            }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `resolver does not follow an arbitrary redirect for the source API`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example.test/login"))
            val error = assertThrows(FrostResolveException::class.java) { FrostResolver(server.url("/")).resolve(FrostTitle(1, "movie")) }
            assertEquals(302, error.status)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `movie transport leaves signed queries and range requests untouched`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody("sample"))
            val url = server.url("/proxy?url=https%3A%2F%2Fcdn.example.test%2Fa.mpd%3Ftoken%3Dabc%252Fdef").toString()
            val stream = source(url, mapOf("origin" to "https://provider.example.test", "cookie" to "sid=synthetic"))
            FrostHttp.client(stream).newCall(Request.Builder().url(url).header("Range", "bytes=100-199").build()).execute().use { assertEquals(206, it.code) }
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(server.url("/").resolve(url)!!.encodedQuery, request.requestUrl!!.encodedQuery)
            assertEquals("bytes=100-199", request.getHeader("Range"))
            assertEquals("sid=synthetic", request.getHeader("Cookie"))
            assertFalse(request.path!!.contains("__hdnea__"))
        }
    }
    @Test fun `cross-origin redirect and separate child requests do not leak source credentials`() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/media.m4s")))
            second.enqueue(MockResponse().setBody("chunk")); second.enqueue(MockResponse().setBody("chunk"))
            val root = first.url("/manifest.mpd").toString()
            val stream = source(root, mapOf("authorization" to "Bearer synthetic", "cookie" to "sid=synthetic", "x-api-key" to "synthetic"))
            val client = FrostHttp.client(stream)
            client.newCall(Request.Builder().url(root).build()).execute().close()
            client.newCall(Request.Builder().url(second.url("/next.m4s")).build()).execute().close()
            assertEquals("Bearer synthetic", first.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            repeat(2) {
                val request = second.takeRequest(2, TimeUnit.SECONDS)!!
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("X-Api-Key"))
            }
        } }
    }
    @Test fun `another playback session cannot inherit the previous source headers`() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("sample")) }
            val url = server.url("/movie.mp4").toString()
            val shared = OkHttpClient()
            FrostHttp.client(source(url, mapOf("cookie" to "sid=first")), shared).newCall(Request.Builder().url(url).build()).execute().close()
            FrostHttp.client(source(url, emptyMap()), shared).newCall(Request.Builder().url(url).build()).execute().close()
            assertEquals("sid=first", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie"))
            assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie"))
        }
    }
    @Test fun `cross-origin mirror keeps primary credential scope even on its initial request`() {
        MockWebServer().use { primary -> MockWebServer().use { mirror ->
            mirror.enqueue(MockResponse().setBody("sample"))
            val source = FrostSource(FrostProvider.FROST, "mirror", mirror.url("/video.mp4").toString(), VodFormat.MP4,
                "720p", "English", null, mapOf("cookie" to "sid=synthetic", "authorization" to "Bearer synthetic"), emptyList(),
                primary.url("/video.mp4").toString())
            FrostHttp.client(source).newCall(Request.Builder().url(source.url).build()).execute().close()
            val request = mirror.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
        } }
    }

}
