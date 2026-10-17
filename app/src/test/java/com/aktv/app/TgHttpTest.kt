package com.aktv.app

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class TgHttpTest {
    @Test fun `transport sends same-origin headers and keeps binary responses intact`() {
        val origin = AtomicReference<String>()
        val payload = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 2, 123, 125)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/config") { exchange ->
            origin.set(exchange.requestHeaders.getFirst("Origin"))
            exchange.responseHeaders.add("Content-Type", "application/x-tss-framed; v=1")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val response = TgHttp().get("http://127.0.0.1:${server.address.port}/config", mapOf("Origin" to TgResolver.SITE))
            assertEquals(TgResolver.SITE, origin.get())
            assertEquals(200, response.code)
            assertEquals("application/x-tss-framed; v=1", response.contentType)
            assertArrayEquals(payload, response.body)
        } finally { server.stop(0) }
    }

    @Test fun `HTTP errors remain status errors rather than fake stream data`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/config") { exchange -> exchange.sendResponseHeaders(403, -1); exchange.close() }
        server.start()
        try {
            val response = TgHttp().get("http://127.0.0.1:${server.address.port}/config", emptyMap())
            assertEquals(403, response.code)
            assertTrue(response.body.isEmpty())
        } finally { server.stop(0) }
    }

    @Test fun `metadata response size is bounded`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/config") { exchange ->
            val body = ByteArray(TgProtocol.MAX_BODY + 1)
            exchange.sendResponseHeaders(200, body.size.toLong())
            try { exchange.responseBody.use { it.write(body) } } catch (_: IOException) { }
        }
        server.start()
        try {
            try {
                TgHttp().get("http://127.0.0.1:${server.address.port}/config", emptyMap())
                fail("Expected bounded response")
            } catch (e: IOException) { assertEquals("Player response too large", e.message) }
        } finally { server.stop(0) }
    }
}
