package com.aktv.app

/**
 * One entry a plain M3U playlist lists. Everything the player needs is already in the file — address,
 * name, artwork, group — so a tune needs no handshake, no session and no second request.
 */
internal data class M3uChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String,
    val url: String
)

/**
 * Reads the subset of the M3U dialect IPTV lists actually use:
 *
 * ```
 * #EXTM3U
 * #EXTINF:-1 tvg-id="143" tvg-logo="https://…/CNBCTV18Prime.png" group-title="English",CNBC TV18 Prime
 * https://…/143/index.m3u8
 * ```
 *
 * `#EXTINF` attributes are quoted, the display name follows the last quoted attribute, `#EXTGRP` groups the
 * entries under it, and the address is the first line that is neither blank nor a `#` directive. Player
 * hints (`#EXTVLCOPT`, `#KODIPROP`, `#EXT-X-…`) are skipped, never guessed at.
 */
internal object M3uPlaylist {

    /** A playlist bigger than this is not a channel list any more; the app would rather fail cleanly. */
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 6_000

    /** How many group rows a section shows, and how many channels one row holds. */
    const val MAX_GROUPS = 24
    const val MAX_PER_ROW = 200

    private val attribute = Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")

    fun parse(text: String): List<M3uChannel> {
        require(text.length <= MAX_BYTES) { "Playlist too large" }
        val out = ArrayList<M3uChannel>()
        val seen = HashSet<String>()
        var pending: M3uChannel? = null
        var group = ""
        var ordinal = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", true) -> pending = entry(line, group, ++ordinal)
                line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit                      // comments, player hints, segment files
                else -> {
                    val head = pending ?: continue
                    pending = null
                    val url = line.substringBefore(' ').trim()
                    if (!playable(url) || !seen.add(url)) continue
                    out += head.copy(id = id(url), url = url)
                    if (out.size >= MAX_ENTRIES) return out
                }
            }
        }
        return out
    }

    /** The stream address is the identity: the same channel keeps its id, and its favourite, across reloads. */
    fun id(url: String): String = Integer.toHexString(url.hashCode())

    private fun playable(url: String): Boolean {
        val lower = url.lowercase()
        // A playlist may also list rtmp/udp/uuid handles the native player cannot open; those are dropped
        // rather than shown as a tile that can only fail.
        if (!lower.contains("://")) return false
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun entry(line: String, fallbackGroup: String, ordinal: Int): M3uChannel {
        val attrs = attribute.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        val quoted = line.lastIndexOf('"')
        val comma = if (quoted >= 0) line.indexOf(',', quoted + 1) else line.indexOf(',')
        val display = if (comma >= 0) line.substring(comma + 1).trim() else ""
        val name = display.ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { "Channel $ordinal" }
        val group = attrs["group-title"].orEmpty().ifBlank { fallbackGroup }.ifBlank { "Live" }
        return M3uChannel(id = "", name = name, group = group, logo = attrs["tvg-logo"].orEmpty(), url = "")
    }

    /**
     * The section's rows: groups biggest first, then alphabetically, each capped so one huge category cannot
     * hold every channel in the playlist behind a single row.
     */
    fun <T> sections(
        items: List<T>,
        limitGroups: Int = MAX_GROUPS,
        limitPerRow: Int = MAX_PER_ROW,
        group: (T) -> String
    ): List<Pair<String, List<T>>> = items
        .groupBy { group(it).ifBlank { "Live" } }
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, List<T>>> { it.value.size }.thenBy { it.key })
        .take(limitGroups)
        .map { (name, list) -> name to list.take(limitPerRow) }
}
