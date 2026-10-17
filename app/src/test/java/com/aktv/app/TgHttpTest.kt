package com.aktv.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class TgHttpTest {
    @Test fun `transport sends same-origin headers and keeps binary responses intact`() {
        val payload = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 2, 123, 125)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/x-tss-framed; v=1")
                .setBody(Buffer().write(payload)))
            server.start()
            val response = TgHttp().get(server.url("/config").toString(), mapOf("Origin" to TgResolver.SITE))
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(TgResolver.SITE, request.getHeader("Origin"))
            assertEquals(200, response.code)
            assertEquals("application/x-tss-framed; v=1", response.contentType)
            assertArrayEquals(payload, response.body)
        }
    }

    @Test fun `HTTP errors remain status errors rather than fake stream data`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.start()
            val response = TgHttp().get(server.url("/config").toString(), emptyMap())
            assertEquals(403, response.code)
            assertTrue(response.body.isEmpty())
        }
    }

    @Test fun `metadata response size is bounded`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(TgProtocol.MAX_BODY + 1))))
            server.start()
            try {
                TgHttp().get(server.url("/config").toString(), emptyMap())
                fail("Expected bounded response")
            } catch (e: IOException) { assertEquals("Player response too large", e.message) }
        }
    }
}
