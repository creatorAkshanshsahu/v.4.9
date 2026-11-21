package com.aktv.app

/**
 * One entry a plain M3U playlist lists. Everything the player needs is already in the file — address,
 * name, artwork, group — so a tune needs no handshake, no session and no second request.
 *
 * Entries whose streams are DRM-protected or token-gated also declare that in the file
 * (`#KODIPROP`, `#EXTHTTP`, `#EXTVLCOPT`); those declarations travel with the entry because a tune
 * that drops them plays a bare URL the CDN refuses.
 */
internal data class M3uChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String,
    val url: String,
    /** The playlist's own channel number (`tvg-id`), when it names one. Blank otherwise. */
    val tvgId: String = "",
    /** `#EXTVLCOPT:http-user-agent=…` declared for this entry, when present. */
    val userAgent: String = "",
    /** `#EXTHTTP:{"cookie":"…"}` declared for this entry, when present. */
    val cookie: String = "",
    /** `#KODIPROP:inputstream.adaptive.license_type=…` (e.g. `clearkey`), when present. */
    val licenseType: String = "",
    /** `#KODIPROP:inputstream.adaptive.license_key=…` (key-document URL or inline `kid:key`), when present. */
    val licenseKey: String = ""
)

/**
 * Reads the subset of the M3U dialect IPTV lists actually use:
 *
 * ```
 * #EXTM3U
 * #EXTINF:-1 tvg-id="143" tvg-logo="https://…/CNBCTV18Prime.png" group-title="English",CNBC TV18 Prime
 * #KODIPROP:inputstream.adaptive.license_type=clearkey
 * #KODIPROP:inputstream.adaptive.license_key=https://…/keyid.php?id=143
 * https://…/143/index.mpd
 * ```
 *
 * `#EXTINF` attributes are quoted, the display name follows the last quoted attribute, `#EXTGRP` groups the
 * entries under it, and the address is the first line that is neither blank nor a `#` directive. Player
 * declarations (`#EXTVLCOPT`, `#KODIPROP`, `#EXTHTTP`) attach to their entry whether they appear above its
 * `#EXTINF` or between it and the address (the nearer ones win); anything else (`#EXT-X-…`) is skipped,
 * never guessed at.
 */
internal object M3uPlaylist {

    /** A playlist bigger than this is not a channel list any more; the app would rather fail cleanly. */
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 6_000

    /** How many group rows a section shows, and how many channels one row holds. */
    const val MAX_GROUPS = 24
    const val MAX_PER_ROW = 200

    private val attribute = Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")

    private val kodiLicenseType = Regex(
        """^#KODIPROP:inputstream\.adaptive\.license_type=(?<val>\S+)\s*$""", RegexOption.IGNORE_CASE
    )
    private val kodiLicenseKey = Regex(
        """^#KODIPROP:inputstream\.adaptive\.license_key=(?<val>\S+)\s*$""", RegexOption.IGNORE_CASE
    )
    private val extvlcUserAgent = Regex(
        """^#EXTVLCOPT:http-user-agent=(?<val>\S+)\s*$""", RegexOption.IGNORE_CASE
    )
    private val extHttpCookie = Regex(
        """^#EXTHTTP:\{"cookie":"(?<val>[^"]*)"\}\s*$""", RegexOption.IGNORE_CASE
    )

    fun parse(text: String): List<M3uChannel> {
        require(text.length <= MAX_BYTES) { "Playlist too large" }
        val out = ArrayList<M3uChannel>()
        val seen = HashSet<String>()
        var pending: M3uChannel? = null
        var group = ""
        var ordinal = 0
        var preLicenseType = ""
        var preLicenseKey = ""
        var preUserAgent = ""
        var preCookie = ""
        fun resetPre() {
            preLicenseType = ""; preLicenseKey = ""; preUserAgent = ""; preCookie = ""
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", true) -> {
                    pending = entry(line, group, ++ordinal, preLicenseType, preLicenseKey, preUserAgent, preCookie)
                    resetPre()
                }
                line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                line.startsWith("#") -> {
                    // A declaration above the #EXTINF belongs to the coming entry; one below it
                    // (before the address) overrides. Anything else is a comment or segment file.
                    val licenseType = kodiLicenseType.matchEntire(line)?.groupValues?.get(1).orEmpty()
                    val licenseKey = kodiLicenseKey.matchEntire(line)?.groupValues?.get(1).orEmpty()
                    val userAgent = extvlcUserAgent.matchEntire(line)?.groupValues?.get(1).orEmpty()
                    val cookie = extHttpCookie.matchEntire(line)?.groupValues?.get(1).orEmpty()
                    if (licenseType.isEmpty() && licenseKey.isEmpty() && userAgent.isEmpty() && cookie.isEmpty()) continue
                    val head = pending
                    if (head == null) {
                        if (licenseType.isNotEmpty()) preLicenseType = licenseType
                        if (licenseKey.isNotEmpty()) preLicenseKey = licenseKey
                        if (userAgent.isNotEmpty()) preUserAgent = userAgent
                        if (cookie.isNotEmpty()) preCookie = cookie
                    } else {
                        pending = head.copy(
                            licenseType = licenseType.ifEmpty { head.licenseType },
                            licenseKey = licenseKey.ifEmpty { head.licenseKey },
                            userAgent = userAgent.ifEmpty { head.userAgent },
                            cookie = cookie.ifEmpty { head.cookie }
                        )
                    }
                }
                else -> {
                    val head = pending
                    pending = null
                    resetPre()
                    head ?: continue
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

    private fun entry(
        line: String,
        fallbackGroup: String,
        ordinal: Int,
        licenseType: String = "",
        licenseKey: String = "",
        userAgent: String = "",
        cookie: String = ""
    ): M3uChannel {
        val attrs = attribute.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        val quoted = line.lastIndexOf('"')
        val comma = if (quoted >= 0) line.indexOf(',', quoted + 1) else line.indexOf(',')
        val display = if (comma >= 0) line.substring(comma + 1).trim() else ""
        val name = display.ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { "Channel $ordinal" }
        val group = attrs["group-title"].orEmpty().ifBlank { fallbackGroup }.ifBlank { "Live" }
        val tvgId = attrs["tvg-id"].orEmpty().ifBlank { attrs["tvg-chno"].orEmpty() }.ifBlank { attrs["channel-id"].orEmpty() }
        return M3uChannel(
            id = "", name = name, group = group, logo = attrs["tvg-logo"].orEmpty(), url = "",
            tvgId = tvgId, userAgent = userAgent, cookie = cookie,
            licenseType = licenseType, licenseKey = licenseKey
        )
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
