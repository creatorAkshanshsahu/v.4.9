package com.aktv.app

import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class TgResponse(val code: Int, val body: ByteArray, val contentType: String = "application/json", val serverTimeMs: Long? = null, val reply: HttpReply? = null)
internal fun interface TgTransport {
    fun get(url: String, headers: Map<String, String>): TgResponse
    fun get(request: MetadataFetch): TgResponse = get(request.url, request.headers)
}

internal class TgHttp : TgTransport {
    override fun get(url: String, headers: Map<String, String>): TgResponse = execute(url, headers)
    override fun get(request: MetadataFetch): TgResponse = execute(request.url, request.headers, request)

    private fun execute(url: String, headers: Map<String, String>, fetch: MetadataFetch? = null): TgResponse {
        checkInterrupted()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (key, value) -> request.header(key, value) }
        // OkHttp sends Origin/Sec-Fetch-* unchanged; some URLConnection implementations silently drop them.
        val transport = if (fetch == null) client else {
            val origin = FetchOrigin(fetch.documentOrigin, url, fetch.cors)
            client.newBuilder().followSslRedirects(false).addNetworkInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder().removeHeader("Referer").removeHeader("Origin")
                origin.visit(original.url.toString())?.let { builder.header("Origin", it) }
                chain.proceed(builder.build())
            }.build()
        }
        transport.newCall(request.build()).execute().use { response ->
            val reply = HttpReply.inspect(response)
            val serverTime = HttpReferenceClock.parse(response.header("Date"), response.header("Age"))
            if (!response.isSuccessful) return TgResponse(response.code, ByteArray(0), response.header("Content-Type").orEmpty(), serverTime, reply)
            val body = response.body?.byteStream()?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkInterrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > TgProtocol.MAX_BODY) throw IOException("Player response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            return TgResponse(response.code, body, response.header("Content-Type").orEmpty(), serverTime, reply)
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    }
}

internal fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cancelled") }

class TgResolveException(val status: Int? = null) : IOException(when (status) {
    401, 403 -> "TG TV refused the player request"
    404 -> "TG TV has no live source for this channel right now"
    451 -> "The stream is not available in this region"
    null -> "Couldn't load TG TV's current player configuration"
    else -> "TG TV is unavailable (HTTP $status)"
})

/**
 * Uses the live website's server configuration, with its public API as the last native source lookup.
 * There is deliberately no bundled playlist, third-party player, or hard-coded deployment function id.
 * Catalogue V2 has a separate namespace and endpoint; its numeric ids are NOT V1 channel ids.
 */
internal class TgResolver(
    private val http: TgTransport = TgHttp(),
    private val site: String = SITE,
    private val now: () -> Long = System::currentTimeMillis,
    initialFunction: String? = null,
    private val saveFunction: (String) -> Unit = {}
) : AutoCloseable {
    companion object { const val SITE = "https://livetgtv.lovable.app" }
    private data class Cached(val until: Long, val streams: List<TgStream>)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val network = Executors.newFixedThreadPool(4) { r -> Thread(r, "tg-metadata").apply { isDaemon = true } }
    private val discoveryLock = Any()
    @Volatile private var verified = initialFunction?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,128}")) }
    @Volatile private var candidates = emptyList<String>()
    @Volatile private var discoveredAt = 0L

    fun invalidate(id: String, v2: Boolean = false) { cache.remove(key(id, v2)) }
    private fun key(id: String, v2: Boolean) = "${if (v2) "v2" else "v1"}:$id"

    fun resolve(id: String, v2: Boolean = false, fresh: Boolean = false): List<TgStream> {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid channel id" }
        checkInterrupted()
        val cacheKey = key(id, v2)
        if (fresh) cache.remove(cacheKey)
        else cache[cacheKey]?.takeIf { it.until > now() }?.let { return it.streams }

        val streams = if (v2) publicStreams(id, true, fresh) else {
            if (fresh) {
                // Playback failure/manual reload revalidates ALL discovered candidates, not just a stale hint
                // that might still return the public API's incomplete (drm:null) source.
                val ids = discover(id, force = true)
                val current = callCandidates((ids + listOfNotNull(verified)).distinct(), id, true)
                if (current.isNotEmpty()) current else publicStreams(id, false, true)
            } else {
                val known = verified
                val fast = known?.let { call(it, id, false) }.orEmpty()
                if (fast.isNotEmpty()) fast else {
                    // A removed function / changed response invalidates discovery immediately.
                    val ids = discover(id, force = known != null).filter { it != known }
                    val current = callCandidates(ids, id, false)
                    if (current.isNotEmpty()) current else publicStreams(id, false, false)
                }
            }
        }
        checkInterrupted()
        if (streams.isEmpty()) throw TgResolveException()
        // Signed media and DRM stay in memory for at most 30s, less if a token is about to expire.
        val expiry = streams.mapNotNull { StreamRequestPolicy.expiry(it.url, it.cookie) }.minOrNull()
        val until = minOf(now() + 30_000L, expiry?.minus(10_000L) ?: Long.MAX_VALUE)
        if (until > now()) {
            if (cache.size >= 128) cache.entries.removeAll { it.value.until <= now() }
            if (cache.size >= 128) cache.clear()
            cache[cacheKey] = Cached(until, streams)
        }
        return streams
    }

    private fun headers(id: String, fresh: Boolean = false) = linkedMapOf(
        "Accept" to "application/x-tss-framed, application/x-ndjson, application/json",
        "Referer" to "$site/watch/$id", "Origin" to site,
        "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors"
    ).apply { if (fresh) { put("Cache-Control", "no-cache, no-store"); put("Pragma", "no-cache") } }

    private fun publicStreams(id: String, v2: Boolean, fresh: Boolean): List<TgStream> {
        val path = if (v2) "v2/" else ""
        val url = "$site/api/public/${path}channels/$id" + if (fresh) "?_aktv=${now()}" else ""
        val requestHeaders = headers(id, fresh).apply { put("Referer", "$site/${path}watch/$id") }
        val response = http.get(url, requestHeaders)
        if (response.code !in 200..299) throw TgResolveException(response.code)
        return TgProtocol.streams(response.body, response.contentType, "$site/${path}watch/$id", if (v2) "hls" else "dash", expectedId = id)
    }

    /** Match the data argument used by TG's current GET server functions. Only read-only GETs are probed. */
    private fun payload(id: String): String {
        val inner = """{"t":10,"i":0,"p":{"k":["data"],"v":[{"t":10,"i":1,"p":{"k":["id"],"v":[{"t":1,"s":${JSONObject.quote(id)}}]},"o":0}]},"o":0}"""
        return URLEncoder.encode("""{"t":$inner,"f":127,"m":[]}""", "UTF-8")
    }

    private fun call(function: String, id: String, fresh: Boolean): List<TgStream> = optional {
        val url = "$site/_serverFn/$function?payload=${payload(id)}" + if (fresh) "&_aktv=${now()}" else ""
        val response = http.get(url, headers(id, fresh).apply { put("x-tsr-serverfn", "true") })
        if (response.code !in 200..299) emptyList() else TgProtocol.streams(response.body, response.contentType, "$site/watch/$id", expectedId = id)
    }.orEmpty()

    private fun callCandidates(ids: List<String>, id: String, fresh: Boolean): List<TgStream> {
        if (ids.isEmpty()) return emptyList()
        val completion = ExecutorCompletionService<Pair<String, List<TgStream>>>(network)
        val futures = ids.take(8).map { fn -> completion.submit(Callable { fn to call(fn, id, fresh) }) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        var fallback: Pair<String, List<TgStream>>? = null
        fun accept(result: Pair<String, List<TgStream>>?): List<TgStream> {
            if (result == null) return emptyList()
            verified = result.first; saveFunction(result.first)
            return result.second
        }
        try {
            repeat(futures.size) {
                checkInterrupted()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return accept(fallback)
                val result = completion.poll(remaining, TimeUnit.NANOSECONDS)?.get() ?: return accept(fallback)
                val sources = result.second
                if (sources.isNotEmpty()) {
                    // A basic channel-detail function can finish first but omit player-only relay/DRM data.
                    // Prefer the actual server configuration rather than pinning that incomplete function.
                    if (sources.size > 1 || sources.any { it.cookie.isNotBlank() || it.headers.isNotEmpty() }) return accept(result)
                    if (fallback == null || sources.any { it.drm != null }) fallback = result
                }
            }
        } finally { futures.forEach { it.cancel(true) } }
        return accept(fallback)
    }

    /** Re-read the page and all relevant lazy chunks. Only a successful discovery advances the refresh clock. */
    fun discover(id: String, force: Boolean = false): List<String> = synchronized(discoveryLock) {
        if (!force && candidates.isNotEmpty() && now() - discoveredAt < 5 * 60_000L) return@synchronized candidates
        checkInterrupted()
        val page = "$site/watch/$id"
        val html = text(page + "?_aktv=${now()}", id) ?: text("$site/embed/$id?_aktv=${now()}", id)
            ?: return@synchronized candidates
        val found = LinkedHashMap<String, Int>()
        fun noteFunctions(text: String, priority: Int) {
            TgDiscovery.functions(text).forEach { fn -> found[fn] = minOf(found[fn] ?: Int.MAX_VALUE, priority) }
        }
        noteFunctions(html, 2)
        val pending = LinkedHashSet(TgDiscovery.scripts(html, page, site))
        val visited = HashSet<String>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (pending.isNotEmpty() && visited.size < 28 && System.nanoTime() < deadline) {
            checkInterrupted()
            val batch = pending.sortedBy(TgDiscovery::priority).take(minOf(4, 28 - visited.size))
            pending.removeAll(batch.toSet()); visited.addAll(batch)
            val jobs = batch.map { url -> network.submit(Callable { url to text(url, id) }) }
            try {
                for (job in jobs) {
                    val left = deadline - System.nanoTime()
                    if (left <= 0) break
                    val (url, script) = optional { job.get(left, TimeUnit.NANOSECONDS) } ?: continue
                    if (script == null) continue
                    noteFunctions(script, TgDiscovery.priority(url))
                    pending.addAll(TgDiscovery.scripts(script, url, site).filter { it !in visited })
                }
            } finally { jobs.forEach { it.cancel(true) } }
            // The current server chunk declares the player function. Do not download the whole UI
            // and Shaka dependency graph before the first channel can start.
            if (found.values.any { it == 0 }) break
        }
        if (found.isNotEmpty()) {
            val next = found.entries.sortedBy { it.value }.take(12).map { it.key }
            if (next != candidates) {
                cache.clear()
                if (verified !in next) verified = null
                candidates = next
            }
            discoveredAt = now()
        }
        candidates
    }

    private fun text(url: String, id: String): String? = optional {
        val response = http.get(url, headers(id, true).apply { put("Accept", "text/html, application/javascript") })
        if (response.code in 200..299) response.body.toString(Charsets.UTF_8) else null
    }
    private fun <T> optional(block: () -> T): T? = try { block() } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        checkInterrupted()
        null
    }

    override fun close() { network.shutdownNow(); cache.clear() }
}
