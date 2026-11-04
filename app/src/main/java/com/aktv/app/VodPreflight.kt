package com.aktv.app

import okhttp3.Request
import okhttp3.ResponseBody
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Cheap "can this source actually serve a manifest?" check, run before the native player is created.
 *
 * A browser player keeps a source change inside one page load: it starts a source, sees the manifest
 * fail and moves on without the viewer noticing. A native player pays a full startup cycle for every
 * dead URL, so a title that plays immediately on the website could need a second attempt in the app.
 *
 * The first few candidates are therefore probed in parallel with a short deadline, and the player's
 * very first attempt starts on a source that answered. A probe never drops a candidate: unreachable
 * sources keep their relative order behind the verified ones, so a CDN that merely dislikes a ranged
 * request is still playable from the source list.
 *
 * Nothing here keeps a response body, URL, signature or header value.
 */
internal object VodPreflight {
    /** Only the first few candidates are worth probing; everything after them keeps its order. */
    const val MAX_CANDIDATES = 6
    const val PREFIX_BYTES = 512
    const val TIMEOUT_MS = 4_000L

    /** Verified sources first (original order kept inside each group). */
    fun rank(sources: List<FrostSource>, reachable: Set<String>): List<FrostSource> {
        if (reachable.isEmpty()) return sources
        val verified = sources.filter { it.identity in reachable }
        if (verified.isEmpty()) return sources
        return verified + sources.filterNot { it.identity in reachable }
    }

    /** True when the candidate that is about to start is one the probe already confirmed. */
    fun isConfirmed(source: FrostSource?, reachable: Set<String>): Boolean =
        source != null && source.identity in reachable

    /**
     * Identities that answered with a manifest-ish response. Bounded by [MAX_CANDIDATES], runs in
     * parallel, never throws and never keeps a body. An empty result means "no evidence", not "dead":
     * the caller keeps the original order in that case.
     */
    fun verify(sources: List<FrostSource>, timeoutMs: Long = TIMEOUT_MS): Set<String> {
        val candidates = sources.take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return emptySet()
        val pool = Executors.newFixedThreadPool(minOf(4, candidates.size)) { runnable ->
            Thread(runnable, "aktv-vod-probe").apply { isDaemon = true }
        }
        return try {
            val pending: List<Pair<FrostSource, Future<Boolean>>> = candidates.map { source ->
                source to pool.submit(Callable { probe(source, timeoutMs) })
            }
            pending.mapNotNull { (source, future) ->
                val answered = try {
                    future.get(timeoutMs + 750L, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    future.cancel(true)
                    false
                }
                if (answered) source.identity else null
            }.toSet()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun probe(source: FrostSource, timeoutMs: Long): Boolean = try {
        val client = FrostHttp.client(source).newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(source.url).header("Range", "bytes=0-${PREFIX_BYTES - 1}").build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) false
            else looksLikeManifest(response.header("Content-Type").orEmpty(), prefix(body))
        }
    } catch (_: Exception) {
        false
    }

    private fun prefix(body: ResponseBody): String = try {
        val buffer = ByteArray(PREFIX_BYTES)
        val stream = body.byteStream()
        val count = stream.read(buffer)
        if (count <= 0) "" else String(buffer, 0, count, Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }

    /** DASH/HLS markers, or a media/adaptive content type. An HTML or JSON error page does not count. */
    fun looksLikeManifest(contentType: String, prefix: String): Boolean {
        val head = prefix.take(4_000)
        if (head.contains("<MPD", true) || head.contains("#EXTM3U", true)) return true
        val type = contentType.substringBefore(';').trim().lowercase()
        if (type.contains("dash+xml") || type.contains("mpegurl") || type.contains("vnd.apple")) return true
        if (type.contains("html") || type.contains("json")) return false
        return type.startsWith("video/") || type == "application/octet-stream" || type == "binary/octet-stream"
    }
}
