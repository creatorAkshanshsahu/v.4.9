package com.aktv.app

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

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

    @Test fun `catalog DNS falls back to resolver only for TMDB API host`() {
        val resolved = InetAddress.getByAddress(byteArrayOf(203, 0, 113, 7))
        var fallbackCalls = 0
        val system = Dns { throw UnknownHostException(it) }
        val fallback = Dns { hostname ->
            fallbackCalls++
            assertEquals(CatalogDns.TMDB_API_HOST, hostname)
            listOf(resolved)
        }
        val dns = CatalogDns(system, fallback)

        assertEquals(listOf(resolved), dns.lookup(CatalogDns.TMDB_API_HOST))
        assertEquals(1, fallbackCalls)
        val unrelated = try { dns.lookup("other.example") } catch (error: UnknownHostException) { error }
        assertEquals("other.example", unrelated.message)
        assertEquals(1, fallbackCalls)
    }

    @Test fun `Google DoH parses IPv4 answers for the TMDB host without carrying API data`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Status":0,"Answer":[{"name":"api.themoviedb.org.","type":1,"TTL":60,"data":"198.51.100.25"}]}"""))
            val dns = GoogleDohDns(OkHttpClient.Builder().build(), server.url("/resolve"))

            assertEquals("198.51.100.25", dns.lookup(CatalogDns.TMDB_API_HOST).single().hostAddress)
            val request = server.takeRequest()
            assertEquals("api.themoviedb.org", request.requestUrl?.queryParameter("name"))
            assertEquals("A", request.requestUrl?.queryParameter("type"))
            assertEquals("application/dns-json", request.getHeader("Accept"))
        }
    }
}
