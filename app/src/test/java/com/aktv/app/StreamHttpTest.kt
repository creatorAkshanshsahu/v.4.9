package com.aktv.app

import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class StreamHttpTest {
    private data class Seen(val query: String?, val authorization: String?, val cookie: String?, val agent: String?, val range: String?)

    @Test fun `native transport follows signed redirects without leaking relay headers or breaking segment queries`() {
        val relay = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val cdn = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seenRelay = CopyOnWriteArrayList<Seen>(); val seenCdn = CopyOnWriteArrayList<Seen>()
        val token = "st=1~exp=4102444800~acl=/*~hmac=redirect-fixture"
        fun record(exchange: com.sun.net.httpserver.HttpExchange) = Seen(exchange.requestURI.rawQuery,
            exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("Cookie"),
            exchange.requestHeaders.getFirst("User-Agent"), exchange.requestHeaders.getFirst("Range"))
        val cdnBase = "http://127.0.0.1:${cdn.address.port}"
        relay.createContext("/stream") { exchange ->
            seenRelay.add(record(exchange))
            exchange.responseHeaders.add("Location", "$cdnBase/manifest?route=manifest&__hdnea__=$token")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        cdn.createContext("/") { exchange ->
            seenCdn.add(record(exchange))
            val body = "test media bytes".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        relay.start(); cdn.start()
        try {
            val start = "http://127.0.0.1:${relay.address.port}/stream?channel=1106"
            val client = StreamHttp.client(TgStream("TG", start, "dash", headers = mapOf(
                "Authorization" to "relay-only", "Cookie" to "relay-session", "User-Agent" to "TG current player")))
            client.newCall(Request.Builder().url(start).build()).execute().use { assertTrue(it.isSuccessful); it.body!!.string() }
            client.newCall(Request.Builder().url("$cdnBase/segment?route=seg&u=a%2Bb").header("Range", "bytes=4-7").build())
                .execute().use { assertTrue(it.isSuccessful); it.body!!.string() }
            assertEquals("relay-only", seenRelay.single().authorization)
            assertEquals("relay-session", seenRelay.single().cookie)
            assertEquals("TG current player", seenRelay.single().agent)
            assertTrue(seenCdn.all { it.authorization == null && it.cookie == null })
            assertTrue(seenCdn.all { it.agent == TgHttp.USER_AGENT })
            assertEquals("route=seg&u=a%2Bb&__hdnea__=$token", seenCdn.last().query)
            assertEquals("bytes=4-7", seenCdn.last().range)
        } finally { relay.stop(0); cdn.stop(0) }
    }

    @Test fun `a rejected response cannot teach the session a new token`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val queries = CopyOnWriteArrayList<String>()
        server.createContext("/") { exchange ->
            queries.add(exchange.requestURI.rawQuery.orEmpty())
            exchange.sendResponseHeaders(403, -1); exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val client = StreamHttp.client(TgStream("TG", "$base/start", "dash"))
            client.newCall(Request.Builder().url("$base/rejected?__hdnea__=bad").build()).execute().close()
            client.newCall(Request.Builder().url("$base/segment").build()).execute().close()
            assertEquals("", queries.last())
        } finally { server.stop(0) }
    }
}
