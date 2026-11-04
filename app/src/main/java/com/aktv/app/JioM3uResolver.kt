package com.aktv.app

import org.json.JSONObject
import java.io.IOException

/** Fetches the allinonereborn2.online JioTV m3u8 playlist and resolves channels natively. */
internal class JioM3uResolver(
    private val http: TgTransport = TgHttp(),
    private val playlist: String = JioM3uProtocol.PLAYLIST,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    companion object { const val SITE = JioM3uProtocol.SITE }
    private data class Cached<T>(val until: Long, val value: T)
    private var playlistCache: Cached<List<JioM3uProtocol.Entry>>? = null
    private var catalog: Cached<Map<String, JioM3uProtocol.Entry>>? = null
    private var playlistCookie: Cached<String>? = null
    private val sources = HashMap<String, Cached<List<TgStream>>>()
    private val tokenClock = HttpReferenceClock(now, elapsed)

    @Synchronized
    fun resolve(id: String, fresh: Boolean = false): List<TgStream> {
        require(id.matches(Regex("[0-9]{1,10}")) || id.matches(Regex("[0-9]{6}"))) { "Invalid Jio channel id" }
        checkInterrupted()
        if (fresh) {
            playlistCache = null; catalog = null; sources.clear()
        } else sources[id]?.takeIf { it.until > elapsed() }?.let { return it.value }

        val entries = loadPlaylist(fresh)
        val entry = entries.firstOrNull { it.id == id } ?: throw IOException("JioTV m3u8 has no matching channel ID")
        val stream = JioM3uProtocol.toTgStream(entry)
        val result = listOf(stream)
        val until = elapsed() + 30_000
        if (until > elapsed()) sources[id] = Cached(until, result)
        return result
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
        playlistCache = null; catalog = null; sources.clear()
    }
}

internal class JioM3uHttpException(val status: Int) : IOException("JioTV m3u8 HTTP $status")
