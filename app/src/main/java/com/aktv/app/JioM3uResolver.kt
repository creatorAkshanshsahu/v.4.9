package com.aktv.app

import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI

/**
 * Fetches the allinonereborn2.online JioTV m3u8 playlist and resolves channels natively.
 *
 * Most playlist entries do not carry their ClearKey keys inline: they point at the provider's key
 * document (`#KODIPROP:...license_key=https://…/jtv-fetch/keyid.php?id=<channel>`), which answers a
 * plain GET with `{"keys":[{"kty":"oct","k":"…","kid":"…"}]}`. Without that document the native player
 * had no keys at all and refused such a channel before the first frame, which is why these playlist
 * channels previously only worked on the website. The keys are fetched here, kept in memory for a few
 * minutes and never written to disk.
 */
internal class JioM3uResolver(
    private val http: TgTransport = TgHttp(),
    private val playlist: String = JioM3uProtocol.PLAYLIST,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    /** The last playlist that answered, kept on disk so a refused refresh cannot empty the section. */
    private val cacheFile: java.io.File? = null
) {
    companion object {
        const val SITE = JioM3uProtocol.SITE
        /** Key documents are small and rotate rarely; a rejected channel re-reads them through `fresh`. */
        private const val KEY_TTL_MS = 10 * 60_000L
        /** The probe must stay well inside the provider's playback window, so it never becomes the delay. */
        private const val PROBE_BUDGET_MS = 8_000L
    }
    private data class Cached<T>(val until: Long, val value: T)
    private var playlistCache: Cached<List<JioM3uProtocol.Entry>>? = null
    private var catalog: Cached<Map<String, JioM3uProtocol.Entry>>? = null
    private var playlistCookie: Cached<String>? = null
    private val sources = HashMap<String, Cached<List<TgStream>>>()
    private val keyCache = HashMap<String, Cached<Map<String, String>>>()
    private val tokenClock = HttpReferenceClock(now, elapsed)
    /** Index into [playlistRequests] of the shape that last answered; tried first next time. */
    @Volatile private var playlistShape = 0

    @Synchronized
    fun resolve(id: String, fresh: Boolean = false): List<TgStream> {
        require(id.matches(Regex("[0-9]{1,10}")) || id.matches(Regex("[0-9]{6}"))) { "Invalid Jio channel id" }
        checkInterrupted()
        if (fresh) {
            playlistCache = null; catalog = null; sources.clear(); keyCache.clear()
        } else sources[id]?.takeIf { it.until > elapsed() }?.let { return it.value }

        val entries = loadPlaylist(fresh)
        val entry = entries.firstOrNull { it.id == id } ?: throw IOException("JioTV m3u8 has no matching channel ID")
        val streams = open(entry, keysFor(entry, fresh))
        val result = streams
        val until = elapsed() + 30_000
        if (until > elapsed()) sources[id] = Cached(until, result)
        return result
    }

    /**
     * Open the channel the way a player would, and hand Media3 the address that actually answered.
     *
     * Two things made these channels fail before. The playlist's `#EXTHTTP` cookie is a *Jio CDN* token;
     * the player's request policy re-sent it as `?__hdnea__=…` on the playlist host, so the redirector
     * was asked for a channel with a signed query it did not mint (and those tokens expire within hours).
     * And the player was pointed at a URL that only answers with a 302, so a redirect the site refuses
     * surfaced as an opaque decoder error instead of "HTTP 403".
     *
     * The probe follows the redirect here, keeps the signed CDN address, validates that the body really is
     * a manifest, and retries with the playlist's own cookie when the host refuses a plain request.
     */
    private fun open(entry: JioM3uProtocol.Entry, keys: Map<String, String>): List<TgStream> {
        val type = JioM3uProtocol.mediaType(entry.url)
        val base = JioM3uProtocol.toTgStream(entry, keys)
        if (type == "mp4") return listOf(base)
        val plain = JioM3uProtocol.requestHeaders(entry)
        val attempts = ArrayList<Map<String, String>>(4).apply {
            // First the request an IPTV player sends, because that is the one this endpoint answers: a
            // `jtv1.mpd?id=N` request carrying `Origin`/`Referer` is refused with HTTP 451 before it ever
            // redirects to the CDN, which is why every channel except the few that ignore it stayed black.
            add(JioM3uProtocol.mediaHeaders(entry))
            add(plain)
            val cookie = JioM3uProtocol.cookieHeaders(entry)
            if (cookie.isNotEmpty()) add(plain + cookie)
            add(plain + ("User-Agent" to TgHttp.USER_AGENT))
        }
        var refusal = ""
        val started = elapsed()
        for (headers in attempts) {
            checkInterrupted()
            if (elapsed() - started > PROBE_BUDGET_MS) { if (refusal.isEmpty()) refusal = "probe timed out"; break }
            val response = try {
                http.get(entry.url, headers)
            } catch (error: InterruptedIOException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (response == null) { refusal = "network error"; continue }
            if (response.code !in 200..299) { refusal = "HTTP ${response.code}"; continue }
            val body = response.body.toString(Charsets.UTF_8)
            if (!JioM3uProtocol.isManifest(type, body)) { refusal = "not a ${type.uppercase()} manifest"; continue }
            val finalUrl = response.finalUrl?.takeIf { it.startsWith("https://", true) } ?: entry.url
            return candidates(tuned(base, entry, finalUrl, body, keys), entry)
        }
        // The player still gets the advertised URL: a probe is not allowed to remove a candidate. The
        // redirector is first here because it is all that is left, and it re-mints the token itself.
        return listOf(redirector(base, entry).copy(label = "${base.label} · $refusal"))
    }

    /**
     * The same channel as several candidates, because the CDN's two credentials disagree by design.
     *
     * The playlist's `#EXTHTTP` cookie is a coarse token minted for the whole site; the URL behind the
     * redirect carries a token minted for that one channel and path. A player that works sends both and
     * lets the CDN choose; a CDN that rejects the coarse one needs the bare request. Rather than guess
     * which is true for a channel today, the player is given both, in the order that reaches a picture
     * fastest, and the normal recovery loop moves to the next one when a stream does not start.
     */
    private fun candidates(probed: TgStream, entry: JioM3uProtocol.Entry): List<TgStream> = listOf(
        probed.copy(label = "${probed.label} · CDN"),
        probed.copy(label = "${probed.label} · CDN, no cookie",
            headers = JioM3uProtocol.mediaHeaders(entry, includeCookie = false)),
        redirector(probed, entry)
    )

    /** The playlist's own address, opened the way an IPTV player opens it: it redirects and re-signs. */
    private fun redirector(from: TgStream, entry: JioM3uProtocol.Entry) = from.copy(
        url = entry.url,
        headers = JioM3uProtocol.mediaHeaders(entry),
        label = "${from.label.substringBefore(" · ")} · playlist redirect"
    )

    /** Keep the manifest's own key ids, and stop sending the playlist's headers to a CDN of another origin. */
    private fun tuned(base: TgStream, entry: JioM3uProtocol.Entry, finalUrl: String, manifest: String, keys: Map<String, String>): TgStream {
        val sameHost = try {
            URI(finalUrl).host.orEmpty().equals(URI(JioM3uProtocol.SITE).host.orEmpty(), true)
        } catch (_: Exception) {
            false
        }
        val matched = matchKeys(keys, JioM3uProtocol.defaultKids(manifest))
        val stream = base.copy(
            url = finalUrl,
            // The playlist host keeps its browser-style headers plus the playlist's cookie; a CDN of
            // another origin gets exactly what an IPTV player sends: user agent and cookie.
            headers = if (sameHost) base.headers + JioM3uProtocol.cookieHeaders(entry)
            else JioM3uProtocol.mediaHeaders(entry),
            drm = JioM3uProtocol.buildDrm(entry, matched)
        )
        return stream
    }

    /**
     * A ClearKey licence must name the key id the manifest actually uses. When the key document and the
     * MPD disagree the session decrypts nothing, which shows up as a black screen with no error text, so
     * a single advertised key is relabelled onto the manifest's key id instead of being dropped.
     */
    private fun matchKeys(keys: Map<String, String>, manifestKids: Set<String>): Map<String, String> {
        if (keys.isEmpty() || manifestKids.isEmpty()) return keys
        if (keys.keys.any { it in manifestKids }) return keys
        if (keys.size != 1) return keys
        val key = keys.values.first()
        return LinkedHashMap<String, String>().apply { manifestKids.forEach { put(it, key) } }
    }

    /**
     * Ask the playlist's own key endpoint for this channel's ClearKey document. A failure is not fatal:
     * the stream keeps the advertised endpoint and the player asks for it when it opens the DRM session.
     */
    private fun keysFor(entry: JioM3uProtocol.Entry, fresh: Boolean): Map<String, String> {
        val keyUrl = JioM3uProtocol.licenseUrl(entry) ?: return emptyMap()
        if (!fresh) keyCache[entry.id]?.takeIf { it.until > elapsed() }?.let { return it.value }
        return try {
            checkInterrupted()
            val response = http.get(keyUrl, linkedMapOf(
                "Origin" to SITE,
                "Referer" to "$SITE/",
                "Accept" to "application/json",
                "User-Agent" to JioM3uProtocol.UA_OVERRIDE,
            ))
            if (response.code !in 200..299) return emptyMap()
            if (response.body.size > JioM3uProtocol.MAX_KEY_BYTES) return emptyMap()
            val keys = JioM3uProtocol.parseKeys(response.body.toString(Charsets.UTF_8))
            if (keys.isNotEmpty()) keyCache[entry.id] = Cached(elapsed() + KEY_TTL_MS, keys)
            keys
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Returns all channel entries from the playlist, for building a UI catalogue. */
    @Synchronized
    fun catalogue(fresh: Boolean = false): List<JioM3uProtocol.Entry> = loadPlaylist(fresh)

    @Synchronized
    fun categoryGroups(fresh: Boolean = false): List<Pair<String, List<JioM3uProtocol.Entry>>> {
        return JioM3uProtocol.categories(loadPlaylist(fresh))
    }

    private fun loadPlaylist(fresh: Boolean): List<JioM3uProtocol.Entry> {
        playlistCache?.takeIf { !fresh && it.until > elapsed() }?.let { return it.value }
        val body = try {
            fetchPlaylist(fresh).also(::keepPlaylist)
        } catch (error: Exception) {
            if (error is InterruptedIOException) throw error
            // A refused request must not empty the section. The last good copy describes the same channels,
            // and each channel's own address, cookie and keys are still resolved fresh when it is played.
            return lastGoodPlaylist() ?: throw error
        }
        val entries = JioM3uProtocol.parse(body)
        require(entries.isNotEmpty()) { "JioTV m3u8 playlist was empty" }
        cache(entries, 30 * 60_000)  // the playlist URL is stable; refresh every 30 min
        return entries
    }

    private fun cache(entries: List<JioM3uProtocol.Entry>, ttlMs: Long) {
        val until = elapsed() + ttlMs
        playlistCache = Cached(until, entries)
        catalog = Cached(until, entries.associateBy { it.id })
    }

    /** The playlist that answered last time, retried after five minutes rather than cached for thirty. */
    private fun lastGoodPlaylist(): List<JioM3uProtocol.Entry>? {
        val file = cacheFile ?: return null
        val text = try { if (file.exists()) file.readText() else "" } catch (_: Exception) { "" }
        if (text.isBlank()) return null
        val entries = JioM3uProtocol.parse(text)
        if (entries.isEmpty()) return null
        cache(entries, 5 * 60_000)
        return entries
    }

    /** Kept on disk only so a refused refresh cannot empty the section: no tokens, cookies or keys. */
    private fun keepPlaylist(body: String) {
        val file = cacheFile ?: return
        try { file.writeText(body) } catch (_: Exception) { }
    }

    /**
     * The playlist file itself, asked for the way whichever client the host currently accepts asks for it.
     *
     * 4.9.59 hit `JioTV m3u8 HTTP 415` and the whole JioTV section went empty: the player-shaped request this
     * build had been sending (its own `User-Agent`, the site as `Origin`/`Referer`, an mpegurl `Accept`) is
     * refused by the front door now. A browser-shaped request is tried first, then an IPTV player's own, then
     * the shape that used to work, and the one that answers is remembered for the rest of the session.
     */
    private fun fetchPlaylist(fresh: Boolean): String {
        val url = if (fresh) "$playlist?_aktv_refresh=${now()}" else playlist
        val shapes = playlistRequests()
        var status = 0
        var refusal = "network error"
        for (index in shapes.indices) {
            val position = (playlistShape + index) % shapes.size
            checkInterrupted()
            val response = try {
                http.get(url, shapes[position])
            } catch (error: InterruptedIOException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (response == null) { refusal = "network error"; continue }
            tokenClock.observe(response.serverTimeMs)
            if (response.code !in 200..299) { status = response.code; refusal = "HTTP ${response.code}"; continue }
            require(response.body.size <= TgProtocol.MAX_BODY * 4) { "JioTV m3u8 playlist too large" }
            val body = response.body.toString(Charsets.UTF_8)
            if (JioM3uProtocol.parse(body).isEmpty()) { refusal = "the file listed no channels"; continue }
            playlistShape = position
            return body
        }
        throw JioM3uHttpException(status, refusal)
    }

    /**
     * Most conservative first, because a refusal here costs the whole section: a plain browser request, then
     * an IPTV player's own identity, then the player-shaped request this build shipped with.
     */
    private fun playlistRequests(): List<Map<String, String>> = listOf(
        linkedMapOf("Accept" to "*/*"),
        linkedMapOf("Accept" to "*/*", "User-Agent" to JioM3uProtocol.UA_OVERRIDE),
        linkedMapOf("Accept" to "application/vnd.apple.mpegurl, application/x-mpegurl"),
        linkedMapOf("Origin" to SITE, "Referer" to "$SITE/", "Accept" to "*/*"),
        linkedMapOf(
            "Origin" to SITE, "Referer" to "$SITE/",
            "Accept" to "application/vnd.apple.mpegurl, application/x-mpegurl",
            "User-Agent" to JioM3uProtocol.UA_OVERRIDE
        ),
    )

    @Synchronized
    fun invalidate() {
        playlistCache = null; catalog = null; sources.clear(); keyCache.clear()
    }
}

internal class JioM3uHttpException(val status: Int, val reason: String? = null) : IOException(
    if (status > 0) "JioTV m3u8 HTTP $status" else "JioTV m3u8 unavailable (${reason ?: "network error"})"
)
