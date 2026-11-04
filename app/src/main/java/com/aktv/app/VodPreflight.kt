package com.aktv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Cheap "can this source actually serve video?" check, run before the native player is created.
 *
 * A browser player keeps a source change inside one page load: it starts a source, sees the manifest
 * fail and moves on without the viewer noticing. A native player pays a full startup cycle for every
 * dead URL, so a title that plays immediately on the website could need a second attempt in the app.
 *
 * A manifest that answers is **not** proof of a playable source. Two cases the live service really
 * serves, both of which used to be ranked as confirmed:
 *
 *  - a seek-bar sprite sheet published as `#EXTM3U` whose media lines are `.jpg` images, listed as a
 *    720p "source" for the title;
 *  - a manifest whose segments are signed (CloudFront `Policy`/`Signature`), where the manifest is
 *    still served after the signature behind it has expired.
 *
 * The probe therefore reads the document, then fetches the single media resource it names (the DASH
 * initialization segment, the HLS media playlist's first segment or `#EXT-X-MAP`). Only an answer to
 * both counts. A probe never drops a candidate: unreachable sources keep their relative order behind
 * the verified ones, so a CDN that merely dislikes a ranged request is still playable from the list.
 *
 * Nothing here keeps a response body, URL, signature or header value.
 */
internal object VodPreflight {
    /** Small on purpose: every candidate costs a manifest request plus one media request. */
    const val MAX_CANDIDATES = 4
    /** Enough of a body to recognise a manifest and read its first media resource. */
    const val PREFIX_BYTES = 256 * 1024
    const val TIMEOUT_MS = 3_500L
    const val CONCURRENCY = 2

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
     * Identities that answered with a manifest **and** with the first media resource it names.
     * Bounded by [MAX_CANDIDATES], runs with [CONCURRENCY] workers, never throws, never keeps a body.
     * An empty result means "no evidence", not "dead": the caller keeps the original order then.
     */
    fun verify(sources: List<FrostSource>, timeoutMs: Long = TIMEOUT_MS): Set<String> {
        val candidates = sources.take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return emptySet()
        val pool = Executors.newFixedThreadPool(minOf(CONCURRENCY, candidates.size)) { runnable ->
            Thread(runnable, "aktv-vod-probe").apply { isDaemon = true }
        }
        return try {
            val pending: List<Pair<FrostSource, Future<Boolean>>> = candidates.map { source ->
                source to pool.submit(Callable { probe(source, timeoutMs) })
            }
            pending.mapNotNull { (source, future) ->
                val answered = try {
                    future.get(timeoutMs * 3 + 750L, TimeUnit.MILLISECONDS)
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

    private data class Answer(val code: Int, val contentType: String, val body: String)

    private fun probe(source: FrostSource, timeoutMs: Long): Boolean {
        val client = try {
            FrostHttp.client(source).newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        } catch (_: Exception) {
            return false
        }
        // A progressive file has no manifest to read: one short ranged answer is the whole check.
        if (source.format == VodFormat.MP4) return fetch(client, source.url, ranged = true)?.code in 200..299
        val manifest = fetch(client, source.url, ranged = false) ?: return false
        if (manifest.code !in 200..299) return false
        if (!looksLikeManifest(manifest.contentType, manifest.body)) return false
        val target = manifestTargets(source.format, source.url, manifest.body).firstOrNull() ?: return true
        // A signed segment that no longer answers means the manifest outlived its own content.
        val media = fetch(client, target, ranged = true) ?: return false
        return media.code in 200..299
    }

    private fun fetch(client: OkHttpClient, url: String, ranged: Boolean): Answer? = try {
        val request = Request.Builder().url(url).apply {
            if (ranged) header("Range", "bytes=0-1023")
        }.build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            val text = if (body == null) "" else try {
                body.byteStream().use { stream ->
                    val buffer = ByteArray(PREFIX_BYTES)
                    var total = 0
                    while (total < buffer.size) {
                        val count = stream.read(buffer, total, buffer.size - total)
                        if (count <= 0) break
                        total += count
                    }
                    String(buffer, 0, total, Charsets.UTF_8)
                }
            } catch (_: Exception) {
                ""
            }
            Answer(response.code, response.header("Content-Type").orEmpty(), text)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The first media resource a manifest names, resolved against it. Empty when the document names
     * none (a progressive file, or a DASH `SegmentBase`), in which case the manifest is the contract.
     */
    fun manifestTargets(format: VodFormat, base: String, body: String): List<String> = when (format) {
        VodFormat.DASH -> dashTargets(base, body)
        VodFormat.HLS -> hlsTargets(base, body)
        VodFormat.MP4 -> emptyList()
    }

    private fun dashTargets(base: String, body: String): List<String> {
        // Any representation will do: they share one template and one signature.
        val representationId = Regex("<Representation[^>]*\\bid\\s*=\\s*\"([^\"]{1,32})\"")
            .find(body)?.groupValues?.get(1).orEmpty()
        val template = INITIALIZATION.find(body)?.groupValues?.get(1)
            ?: MEDIA.find(body)?.groupValues?.get(1)
            ?: return emptyList()
        // The document is read as raw text here, so the XML escapes a manifest always carries stay in it.
        val filled = xmlUnescape(template)
            .replace("\$RepresentationID\$", representationId.ifBlank { "0" })
            .replace("\$Number%05d\$", "00001")
            .replace("\$Number\$", "1")
            .replace("\$Time\$", "0")
            .replace(BANDWIDTH, "0")
        return resolve(base, filled)
    }

    private fun hlsTargets(base: String, body: String): List<String> {
        EXT_X_MAP.find(body)?.groupValues?.get(1)?.let { return resolve(base, it) }
        val first = body.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return emptyList()
        return resolve(base, first)
    }

    private fun resolve(base: String, reference: String): List<String> = try {
        val value = if (reference.startsWith("https://", true) || reference.startsWith("http://", true)) reference
        else URI(base).resolve(reference.replace(" ", "%20")).toString()
        listOf(value).filter { it.startsWith("https://", true) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun xmlUnescape(value: String): String = value
        .replace("&amp;", "&").replace("&#38;", "&").replace("&#x26;", "&")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private val INITIALIZATION = Regex("\\binitialization\\s*=\\s*\"([^\"]{1,4096})\"")
    private val MEDIA = Regex("\\bmedia\\s*=\\s*\"([^\"]{1,4096})\"")
    private val BANDWIDTH = Regex("\\\$Bandwidth\\\$")
    private val EXT_X_MAP = Regex("#EXT-X-MAP:[^\\n]*URI=\"([^\"]{1,4096})\"")

    /**
     * A provider's seek-bar thumbnails are published as a real HLS playlist: `#EXTM3U`, `#EXTINF`
     * durations and `.jpg` media lines. Its header is indistinguishable from video, so it has to be
     * recognised by what its media lines point at.
     */
    fun isImagePlaylist(body: String): Boolean {
        if (!body.contains("#EXTM3U", true)) return false
        val media = body.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.take(16).toList()
        if (media.size < 2) return false
        val images = media.count { line ->
            val path = line.substringBefore('?').substringBefore('#').lowercase()
            IMAGE_SUFFIXES.any { path.endsWith(it) }
        }
        return images * 4 >= media.size * 3
    }

    private val IMAGE_SUFFIXES = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif")

    /** DASH/HLS markers, or a media/adaptive content type. An HTML, JSON or sprite-sheet body does not count. */
    fun looksLikeManifest(contentType: String, prefix: String): Boolean {
        val head = prefix.take(4_000)
        val type = contentType.substringBefore(';').trim().lowercase()
        if (head.contains("#EXTM3U", true)) return !isImagePlaylist(head)
        if (head.contains("<MPD", true)) return true
        if (type.contains("dash+xml") || type.contains("mpegurl") || type.contains("vnd.apple")) return true
        if (type.contains("html") || type.contains("json")) return false
        return type.startsWith("video/") || type == "application/octet-stream" || type == "binary/octet-stream"
    }
}
