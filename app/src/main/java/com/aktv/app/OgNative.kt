package com.aktv.app

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * theogpiratebot.online's movie and series servers, played by the **native** player.
 *
 * The site publishes no stream of its own: each of its servers is a third party embed player keyed by a
 * stable name (`vidstucki`, `vidfast`, `vidlink`, …). What those players have in common is that the page
 * they serve for a title contains the address of the stream it will play - a bare `.m3u8`/`.mp4`, or a
 * JSON field holding one, or one more embed in an iframe. That is what this resolver reads: it asks each
 * server's page for the title, takes the media addresses the page itself names, and hands them to the
 * same native Media3 player that plays TG FROST and TG OUT. Nothing is decrypted, no DRM is bypassed, and
 * a server that answers with no address simply contributes no source.
 *
 * The addresses are read fresh on every title, because a provider rotates them; the alternative - storing
 * one - would be a dead source within hours. Every address found is probed before it is played by the
 * player's normal preflight, so a dead one is skipped instead of shown as a black screen.
 */
internal object OgNative {

    const val SITE = "https://theogpiratebot.online"
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_CANDIDATES = 12

    /** One of the site's servers: a stable key, the label the viewer sees, and the page it embeds. */
    internal class Server(val key: String, val label: String, val embed: (FrostTitle) -> String)

    private fun kind(title: FrostTitle) = if (title.isSeries) "tv" else "movie"

    private fun path(title: FrostTitle) =
        if (title.isSeries) "${title.id}/${title.season}/${title.episode}" else "${title.id}"

    /**
     * The embed families the site's own player lists. Each is asked for the title in its own addressing
     * scheme; a family whose page names no media address contributes nothing.
     */
    val SERVERS: List<Server> = listOf(
        Server("vidstuck", "Vidstuck") { t -> "https://vidstuck.xyz/embed/${kind(t)}/${path(t)}" },
        Server("vidfast", "Vidfast") { t -> "https://vidfast.pro/${kind(t)}/${path(t)}?autoPlay=true" },
        Server("vidlink", "Vidlink") { t -> "https://vidlink.pro/${kind(t)}/${path(t)}" },
    )

    fun server(key: String): Server? = SERVERS.firstOrNull { it.key == key }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(false).build()

    /** What each server said, so a title with no source explains itself instead of just failing. */
    @Volatile private var outcome = ""

    fun lastOutcome(): String = outcome

    /** Every server, in the site's order: the player's own preflight drops the ones that do not answer. */
    fun resolve(title: FrostTitle): List<FrostSource> {
        val found = LinkedHashSet<FrostSource>()
        val notes = mutableListOf<String>()
        for (server in SERVERS) {
            try {
                val sources = sources(title, server)
                notes += "${server.label}: " + if (sources.isEmpty()) "no address on its page" else "${sources.size} source(s)"
                found += sources
            } catch (error: Exception) {
                // One server refusing is not a failed title: the next server is asked.
                val status = (error as? FrostResolveException)?.status
                notes += "${server.label}: " + (status?.let { "HTTP $it" } ?: "unreachable")
                continue
            }
            if (found.size >= MAX_CANDIDATES) break
        }
        outcome = notes.joinToString(" · ").take(220)
        if (found.isEmpty()) throw FrostResolveException(kind = "empty")
        return found.toList()
    }

    /** One server, as the native sources its page names. */
    internal fun sources(
        title: FrostTitle,
        server: Server,
        fetch: (String) -> String? = ::fetch
    ): List<FrostSource> {
        val page = server.embed(title).takeIf { it.startsWith("https://", true) } ?: return emptyList()
        val body = fetch(page) ?: return emptyList()
        val addresses = LinkedHashSet<String>()
        addresses += candidates(body, page)
        // A server that wraps another one names it in an iframe; one hop is enough to reach the media page.
        iframe(body)?.let { nested ->
            absolute(nested, page)?.let { url -> fetch(url)?.let { inner -> addresses += candidates(inner, url) } }
        }
        return addresses.mapNotNull { url ->
            val format = format(url) ?: return@mapNotNull null
            FrostSource(
                provider = FrostProvider.OG,
                identity = "og:${server.key}:${url.hashCode()}",
                url = url,
                format = format,
                quality = "",
                language = "",
                hevc = null,
                headers = mapOf("User-Agent" to TgHttp.USER_AGENT, "Referer" to "$SITE/"),
                subtitles = emptyList(),
                credentialOrigin = url,
                scraperName = "OG ${server.label}"
            )
        }
    }

    fun cancel() { client.dispatcher.cancelAll() }

    private fun fetch(url: String): String? = try {
        val request = Request.Builder().url(url)
            .header("User-Agent", TgHttp.USER_AGENT)
            .header("Accept", "text/html,application/json,*/*")
            .header("Referer", "$SITE/")
            .header("Origin", SITE)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw FrostResolveException(response.code)
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val input = body.byteStream()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) return null
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    } catch (error: FrostResolveException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /**
     * The media addresses a page names: a bare `.m3u8`/`.mp4`, or the JSON field a player keeps it in
     * (`file`, `playlist`, `source`, `src`, `hls`), with escaped slashes resolved.
     */
    internal fun candidates(body: String, page: String): List<String> {
        val text = body.replace("\\/", "/")
        val addresses = LinkedHashSet<String>()
        bareMedia.findAll(text).forEach { addresses += it.value }
        keyedMedia.findAll(text).forEach { match ->
            val value = match.groupValues[1]
            if (format(value) != null) addresses += value
        }
        val resolved = LinkedHashSet<String>()
        for (address in addresses) {
            absolute(address, page)?.takeIf { format(it) != null }?.let { resolved += it }
            if (resolved.size >= MAX_CANDIDATES) break
        }
        return resolved.toList()
    }

    /** An embed inside an embed: the site's own servers are wrapped this way, and so are some providers. */
    internal fun iframe(body: String): String? =
        iframeTag.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.startsWith("https://", true) }

    /** HLS and MP4 only: a DASH manifest from these hosts is Widevine, which this app does not touch. */
    internal fun format(url: String): VodFormat? = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "m3u8" -> VodFormat.HLS
        "mp4" -> VodFormat.MP4
        else -> null
    }

    private fun absolute(value: String, page: String): String? = try {
        val uri = URI(page).resolve(value.trim())
        uri.toASCIIString().takeIf {
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null
        }
    } catch (_: Exception) {
        null
    }

    private val bareMedia = Regex("""(?i)https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""")
    private val keyedMedia = Regex(
        """(?i)"(?:file|playlist|source|src|hls|url)"\s*:\s*"(https?://[^"\s]+)""""
    )
    private val iframeTag = Regex("""(?i)<iframe[^>]+src\s*=\s*["']([^"']+)["']""")
}
