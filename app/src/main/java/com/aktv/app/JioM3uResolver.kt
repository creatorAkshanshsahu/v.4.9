package com.aktv.app

import java.io.IOException

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
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    companion object {
        const val SITE = JioM3uProtocol.SITE
        /** Key documents are small and rotate rarely; a rejected channel re-reads them through `fresh`. */
        private const val KEY_TTL_MS = 10 * 60_000L
    }
    private data class Cached<T>(val until: Long, val value: T)
    private var playlistCache: Cached<List<JioM3uProtocol.Entry>>? = null
    private var catalog: Cached<Map<String, JioM3uProtocol.Entry>>? = null
    private var playlistCookie: Cached<String>? = null
    private val sources = HashMap<String, Cached<List<TgStream>>>()
    private val keyCache = HashMap<String, Cached<Map<String, String>>>()
    private val tokenClock = HttpReferenceClock(now, elapsed)

    @Synchronized
    fun resolve(id: String, fresh: Boolean = false): List<TgStream> {
        require(id.matches(Regex("[0-9]{1,10}")) || id.matches(Regex("[0-9]{6}"))) { "Invalid Jio channel id" }
        checkInterrupted()
        if (fresh) {
            playlistCache = null; catalog = null; sources.clear(); keyCache.clear()
        } else sources[id]?.takeIf { it.until > elapsed() }?.let { return it.value }

        val entries = loadPlaylist(fresh)
        val entry = entries.firstOrNull { it.id == id } ?: throw IOException("JioTV m3u8 has no matching channel ID")
        val stream = JioM3uProtocol.toTgStream(entry, keysFor(entry, fresh))
        val result = listOf(stream)
        val until = elapsed() + 30_000
        if (until > elapsed()) sources[id] = Cached(until, result)
        return result
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
        val body = fetchPlaylist(fresh)
        val entries = JioM3uProtocol.parse(body)
        require(entries.isNotEmpty()) { "JioTV m3u8 playlist was empty" }
        val until = elapsed() + 30 * 60_000  // playlist URL is stable; refresh every 30 min
        playlistCache = Cached(until, entries)
        catalog = Cached(until, entries.associateBy { it.id })
        return entries
    }

    private fun fetchPlaylist(fresh: Boolean): String {
        val url = if (fresh) "$playlist?_aktv_refresh=${now()}" else playlist
        val response = http.get(url, linkedMapOf(
            "Origin" to SITE,
            "Referer" to "$SITE/",
            "Accept" to "application/vnd.apple.mpegurl, application/x-mpegurl",
            "User-Agent" to JioM3uProtocol.UA_OVERRIDE,
        ))
        tokenClock.observe(response.serverTimeMs)
        if (response.code !in 200..299) throw JioM3uHttpException(response.code)
        require(response.body.size <= TgProtocol.MAX_BODY * 4) { "JioTV m3u8 playlist too large" }
        return response.body.toString(Charsets.UTF_8)
    }

    @Synchronized
    fun invalidate() {
        playlistCache = null; catalog = null; sources.clear(); keyCache.clear()
    }
}

internal class JioM3uHttpException(val status: Int) : IOException("JioTV m3u8 HTTP $status")
