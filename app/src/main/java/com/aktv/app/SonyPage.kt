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
        Tile("sony-hd", "SONY HD", listOf("set hd", "sony hd", "sony entertainment television hd"), "Sony_HD.png"),
        Tile("sab-hd", "SAB HD", listOf("sony sab hd", "sony sab"), "Sony_SAB_HD.png"),
        Tile("sony-marathi", "SONY MARATHI", listOf("sony marathi"), "Sony_Marathi_SD.png"),
        Tile("sony-pal", "SONY PAL", listOf("sony pal"), "Sony_Pal.png"),
        Tile("sony-aath", "SONY AATH", listOf("sony aath", "aath"), "Sony_aath.png"),
        Tile("sony-yay", "SONY YAY", listOf("sony yay hindi", "sony yay"), "Sony_Yay_Hindi.png"),
        Tile("sony-max-hd", "SONY MAX HD", listOf("sony max hd"), "Sony_Max_HD.png"),
        Tile("sony-max", "SONY MAX", listOf("sony max sd", "sony max"), "Sony_Max_SD.png"),
        Tile("sony-wah", "SONY WAH", listOf("sony wah"), "Sony_Wah.png"),
        Tile("sony-max2", "SONY MAX2", listOf("sony max2"), "Sony_MAX2.png"),
        Tile("sony-pix-hd", "SONY PIX HD", listOf("sony pix hd", "sony pix"), "Sony_Pix_HD.png"),
        Tile("sony-ten-1-hd", "SONY TEN 1 HD", listOf("sony ten 1 hd"), "Ten_HD.png"),
        Tile("sony-ten-1", "SONY TEN 1", listOf("sony ten 1 sd", "sony ten 1"), "Ten_1.png"),
        Tile("sony-ten-2-hd", "SONY TEN 2 HD", listOf("sony ten 2 hd"), "Ten2_HD.png"),
        Tile("sony-ten-2", "SONY TEN 2", listOf("sony ten 2 sd", "sony ten 2"), "Ten2_HD.png"),
        Tile("sony-ten-3-hd", "SONY TEN 3 HD", listOf("sony ten 3 hd"), "Ten3_HD.png"),
        Tile("sony-ten-3", "SONY TEN 3", listOf("sony ten 3 hindi hd", "sony ten 3"), "Ten3_HD.png"),
        Tile("sony-ten-4-hd", "SONY TEN 4 HD", listOf("sony ten 4 hd"), "Ten_4_HD_Telugu.png"),
        Tile("sony-ten-4", "SONY TEN 4", listOf("sony ten 4 sd", "sony ten 4"), "Ten_4_Telugu.png"),
        Tile("sony-ten-5-hd", "SONY TEN 5 HD", listOf("sony ten 5 hd"), "Six_HD.png"),
        Tile("sony-ten-5", "SONY TEN 5", listOf("sony ten 5 sd", "sony ten 5"), "Six_HD.png"),
        Tile("bbc-earth", "BBC EARTH", listOf("bbc earth"), "Sony_BBC_Earth_HD.png")
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
     * The section, and it is only the page: one tile per link the site publishes, in the site's order,
     * with the site's own label and logo. Nothing is added that the page does not list, and nothing is
     * dropped from it — 22 tiles, always.
     *
     * Every tile plays that site's own player for its slug inside the app, because that is the source the
     * user asked for. A tile whose channel is also in the site's playlist keeps that channel's identity
     * (so favourites and recents line up), but the player is still the page's, not a native fallback.
     */
    fun channels(catalogue: List<MainActivity.Channel>): List<MainActivity.Channel> {
        val pool = catalogue.filter { !it.v2 }.distinctBy { it.key }
        val names = pool.map { normalise(it.name) to it }.toMap()
        val files = pool.map { logoFile(it.logo) to it }.toMap()
        val taken = HashSet<String>()

        fun words(channel: MainActivity.Channel): List<String> =
            channel.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
                .let { if (it.firstOrNull() == "sony") it.drop(1) else it }

        /** The tile's name, optionally followed only by a quality or language word. */
        fun qualifies(channel: MainActivity.Channel, alias: String): Boolean {
            val wanted = alias.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
                .let { if (it.firstOrNull() == "sony") it.drop(1) else it }
            val actual = words(channel)
            if (wanted.isEmpty() || actual.size < wanted.size) return false
            if (actual.subList(0, wanted.size) != wanted) return false
            return actual.drop(wanted.size).all { it in SUFFIX_WORDS }
        }

        // Exact name, then the CDN logo file, then the name plus a quality/language suffix. Each pass is
        // global, so an exact match always beats a loose one.
        val assigned = HashMap<String, MainActivity.Channel>()
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

        // The page in page order, and nothing else. A tile keeps its playlist identity when there is one.
        return TILES.map { tile ->
            val bound = assigned[tile.slug]
            MainActivity.Channel(
                id = bound?.id ?: "page:" + tile.slug,
                name = tile.title,
                category = "Sony",
                logo = LOGO + tile.logo,
                v2 = false,
                jio = bound != null,
                pinned = true,
                page = pageUrl(tile.slug)
            )
        }
    }

    /** Words that may follow a page name without changing which channel it is. */
    private val SUFFIX_WORDS = setOf(
        "hd", "sd", "uhd", "4k", "hindi", "english", "tamil", "telugu", "bengali", "kannada",
        "malayalam", "marathi", "punjabi", "gujarati", "hd1"
    )

    /**
     * The same 22 tiles for the JioTV section, where the playlist is the player: a tile the playlist carries
     * keeps that identity and plays natively, so it must not carry the page - those are the Live TV tiles. A
     * tile the playlist does not carry has nothing to play natively, so it keeps its page rather than going
     * silent.
     */
    fun playlistChannels(pool: List<MainActivity.Channel>): List<MainActivity.Channel> =
        channels(pool).map { if (it.jio) it.copy(page = null) else it }
}
