package com.aktv.app

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit

class JjtvAuthRecoveryTest {
    @Test fun `a rejected cached JJTV token is replaced by fresh metadata and authenticates subsequent media loads`() {
        MockWebServer().use { media ->
            media.start()
            media.enqueue(MockResponse().setResponseCode(403))
            media.enqueue(MockResponse().setBody("fixture manifest"))
            media.enqueue(MockResponse().setBody("fixture segment"))
            var cookiesRead = 0
            val url = media.url("/live.mpd?__hdnea__=catalog-old").toString()
            val transport = TgTransport { requested, _ ->
                val body = when (URI(requested).path) {
                    "/pind" -> """<script>const CHANNELS_URL='/channels.json'; const COOKIE_PRIMARY='https://tokens.example/general.json'; const COOKIE_FALLBACK=''; const COOKIE_STAR_SPORTS='';</script>"""
                    "/channels.json" -> """[{"id":"872","url":${JSONObject.quote(url)},"keyId":"${"1".repeat(32)}","key":"${"2".repeat(32)}"}]"""
                    "/general.json" -> """[{"cookie":"__hdnea__=${if (++cookiesRead == 1) "rejected" else "current"}"}]"""
                    else -> throw AssertionError("Only advertised metadata endpoints are used")
                }
                TgResponse(200, body.toByteArray())
            }
            val resolver = JjtvResolver(transport)
            val stale = resolver.resolve("872").first()
            val recovery = PlaybackAuthRecovery()
            val code = StreamHttp.client(stale).newCall(Request.Builder().url(stale.url).build()).execute().use { it.code }
            assertEquals(403, code)
            assertTrue(recovery.retry(LiveProvider.JJTV, code))
            val refreshed = resolver.resolve("872", fresh = true).first()
            assertEquals(ClearKey.pairs(stale.drm!!), ClearKey.pairs(refreshed.drm!!))
            assertEquals("__hdnea__=current", refreshed.cookie)
            val client = StreamHttp.client(refreshed)
            client.newCall(Request.Builder().url(refreshed.url).build()).execute().use { assertTrue(it.isSuccessful) }
            client.newCall(Request.Builder().url(media.url("/segment.m4s")).build()).execute().use { assertTrue(it.isSuccessful) }
            assertEquals("rejected", media.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("__hdnea__"))
            assertEquals("current", media.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("__hdnea__"))
            assertEquals("current", media.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("__hdnea__"))
            assertEquals(2, cookiesRead)
            assertFalse(recovery.retry(LiveProvider.JJTV, 403))
        }
    }
}
