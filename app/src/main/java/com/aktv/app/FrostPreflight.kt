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
            when {
                response.code == 401 || response.code == 403 || response.code == 404 || response.code == 410 -> Verdict.DEAD
                !response.isSuccessful -> Verdict.UNKNOWN
                source.format == VodFormat.HLS -> {
                    val body = response.body?.byteStream()?.let { input ->
                        val buffer = ByteArray(2_048); val read = input.read(buffer)
                        if (read > 0) String(buffer, 0, read, Charsets.UTF_8) else ""
                    }.orEmpty()
                    // An HLS answer that is not a playlist is a dead end wearing a 200.
                    if (body.contains("#EXTM3U", true) || body.contains("#EXT-X-", true)) Verdict.ALIVE else Verdict.DEAD
                }
                source.format == VodFormat.MP4 -> Verdict.ALIVE
                else -> Verdict.UNKNOWN
            }
        }
    } catch (_: Exception) {
        Verdict.UNKNOWN
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
