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
    /** [slug] as the page links it, the names it appears under, and the logo it is published with. */
    data class Tile(val slug: String, val names: List<String>, val logo: String)

    /** Page order, exactly as the page lists the channels. */
    val TILES: List<Tile> = listOf(
        Tile("sony-hd", listOf("set hd", "sony hd", "sony entertainment television hd"), "Sony_HD.png"),
        Tile("sab-hd", listOf("sony sab hd", "sony sab"), "Sony_SAB_HD.png"),
        Tile("sony-marathi", listOf("sony marathi"), "Sony_Marathi_SD.png"),
        Tile("sony-pal", listOf("sony pal"), "Sony_Pal.png"),
        Tile("sony-aath", listOf("sony aath", "aath"), "Sony_aath.png"),
        Tile("sony-yay", listOf("sony yay hindi", "sony yay"), "Sony_Yay_Hindi.png"),
        Tile("sony-max-hd", listOf("sony max hd"), "Sony_Max_HD.png"),
        Tile("sony-max", listOf("sony max sd", "sony max"), "Sony_Max_SD.png"),
        Tile("sony-wah", listOf("sony wah"), "Sony_Wah.png"),
        Tile("sony-max2", listOf("sony max2"), "Sony_MAX2.png"),
        Tile("sony-pix-hd", listOf("sony pix hd", "sony pix"), "Sony_Pix_HD.png"),
        Tile("sony-ten-1-hd", listOf("sony ten 1 hd"), "Ten_HD.png"),
        Tile("sony-ten-1", listOf("sony ten 1 sd", "sony ten 1"), "Ten_1.png"),
        Tile("sony-ten-2-hd", listOf("sony ten 2 hd"), "Ten2_HD.png"),
        Tile("sony-ten-2", listOf("sony ten 2 sd", "sony ten 2"), "Ten2_HD.png"),
        Tile("sony-ten-3-hd", listOf("sony ten 3 hd"), "Ten3_HD.png"),
        Tile("sony-ten-3", listOf("sony ten 3 hindi hd", "sony ten 3"), "Ten3_HD.png"),
        Tile("sony-ten-4-hd", listOf("sony ten 4 hd"), "Ten_4_HD_Telugu.png"),
        Tile("sony-ten-4", listOf("sony ten 4 sd", "sony ten 4"), "Ten_4_Telugu.png"),
        Tile("sony-ten-5-hd", listOf("sony ten 5 hd"), "Six_HD.png"),
        Tile("sony-ten-5", listOf("sony ten 5 sd", "sony ten 5"), "Six_HD.png"),
        Tile("bbc-earth", listOf("bbc earth"), "Sony_BBC_Earth_HD.png")
    )

    /** The one Sony row. There is no other Sony shelf anywhere in the app. */
    const val TITLE = "Sony Live · Allinone"

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
        val normalised = pool.map { Triple(it, normalise(it.name), logoFile(it.logo)) }
        val used = LinkedHashSet<String>()
        val out = ArrayList<MainActivity.Channel>(TILES.size)
        for (tile in TILES) {
            val aliases = tile.names.map(::normalise)
            val logo = tile.logo.lowercase()
            val picked =
                normalised.firstOrNull { (channel, name, _) -> channel.key !in used && name in aliases }?.first
                    ?: normalised.firstOrNull { (channel, _, file) -> channel.key !in used && file == logo }?.first
                    ?: normalised.firstOrNull { (channel, name, _) ->
                        channel.key !in used && aliases.any { alias -> name.contains(alias) }
                    }?.first
            if (picked != null) {
                used.add(picked.key)
                out.add(picked.copy(jio = true, pinned = true))
            }
        }
        // Only channels the provider itself brands Sony: three-letter tokens like "max" or "aath" also
        // match Jaya Max, Mahaa Max, Aakash Aath and MK Six, which are not this site's Sony section.
        for ((channel, name, _) in normalised) {
            if (channel.key in used || !name.startsWith("sony")) continue
            used.add(channel.key)
            out.add(channel.copy(jio = true, pinned = true))
        }
        return out
    }
}
