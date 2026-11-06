package com.aktv.app

import java.util.concurrent.TimeUnit
import okhttp3.Request

/**
 * A source is only offered if its manifest actually answers.
 *
 * A player that is handed a URL which returns 404 does not fail quickly - it retries, holds the spinner and
 * costs the viewer sixteen seconds before the app moves on. One request here is cheaper than that, and it
 * separates the three cases that matter: the address is dead (drop it), the address answers (offer it), or
 * the network did not answer (keep it: a slow link is not a dead source).
 */
internal object FrostPreflight {

    internal enum class Verdict { ALIVE, DEAD, UNKNOWN }

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(false).build()

    fun check(source: FrostSource): Verdict = try {
        val builder = Request.Builder().url(source.url).get().header("Accept", "*/*")
        // Ask the way the player will ask: same client, same per-source headers.
        val call = FrostHttp.client(source, client).newCall(builder.build())
        call.execute().use { response ->
            // Only a playlist has to be read: a file is proven by its answer, and 8 KB of a film is bandwidth.
            val body = if (response.isSuccessful && source.format == VodFormat.HLS) response.body?.byteStream()?.let { input ->
                // One read() is not the whole prefix: a playlist's `#EXTM3U` can arrive in the second one.
                val buffer = ByteArray(PREFIX_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val count = input.read(buffer, total, buffer.size - total)
                    if (count <= 0) break
                    total += count
                }
                String(buffer, 0, total, Charsets.UTF_8)
            }.orEmpty() else ""
            verdict(response.code, source.format, body, response.header("Content-Type").orEmpty())
        }
    } catch (_: Exception) {
        Verdict.UNKNOWN
    }

    /** Enough of an answer to recognise a playlist, and no more. */
    const val PREFIX_BYTES = 8 * 1024

    /**
     * What one answer proves, given the format that was asked for.
     *
     * The case that made this a decision rather than a status check: an HLS address that answers `200`
     * with a **sprite sheet** - a real `#EXTM3U` whose media lines are `.jpg` thumbnails. The player
     * starts it, fails on the first "segment", and the viewer loses the provider in under a second. Such
     * an answer is the one thing a 200 can be here that is definitely not video.
     */
    fun verdict(status: Int, format: VodFormat, body: String, contentType: String = ""): Verdict = when {
        status in listOf(401, 403, 404, 410) -> Verdict.DEAD
        status !in 200..299 -> Verdict.UNKNOWN
        format == VodFormat.HLS -> when {
            VodPreflight.isImagePlaylist(body) -> Verdict.DEAD
            body.contains("#EXTM3U", true) || body.contains("#EXT-X-", true) -> Verdict.ALIVE
            // A page where a playlist should be is a dead end wearing a 200.
            body.trimStart().startsWith("<") || contentType.contains("html", true) -> Verdict.DEAD
            body.isBlank() -> Verdict.UNKNOWN
            else -> Verdict.UNKNOWN
        }
        format == VodFormat.MP4 -> Verdict.ALIVE
        else -> Verdict.UNKNOWN
    }

    /** Everything but the addresses that answered "this does not exist". */
    fun keep(sources: List<FrostSource>): List<FrostSource> {
        if (sources.isEmpty()) return sources
        val alive = ArrayList<FrostSource>(sources.size)
        for (source in sources) {
            if (check(source) != Verdict.DEAD) alive += source
        }
        return if (alive.isEmpty()) emptyList() else alive
    }
}
