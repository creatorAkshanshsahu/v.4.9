package com.aktv.app

/**
 * Curated Live TV shelves.
 *
 * `allinonereborn2.online/sony/` lists its channels by opaque slug and embeds a third-party web
 * player (`suniliv.pages.dev/ptest1.html?id=<slug>`) that resolves the source with Media Source
 * Extensions - there is no static manifest to read, and the app deliberately never opens a web
 * player. Each slug does, however, name a Sony channel that is already in the native catalogue.
 *
 * The shelf is therefore selected from the **loaded catalogue by channel name**, never by a list of
 * hand-written IDs. An earlier revision hard-coded guessed Jio IDs and 14 of them resolved to
 * unrelated channels (Star Sports, Star Gold, Maa TV, a news channel); a live check caught it. Names
 * come from the provider, so this cannot drift into showing a different broadcaster.
 *
 * Playback still runs on the existing native TG/GioTV ClearKey path: nothing here remaps a source,
 * invents a URL, or borrows another channel's credentials.
 *
 * The same name-matched shelf is also the new **Sony section of the playlist tab**, where the tiles come
 * from the allinonereborn2.online M3U8 catalogue itself. There the stream, its ClearKey key document and
 * its cookie all come from that playlist, so the section plays natively without the TG or GioTV lookup.
 */
internal object LiveCollections {

    /** The catalogued Sony shelf. It is never replaced: the playlist section below sits next to it. */
    const val SONY_TITLE = "Sony Live"
    /** The separate section for the allinonereborn2.online/sony page's channels, played from the playlist. */
    const val SONY_PLAYLIST_TITLE = "Sony Live · Allinone Playlist"

    data class Spec(
        val title: String,
        val kicker: String,
        /** Name tokens in the order the source page presents them; longest match wins. */
        val order: List<String>,
        /** Builder-defined selection. Match the provider's own name, never a guessed ID. */
        val matches: (String) -> Boolean
    )

    /** Sony-branded channels plus the Sony-operated entries that do not carry the word Sony. */
    private fun isSony(name: String): Boolean {
        val n = name.lowercase()
        if (n.contains("liv sports")) return false          // SonyLIV app-only streams, not on the page
        return n.contains("sony") || n.startsWith("set ") || n == "set hd" ||
            n.contains("sony aath") || n.startsWith("aath") || n.startsWith("bbc earth")
    }

    /**
     * The Sony page's tiles in the order they appear on it, each named by the token that identifies it
     * in the provider's own channel names. Names are matched loosely (case/punctuation-insensitive)
     * because the provider writes them its way, e.g. `Sony Yay Hindi` for the page's SONY YAY. Plain and
     * HD tiles both exist upstream, so the tokens are precise enough to tell `Sony Max` from `Sony MAX2`
     * and `Sony Ten 3` from `Sony Ten 3 Hindi HD`.
     */
    private val PAGE_ORDER = listOf(
        "set hd",                                          // sony-hd (Sony's own identity for it)
        "sony sab hd", "sony sab sd",                      // sab-hd
        "sony marathi", "sony pal", "sony aath", "sony yay",// sony-marathi / pal / aath / yay
        "sony max hd", "sony max", "sony wah", "sony max2", "sony pix",   // sony-max-hd .. sony-pix-hd
        "sony ten 1 hd", "sony ten 1", "sony ten 2 hd", "sony ten 2",
        "sony ten 3 hd", "sony ten 3", "sony ten 4 hd", "sony ten 4",
        "sony ten 5 hd", "sony ten 5",
        "bbc earth"
    )

    val SONY = Spec(
        title = SONY_TITLE,
        kicker = "SONY CHANNELS \u00b7 NATIVE CLEARKEY",
        order = PAGE_ORDER,
        matches = ::isSony
    )

    val shelves: List<Spec> get() = listOf(SONY)

    /**
     * Position in the page order. The *longest* matching token wins, so a specific tile never inherits
     * the position of a shorter one it happens to contain - `Sony MAX2` is not `Sony Max`, and
     * `Sony Ten 3 Hindi HD` lands next to the other Ten 3 tiles instead of at the end.
     */
    private fun rank(name: String, spec: Spec): Int {
        val n = name.lowercase()
        return spec.order.withIndex()
            .filter { n.contains(it.value) }
            .maxByOrNull { it.value.length }?.index
            ?: spec.order.size
    }

    /**
     * Select the shelf from the catalogue. V2 IDs are excluded: they reuse numeric IDs for different
     * channels and are not Jio channel IDs. Order follows the source page, then by name.
     */
    fun channels(spec: Spec, catalogue: List<MainActivity.Channel>): List<MainActivity.Channel> =
        catalogue.filter { !it.v2 && spec.matches(it.name) }
            .distinctBy { it.key }
            .sortedWith(compareBy({ rank(it.name, spec) }, { it.name.lowercase() }))
}

/**
 * The `allinonereborn2.online/sony/` page itself: one tile per channel, in the page's own order.
 *
 * The page links every tile to a third-party web player (`suniliv.pages.dev/ptest1.html?id=<slug>`),
 * which the app never opens. What the page does tell us is *which* Sony channels its author lists and
 * how they are ordered, so the section is rebuilt from that list and the tiles are then bound to the
 * playlist's own channels — the ones that carry a native manifest, ClearKey document and cookie.
 *
 * A tile is matched by name only. No channel id, stream or credential is copied from the page, and a
 * channel the loaded catalogue does not carry is left out rather than guessed.
 */
internal object SonyPage {
    data class Tile(val slug: String, val names: List<String>)

    /** Page order, with the names each tile is published under upstream (plain and HD variants differ). */
    val TILES: List<Tile> = listOf(
        Tile("sony-hd", listOf("set hd", "sony hd", "sony entertainment television hd")),
        Tile("sab-hd", listOf("sony sab hd", "sony sab")),
        Tile("sony-marathi", listOf("sony marathi")),
        Tile("sony-pal", listOf("sony pal")),
        Tile("sony-aath", listOf("sony aath", "aath")),
        Tile("sony-yay", listOf("sony yay")),
        Tile("sony-max-hd", listOf("sony max hd")),
        Tile("sony-max", listOf("sony max")),
        Tile("sony-wah", listOf("sony wah")),
        Tile("sony-max2", listOf("sony max2")),
        Tile("sony-pix-hd", listOf("sony pix hd", "sony pix")),
        Tile("sony-ten-1-hd", listOf("sony ten 1 hd")),
        Tile("sony-ten-1", listOf("sony ten 1")),
        Tile("sony-ten-2-hd", listOf("sony ten 2 hd")),
        Tile("sony-ten-2", listOf("sony ten 2")),
        Tile("sony-ten-3-hd", listOf("sony ten 3 hd")),
        Tile("sony-ten-3", listOf("sony ten 3")),
        Tile("sony-ten-4-hd", listOf("sony ten 4 hd")),
        Tile("sony-ten-4", listOf("sony ten 4")),
        Tile("sony-ten-5-hd", listOf("sony ten 5 hd")),
        Tile("sony-ten-5", listOf("sony ten 5")),
        Tile("bbc-earth", listOf("bbc earth"))
    )

    const val TITLE = LiveCollections.SONY_PLAYLIST_TITLE

    private fun normalise(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

    /**
     * One tile per page channel, in page order. An exact name wins, then the first name that contains
     * the tile's alias; a channel is used once, so `Sony Ten 3 HD` and `Sony Ten 3` stay two tiles.
     */
    fun channels(catalogue: List<MainActivity.Channel>): List<MainActivity.Channel> {
        val pool = catalogue.filter { !it.v2 }.distinctBy { it.key }
        if (pool.isEmpty()) return emptyList()
        val normalised = pool.map { it to normalise(it.name) }
        val used = LinkedHashSet<String>()
        val out = ArrayList<MainActivity.Channel>(TILES.size)
        for (tile in TILES) {
            val aliases = tile.names.map(::normalise)
            val exact = normalised.firstOrNull { (channel, name) -> channel.key !in used && name in aliases }?.first
            val loose = exact ?: normalised.firstOrNull { (channel, name) ->
                channel.key !in used && aliases.any { alias -> name.contains(alias) }
            }?.first
            if (loose != null) { used.add(loose.key); out.add(loose) }
        }
        return out
    }
}
