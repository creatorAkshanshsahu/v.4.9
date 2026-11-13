package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader
import java.nio.file.Files

class XtreamClientTest {

    @Test
    fun `parseInput normalizes bare hosts, portal paths, and M3U get_php links`() {
        val bare = XtreamClient.parseInput("livebox.pro:8080", "alice", "secret")
        assertEquals("http://livebox.pro:8080", bare.server)
        assertEquals("alice", bare.username)
        assertEquals("secret", bare.password)

        val portalPath = XtreamClient.parseInput("http://livebox.pro:8080/c/", "alice", "secret")
        assertEquals("http://livebox.pro:8080", portalPath.server)

        val m3uLink = XtreamClient.parseInput(
            "http://starshare.fans:8080/get.php?username=demoUser&password=demoPass&type=m3u_plus&output=ts",
            "",
            ""
        )
        assertEquals("http://starshare.fans:8080", m3uLink.server)
        assertEquals("demoUser", m3uLink.username)
        assertEquals("demoPass", m3uLink.password)
        assertTrue(m3uLink.isConfigured)
    }

    @Test
    fun `candidateOrigins includes active StarShare mirrors for dead StarShare hosts`() {
        val candidates = XtreamClient.candidateOrigins("http://starshare.st:8080")
        assertEquals("http://starshare.st:8080", candidates.first())
        assertTrue(candidates.contains("http://livebox.pro:8080"))
        assertTrue(candidates.contains("http://livebox.pro"))
        assertTrue(candidates.contains("http://starshare.fans:8080"))
    }

    @Test
    fun `authenticate fails over from dead StarShare mirror to working livebox origin`() {
        val calls = ArrayList<String>()
        val transport = XtreamTransport { url, _ ->
            calls += url
            when {
                url.startsWith("http://starshare.st:8080") -> XtreamHttpStream(500, StringReader("Internal Server Error"))
                url.startsWith("http://starshare.st/") -> XtreamHttpStream(502, StringReader("Bad Gateway"))
                url.startsWith("http://livebox.pro:8080/player_api.php") -> XtreamHttpStream(
                    200,
                    StringReader(
                        """{"user_info":{"auth":1,"status":"Active","exp_date":"1893456000","active_cons":"0","max_connections":"2","allowed_output_formats":["m3u8","ts"]}}"""
                    )
                )
                else -> XtreamHttpStream(404, StringReader("Not found"))
            }
        }

        val info = XtreamClient.authenticate(
            XtreamAccount("http://starshare.st:8080", "myuser", "mypass"),
            transport
        )
        assertEquals("http://livebox.pro:8080", info.workingOrigin)
        assertEquals("Active", info.status)
        assertEquals(0, info.activeConnections)
        assertEquals(2, info.maxConnections)
        assertEquals(listOf("m3u8", "ts"), info.allowedFormats)
        assertTrue(calls.size >= 3)
    }

    @Test
    fun `authenticate stops immediately when credentials are rejected with auth 0`() {
        var callCount = 0
        val transport = XtreamTransport { _, _ ->
            callCount++
            XtreamHttpStream(200, StringReader("""{"user_info":{"auth":0}}"""))
        }

        try {
            XtreamClient.authenticate(XtreamAccount("http://livebox.pro:8080", "wrong", "creds"), transport)
            fail("Expected XtreamException on auth=0")
        } catch (e: XtreamException) {
            assertTrue(e.message.orEmpty().contains("Invalid StarShare username or password"))
            assertEquals("Wrong password must not hammer backup mirrors", 1, callCount)
        }
    }

    @Test
    fun `fetchLiveCatalogue and fetchVodCatalogue stream array and object-map payloads`() {
        val transport = XtreamTransport { url, _ ->
            when {
                url.contains("action=get_live_categories") -> XtreamHttpStream(
                    200,
                    StringReader("""[{"category_id":"10","category_name":"|IN| SPORTS HD"}]""")
                )
                url.contains("action=get_live_streams") -> XtreamHttpStream(
                    200,
                    StringReader(
                        """[{"stream_id":501,"name":"IN | Star Sports 1 HD","stream_icon":"http://img.test/ss1.png","category_id":"10"}]"""
                    )
                )
                url.contains("action=get_vod_categories") -> XtreamHttpStream(
                    200,
                    StringReader("""{"1":{"category_id":"22","category_name":"Bollywood Dual Audio"}}""")
                )
                url.contains("action=get_vod_streams") -> XtreamHttpStream(
                    200,
                    StringReader(
                        """{"a":{"stream_id":"9001","name":"Jawan (2023)","stream_icon":"http://img.test/jawan.jpg","category_id":"22","rating":"7.5","container_extension":"mkv"}}"""
                    )
                )
                url.contains("action=get_series_info") -> XtreamHttpStream(
                    200,
                    StringReader(
                        """{"episodes":{"1":[{"id":"7701","episode_num":1,"title":"Pilot","container_extension":"mkv","season":1}]}}"""
                    )
                )
                else -> XtreamHttpStream(404, StringReader(""))
            }
        }

        val account = XtreamAccount("http://livebox.pro:8080", "user1", "pass1")
        val live = XtreamClient.fetchLiveCatalogue(account, "http://livebox.pro:8080", transport)
        assertEquals(1, live.size)
        assertEquals("501", live[0].streamId)
        assertEquals("Star Sports 1 HD", live[0].name)
        assertEquals("IN| SPORTS HD", live[0].categoryName)

        val vod = XtreamClient.fetchVodCatalogue(account, "http://livebox.pro:8080", transport)
        assertEquals(1, vod.size)
        assertEquals(9001, vod[0].streamId)
        assertEquals("Jawan (2023)", vod[0].name)
        assertEquals("2023", vod[0].year)
        assertEquals("mkv", vod[0].containerExtension)

        val eps = XtreamClient.fetchSeriesEpisodes(account, 42, "http://livebox.pro:8080", transport)
        assertEquals(1, eps.size)
        assertEquals("7701", eps[0].episodeId)
        assertEquals(1, eps[0].season)
        assertEquals(1, eps[0].episode)
        assertEquals("mkv", eps[0].containerExtension)
    }

    @Test
    fun `liveStreamsFor orders TS vs HLS from allowedFormats and redactUrl masks secrets`() {
        val account = XtreamAccount("http://livebox.pro:8080", "myUser", "mySecretPass")
        val defaultOrder = XtreamClient.liveStreamsFor(account, "101", listOf("ts", "m3u8"))
        assertEquals("ts", defaultOrder[0].type)
        assertEquals("hls", defaultOrder[1].type)
        assertTrue(defaultOrder.all { XtreamClient.isXtreamStream(it) })

        val hlsOnlyOrder = XtreamClient.liveStreamsFor(account, "101", listOf("m3u8"))
        assertEquals("hls", hlsOnlyOrder[0].type)
        assertEquals("ts", hlsOnlyOrder[1].type)

        val redacted = XtreamClient.redactUrl(defaultOrder[0].url)
        assertFalse("Redacted URL must not contain username: $redacted", redacted.contains("myUser"))
        assertFalse("Redacted URL must not contain password: $redacted", redacted.contains("mySecretPass"))

        val tmpDir = Files.createTempDirectory("xtream-cache-test").toFile()
        try {
            XtreamClient.saveLiveCache(tmpDir, account.cacheKey, listOf(XtreamLiveChannel("101", "Star Sports 1", "", "Sports")))
            val files = tmpDir.listFiles().orEmpty()
            assertEquals(1, files.size)
            assertFalse(files[0].name.contains("mySecretPass"))
            assertFalse(files[0].readText().contains("mySecretPass"))
            val reloaded = XtreamClient.loadLiveCache(tmpDir, account.cacheKey)
            assertEquals(1, reloaded.size)
            assertEquals("Star Sports 1", reloaded[0].name)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
