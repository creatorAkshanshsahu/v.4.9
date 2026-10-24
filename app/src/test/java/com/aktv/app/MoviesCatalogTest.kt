package com.aktv.app

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class MoviesCatalogTest {
    @Test fun `trailer selection prefers an official YouTube trailer and ignores unrelated providers`() {
        val data = JSONObject("""{"videos":{"results":[
            {"site":"Vimeo","type":"Trailer","key":"vimeo_key"},
            {"site":"YouTube","type":"Clip","key":"clip_12345"},
            {"site":"YouTube","type":"Trailer","official":false,"key":"unofficial"},
            {"site":"YouTube","type":"Trailer","official":true,"key":"official_key1"}
        ]}}""")
        assertEquals("official_key1", Movies.videoKey(data))
    }

    @Test fun `trailer selection falls back to a YouTube teaser and rejects invalid keys`() {
        val teaser = JSONObject("""{"videos":{"results":[{"site":"YouTube","type":"Teaser","key":"teaser_1"}]}}""")
        assertEquals("teaser_1", Movies.videoKey(teaser))
        val invalid = JSONObject("""{"videos":{"results":[{"site":"YouTube","type":"Trailer","key":"unsafe/key?x=y"}]}}""")
        assertEquals("", Movies.videoKey(invalid))
    }

    @Test fun `catalog transport reads JSON using app headers`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[]}"""))
            val transport = CatalogHttpTransport(OkHttpClient.Builder().build())

            assertEquals("""{"results":[]}""", transport.get(server.url("/3/trending/movie/week").toString()))
            val request = server.takeRequest()
            assertEquals("application/json", request.getHeader("Accept"))
            assertEquals(Streams.CHROME, request.getHeader("User-Agent"))
        }
    }

    @Test fun `catalog transport retries HTTP errors and preserves the status code`() {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(401).setBody("{}")) }
            val transport = CatalogHttpTransport(OkHttpClient.Builder().build())
            val error = try {
                transport.get(server.url("/3/configuration").toString())
                null
            } catch (e: IOException) { e }

            assertEquals("HTTP 401", error?.message)
            assertEquals(3, server.requestCount)
        }
    }
}
