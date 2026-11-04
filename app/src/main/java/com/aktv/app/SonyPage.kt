package com.aktv.app

/**
 * The `allinonereborn2.online/sony/` page, rebuilt as the app's one Sony section.
 *
 * The page lists 22 Sony tiles and sends each of them to its own web player
 * (`suniliv.pages.dev/ptest1.html?id=<slug>`), a browser player that resolves the stream with Media
 * Source Extensions — there is no manifest to read in its markup, and the app never opens a page. What
 * the page does establish is the *list*: which Sony channels its author carries and in what order.
 * Each tile is bound to the matching channel of `allinonereborn2.online`'s own playlist
 * (`jtv33.m3u8`), which supplies the native manifest (`jtv1.mpd?id=<n>`), its ClearKey document
 * (`jtv-fetch/keyid.php?id=<n>`) and the channel cookie.
 *
 * A tile is matched by name first (`Sony Ten 3 Hindi HD`), then by the CDN logo file the page and the
 * playlist both publish (`Ten3_HD.png`), so a channel the playlist renames still binds. No id, stream
 * or credential is copied from another provider, and the tiles are **pinned**: `ChannelProviderOrder`
 * gives them the playlist only, so a Sony tile can never silently fall back to TG or GioTV. The user
 * asked for this site's source and nothing else.
 */
internal object SonyPage {
    /**
     * [slug] as the page links it, the title the page shows, the names it appears under in the playlist,
     * and the logo it is published with.
     */
    data class Tile(val slug: String, val title: String, val names: List<String>, val logo: String)

    /** Page order, exactly as the page lists the channels. */
    val TILES: List<Tile> = listOf(
        Tile("sony-hd", "Sony HD", listOf("set hd", "sony hd", "sony entertainment television hd"), "Sony_HD.png"),
        Tile("sab-hd", "SAB HD", listOf("sony sab hd", "sony sab"), "Sony_SAB_HD.png"),
        Tile("sony-marathi", "Sony Marathi", listOf("sony marathi"), "Sony_Marathi_SD.png"),
        Tile("sony-pal", "Sony Pal", listOf("sony pal"), "Sony_Pal.png"),
        Tile("sony-aath", "Sony Aath", listOf("sony aath", "aath"), "Sony_aath.png"),
        Tile("sony-yay", "Sony Yay", listOf("sony yay hindi", "sony yay"), "Sony_Yay_Hindi.png"),
        Tile("sony-max-hd", "Sony Max HD", listOf("sony max hd"), "Sony_Max_HD.png"),
        Tile("sony-max", "Sony Max", listOf("sony max sd", "sony max"), "Sony_Max_SD.png"),
        Tile("sony-wah", "Sony Wah", listOf("sony wah"), "Sony_Wah.png"),
        Tile("sony-max2", "Sony Max 2", listOf("sony max2"), "Sony_MAX2.png"),
        Tile("sony-pix-hd", "Sony Pix HD", listOf("sony pix hd", "sony pix"), "Sony_Pix_HD.png"),
        Tile("sony-ten-1-hd", "Sony Ten 1 HD", listOf("sony ten 1 hd"), "Ten_HD.png"),
        Tile("sony-ten-1", "Sony Ten 1", listOf("sony ten 1 sd", "sony ten 1"), "Ten_1.png"),
        Tile("sony-ten-2-hd", "Sony Ten 2 HD", listOf("sony ten 2 hd"), "Ten2_HD.png"),
        Tile("sony-ten-2", "Sony Ten 2", listOf("sony ten 2 sd", "sony ten 2"), "Ten2_HD.png"),
        Tile("sony-ten-3-hd", "Sony Ten 3 HD", listOf("sony ten 3 hd"), "Ten3_HD.png"),
        Tile("sony-ten-3", "Sony Ten 3", listOf("sony ten 3 hindi hd", "sony ten 3"), "Ten3_HD.png"),
        Tile("sony-ten-4-hd", "Sony Ten 4 HD", listOf("sony ten 4 hd"), "Ten_4_HD_Telugu.png"),
        Tile("sony-ten-4", "Sony Ten 4", listOf("sony ten 4 sd", "sony ten 4"), "Ten_4_Telugu.png"),
        Tile("sony-ten-5-hd", "Sony Ten 5 HD", listOf("sony ten 5 hd"), "Six_HD.png"),
        Tile("sony-ten-5", "Sony Ten 5", listOf("sony ten 5 sd", "sony ten 5"), "Six_HD.png"),
        Tile("bbc-earth", "Sony BBC Earth", listOf("bbc earth"), "Sony_BBC_Earth_HD.png")
    )

    /** The one Sony row. There is no other Sony shelf anywhere in the app. */
    const val TITLE = "Sony Live · Allinone"

    /** The site's own player page for one tile; it is the tile's stream when the playlist has none. */
    const val PAGE = "https://suniliv.pages.dev/ptest1?id="
    fun pageUrl(slug: String) = PAGE + slug

    private const val LOGO = "https://jiotvimages.cdn.jio.com/dare_images/images/"

    private fun normalise(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

    /** The logo file name a URL points at, e.g. `Ten3_HD.png` for either CDN path. */
    private fun logoFile(value: String) = value.substringAfterLast('/').substringBefore('?').lowercase()

    /**
     * The section: the page's tiles in page order, then any other Sony channel the playlist carries that
     * the page does not list (Sony's regional Yay and BBC Earth feeds, SAB SD, MAX1 …), so the section is
     * every Sony channel this source has. A channel is used once and every tile is pinned to the playlist.
     *
     * A page tile the playlist does not carry is left out rather than filled from another provider. The
     * live playlist currently carries 14 of the page's 22 tiles: its SD twins and the Ten 4/Ten 5 feeds
     * are web-player-only, and showing them from a different source is exactly what was rejected.
     */
    fun channels(catalogue: List<MainActivity.Channel>): List<MainActivity.Channel> {
        val pool = catalogue.filter { !it.v2 }.distinctBy { it.key }
        if (pool.isEmpty()) return emptyList()
        val names = pool.map { normalise(it.name) to it }.toMap()
        val files = pool.map { logoFile(it.logo) to it }.toMap()
        val taken = HashSet<String>()
        val assigned = HashMap<String, MainActivity.Channel>()

        /** A channel's normalised words without a leading "sony", so `BBC Earth HD` can meet `bbc earth`. */
        fun words(channel: MainActivity.Channel): List<String> =
            channel.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
                .let { if (it.firstOrNull() == "sony") it.drop(1) else it }

        /** The page's name, optionally followed only by a quality or language word. */
        fun qualifies(channel: MainActivity.Channel, alias: String): Boolean {
            val wanted = alias.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
                .let { if (it.firstOrNull() == "sony") it.drop(1) else it }
            val actual = words(channel)
            if (wanted.isEmpty() || actual.size < wanted.size) return false
            if (actual.subList(0, wanted.size) != wanted) return false
            return actual.drop(wanted.size).all { it in SUFFIX_WORDS }
        }

        // Exact name, then the CDN logo file, then the name plus a quality/language suffix. Each pass is
        // global, so an exact match always beats a loose one - `Sony MAX1` can never claim the MAX tile
        // before the MAX2 tile has had its exact chance.
        for (tile in TILES) {
            val hit = tile.names.map(::normalise).firstNotNullOfOrNull { alias ->
                names[alias]?.takeIf { it.key !in taken }
            } ?: continue
            taken.add(hit.key); assigned[tile.slug] = hit
        }
        for (tile in TILES) {
            if (tile.slug in assigned) continue
            val hit = files[tile.logo.lowercase()]?.takeIf { it.key !in taken } ?: continue
            taken.add(hit.key); assigned[tile.slug] = hit
        }
        for (tile in TILES) {
            if (tile.slug in assigned) continue
            val hit = pool.firstOrNull { it.key !in taken && tile.names.any { alias -> qualifies(it, alias) } }
                ?: continue
            taken.add(hit.key); assigned[tile.slug] = hit
        }

        val out = ArrayList<MainActivity.Channel>(TILES.size)
        // Page order first, then the rest of the source's Sony channels in the order it lists them.
        for (tile in TILES) {
            val bound = assigned[tile.slug]
            if (bound != null) {
                out.add(bound.copy(jio = true, pinned = true, page = pageUrl(tile.slug)))
            } else {
                // The page lists this channel but the playlist does not carry it (the SD twins and the
                // Ten 4/Ten 5 feeds). It is still a tile of this section: it plays the site's own player
                // for its slug, inside the app, so no tile of the section is ever left without a picture.
                out.add(
                    MainActivity.Channel(
                        id = "page:" + tile.slug, name = tile.title, category = "Sony",
                        logo = LOGO + tile.logo, v2 = false, jio = false, pinned = true,
                        page = pageUrl(tile.slug)
                    )
                )
            }
        }
        for (channel in pool) {
            if (channel.key in taken || !normalise(channel.name).startsWith("sony")) continue
            taken.add(channel.key)
            out.add(channel.copy(jio = true, pinned = true))
        }
        return out
    }

    /** Words that may follow a page name without changing which channel it is. */
    private val SUFFIX_WORDS = setOf(
        "hd", "sd", "uhd", "4k", "hindi", "english", "tamil", "telugu", "bengali", "kannada",
        "malayalam", "marathi", "punjabi", "gujarati", "hd1"
    )
}
