package com.aktv.app

import android.content.Context
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

    /** The application context, so a page whose stream only exists after its scripts run can still be read. */
    @Volatile var appContext: Context? = null

    /** What each server said, so a title with no source explains itself instead of just failing. */
    @Volatile private var outcome = ""

    fun lastOutcome(): String = outcome

    /** The same lines in full, one per line, for the player's details dialog. */
    @Volatile private var report = ""
    fun lastReport(): String = report

    /** What was found for a title, so a second open is instant and the entry never vanishes from the menu. */
    private data class Memo(val until: Long, val sources: List<FrostSource>)
    private val memo = HashMap<String, Memo>()
    private fun memoKey(t: FrostTitle) = "${t.type}:${t.id}:${t.season}:${t.episode}"

    fun cached(title: FrostTitle): List<FrostSource> = synchronized(memo) {
        memo[memoKey(title)]?.takeIf { it.until > System.currentTimeMillis() }?.sources
    } ?: emptyList()

    /** Every server, in the site's order: the player's own preflight drops the ones that do not answer. */
    fun resolve(title: FrostTitle, fresh: Boolean = false): List<FrostSource> {
        // A stream found for this title a moment ago is reused: starting a film must not cost another search.
        cached(title).takeIf { !fresh && it.isNotEmpty() }?.let { return it }
        val found = LinkedHashSet<FrostSource>()
        val notes = mutableListOf<String>()
        // This site's own player is TG underneath - its PLAYERS menu offers TG FROST and TG OUT for the same
        // title id - so TG's own endpoint is asked first. That is headless (no browser engine, so it also
        // works on a television), and it is the stream the site itself would play.
        for (provider in listOf(FrostProvider.FROST, FrostProvider.OUT)) {
            val viaTg = runCatching { TgNative.resolve(title, provider) }.getOrDefault(emptyList())
            notes += "${provider.label} (the site's own player): " + if (viaTg.isEmpty()) "no source" else "${viaTg.size} source(s)"
            viaTg.forEach { found += it }
        }
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
        // No page named an address in its markup, because these players also build one in script. The
        // address is still in what the page serves - in a JSON field, an embedded player, or a manifest -
        // so the pages are crawled over HTTP: the site's own player first, then its servers, in its order.
        if (found.isEmpty()) {
            for (key in serverKeys()) {
                val hits = runCatching { PageCrawl.crawl(playerUrl(title, key), "$SITE/", 9_000) }
                    .getOrDefault(emptyList())
                notes += "server $key: " + if (hits.isEmpty()) "no media address on its page" else "${hits.size} address(es)"
                if (hits.isNotEmpty()) {
                    preferredServer = key
                    hits.forEach { hit -> sourceOf(hit.url, "player-$key", "OG $key", hit.page)?.let { found += it } }
                    break
                }
            }
        }
        if (found.isEmpty()) {
            // A viewer will not wait a minute: the whole search gets a deadline, and the servers are
            // asked in the site's own order until it expires.
            val deadline = System.currentTimeMillis() + 45_000
            for (server in SERVERS) {
                if (System.currentTimeMillis() > deadline) { notes += "search deadline reached"; break }
                val hits = runCatching { PageCrawl.crawl(server.embed(title), "$SITE/", 12_000) }
                    .getOrDefault(emptyList())
                notes += "${server.label} player: " + if (hits.isEmpty()) "no media address on its page" else "${hits.size} address(es)"
                if (hits.isNotEmpty()) {
                    hits.forEach { hit -> sourceOf(hit.url, server.key, "OG ${server.label}", hit.page)?.let { found += it } }
                    break
                }
            }
        }
        report = notes.joinToString("\n")
        outcome = notes.joinToString(" · ").take(320)
        if (found.isEmpty()) throw FrostResolveException(kind = "empty")
        // An address that answers "not found" is not a source: drop it here instead of spending sixteen
        // seconds of the viewer's time watching a spinner for it.
        val live = FrostPreflight.keep(found.toList())
        notes += if (live.size == found.size) "all addresses answered" else "${found.size - live.size} stale address(es) dropped"
        report = notes.joinToString("\n")
        outcome = notes.joinToString(" · ").take(320)
        if (live.isEmpty()) throw FrostResolveException(kind = "stale")
        val result = live
        synchronized(memo) { memo[memoKey(title)] = Memo(System.currentTimeMillis() + 30 * 60_000, result) }
        return result
    }

    /** One server, as the native sources its page names. */
    internal fun sources(
        title: FrostTitle,
        server: Server,
        fetch: (String) -> String? = ::fetch
    ): List<FrostSource> {
        val page = server.embed(title).takeIf { it.startsWith("https://", true) } ?: return emptyList()
        val found = ArrayList<FrostSource>()
        val seen = HashSet<String>()
        var queue = ArrayDeque<Pair<String, String?>>()
        queue.add(page to "$SITE/")
        // A server that wraps another one names it in a frame; three hops reach the media page and no more.
        repeat(PageCrawl.MAX_DEPTH) {
            if (found.isNotEmpty() || queue.isEmpty() || seen.size >= 6) return@repeat
            val nested = ArrayDeque<Pair<String, String?>>()
            for ((url, referer) in queue) {
                if (!seen.add(url)) continue
                val body = try { fetch(url) } catch (_: Exception) { null } ?: continue
                for (address in candidates(body, url)) {
                    sourceOf(address, server.key, "OG ${server.label}", url)?.let { found += it }
                }
                if (found.isNotEmpty()) break
                for (frame in PageCrawl.frames(body, url)) nested.add(frame to url)
            }
            queue = nested
        }
        return found.distinctBy { it.url }
    }

    /** One media address, as a source the native player can take. */
    private fun sourceOf(url: String, key: String, label: String, referer: String? = null): FrostSource? {
        val format = format(url) ?: return null
        val page = referer?.takeIf { it.startsWith("https://", true) } ?: "$SITE/"
        val origin = PageCrawl.origin(page) ?: SITE
        return FrostSource(
            provider = FrostProvider.OG,
            identity = "og:$key:${url.hashCode()}",
            url = url,
            format = format,
            quality = "",
            language = "",
            hevc = null,
            headers = mapOf("User-Agent" to TgHttp.USER_AGENT, "Referer" to page, "Origin" to origin),
            subtitles = emptyList(),
            credentialOrigin = url,
            scraperName = label
        )
    }

    /**
     * The site's own servers are addressed by name, not by number: the viewer's link for a film reads
     * `player?id=656908&type=movie&server=elfura`. These are the names this app knows; the one that works is
     * moved to the front for the rest of the session, so the next title starts with it.
     */
    private val SITE_SERVERS = listOf("elfura", "vidfast", "vidstuck", "vidlink")

    @Volatile private var preferredServer: String? = null

    /** The address of the page the site's own player shows for a title, on one of its servers. */
    fun playerUrl(title: FrostTitle, server: String? = null): String =
        "$SITE/player?id=${title.id}&type=${kind(title)}" +
            (server?.let { "&server=$it" } ?: "") +
            if (title.isSeries) "&season=${title.season}&episode=${title.episode}" else ""

    /** Every name worth asking, the one that worked last time first. */
    private fun serverKeys(): List<String> = (listOfNotNull(preferredServer) + SITE_SERVERS).distinct()

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
    internal fun format(url: String): VodFormat? = when (PageCrawl.format(url)) {
        VodFormat.HLS -> VodFormat.HLS
        VodFormat.MP4 -> VodFormat.MP4
        else -> null // A DASH manifest from these hosts means Widevine.
    }

    private fun absolute(value: String, page: String): String? = try {
        val uri = URI(page).resolve(value.trim())
        uri.toASCIIString().takeIf {
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null
        }
    } catch (_: Exception) {
        null
    }

    private val bareMedia = Regex("""(?i)(?:https?:)?//[^\s"'<>\\]+?\.(?:m3u8|m3u|mp4|m4v)(?:\?[^\s"'<>\\]*)""")
    private val keyedMedia = Regex(
        """(?i)"(?:file|playlist|source|src|hls|url|link|stream)"\s*:\s*"((?:https?:)?//[^"\s]+)""""
    )
    private val iframeTag = Regex("""(?i)<iframe[^>]+src\s*=\s*["']([^"']+)["']""")
}
