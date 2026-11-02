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
            repeat(2) { server.enqueue(MockResponse().setResponseCode(401).setBody("{}")) }
            val transport = CatalogHttpTransport(OkHttpClient.Builder().build())
            val error = try {
                transport.get(server.url("/3/configuration").toString())
                null
            } catch (e: IOException) { e }

            assertEquals("HTTP 401", error?.message)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `catalog DNS tries DoH and retains system addresses as connection fallbacks`() {
        val systemAddress = InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 8))
        val dohAddress = InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 7))
        var fallbackCalls = 0
        var systemCalls = 0
        val system = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                systemCalls++
                return listOf(systemAddress)
            }
        }
        val fallback = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                fallbackCalls++
                assertEquals(CatalogDns.TMDB_API_HOST, hostname)
                return listOf(dohAddress)
            }
        }
        val dns = CatalogDns(system, fallback)

        assertEquals(listOf(dohAddress, systemAddress), dns.lookup(CatalogDns.TMDB_API_HOST))
        assertEquals(1, fallbackCalls)
        assertEquals(1, systemCalls)
        assertEquals(listOf(systemAddress), dns.lookup("other.example"))
        assertEquals(1, fallbackCalls)
        assertEquals(2, systemCalls)
    }

    @Test fun `catalog DNS backs off unavailable DoH and keeps system DNS working`() {
        val systemAddress = InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 8))
        var systemCalls = 0
        var fallbackCalls = 0
        var nowMs = 1_000L
        val system = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                systemCalls++
                return listOf(systemAddress)
            }
        }
        val fallback = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                fallbackCalls++
                throw UnknownHostException(hostname)
            }
        }
        val dns = CatalogDns(system, fallback) { nowMs }

        assertEquals(listOf(systemAddress), dns.lookup(CatalogDns.TMDB_API_HOST))
        assertEquals(listOf(systemAddress), dns.lookup(CatalogDns.TMDB_API_HOST))
        assertEquals(1, fallbackCalls)
        assertEquals(2, systemCalls)
        nowMs += 60_001L
        assertEquals(listOf(systemAddress), dns.lookup(CatalogDns.TMDB_API_HOST))
        assertEquals(2, fallbackCalls)
    }

    @Test fun `Google DoH queries AAAA when no IPv4 answer exists`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Status":0,"Answer":[]}"""))
            server.enqueue(MockResponse().setBody("""{"Status":0,"Answer":[{"name":"api.themoviedb.org.","type":28,"TTL":60,"data":"2001:db8::25"}]}"""))
            val dns = GoogleDohDns(OkHttpClient.Builder().build(), server.url("/resolve"))

            val addresses = dns.lookup(CatalogDns.TMDB_API_HOST).map { it.hostAddress }
            assertEquals(listOf("2001:db8:0:0:0:0:0:25"), addresses)
            val aRequest = server.takeRequest()
            val aaaaRequest = server.takeRequest()
            assertEquals("api.themoviedb.org", aRequest.requestUrl?.queryParameter("name"))
            assertEquals("A", aRequest.requestUrl?.queryParameter("type"))
            assertEquals("AAAA", aaaaRequest.requestUrl?.queryParameter("type"))
            assertEquals("application/dns-json", aRequest.getHeader("Accept"))
        }
    }

    @Test fun `Google DoH stops after a usable IPv4 answer`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Status":0,"Answer":[{"name":"api.themoviedb.org.","type":1,"TTL":60,"data":"198.51.100.25"}]}"""))
            val dns = GoogleDohDns(OkHttpClient.Builder().build(), server.url("/resolve"))

            assertEquals(listOf("198.51.100.25"), dns.lookup(CatalogDns.TMDB_API_HOST).map { it.hostAddress })
            assertEquals("A", server.takeRequest().requestUrl?.queryParameter("type"))
            assertEquals(1, server.requestCount)
        }
    }
}
