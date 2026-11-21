package com.aktv.app

/**
 * The Flix home: a light glass screen in the shape of the reference design — wordmark left, a dark
 * pill of tabs beside the search glyph, a bell and a profile chip right, two hero cards, a chip
 * strip, a section header with sort/filter, then the shelves with captions under the posters.
 *
 * This file holds the tokens and the pure view logic (which chips a tab gets, how sort/filter
 * reshape the rows, what the section header says). The screen only renders what these return, so
 * the rules are unit-tested here without a device.
 */
internal object FlixColors {
    val SCREEN_TOP = 0xFF6F8AA1.toInt()
    val SCREEN_MID = 0xFF9DB1C3.toInt()
    val SCREEN_BOT = 0xFF879DAC.toInt()
    /** The big rounded container the whole screen sits in: white glass over the gradient. */
    val CONTAINER = 0x2EFFFFFF
    /** Dark navy ink for copy on the glass. */
    val INK = 0xFF1A2340.toInt()
    val MUTED = 0xFF5A6B8C.toInt()
    val FAINT = 0xFF8B98B8.toInt()
    /** The near-black navy of the tab pill and the sort/filter pill. */
    val PILL = 0xFF141B31.toInt()
    val PILL_HI = 0xFF232C4D.toInt()
    val TAB_IDLE = 0xFF98A2BC.toInt()
    val CHIP = 0x4DFFFFFF
    val CHIP_SELECTED = 0xFFE8EEF7.toInt()
    /** Channel tiles: white glass that still takes a breath of the channel's own tint. */
    val CARD = 0x66FFFFFF
    val CAPTION = 0xFFD7E1F0.toInt()
    /** The counters under a poster: rating gold, divider and year pale. */
    val POSTER_META = 0xFFE4EAF5.toInt()
    val GOLD = Accent.GOLD
    val RED = Accent.LIVE_DOT
}

/** One chip in the strip: what it says and which row it jumps to. */
internal data class FlixChip(val id: String, val label: String, val glyph: String, val row: Int)

/** Which chips a tab's strip shows, derived from the rows that actually loaded. */
internal object FlixChips {
    // Mirrors the private row titles in MainActivity: these rows are furniture, not destinations.
    private val SKIP = setOf("Favorites", "Recently watched", "Continue watching", "Playlists", "Trending Now", "Trending on AK")
    private val MAX_CHIPS = 24

    fun chipsFor(tab: Int, rows: List<MainActivity.Row>, vod: Boolean): List<FlixChip> {
        if (rows.isEmpty()) return emptyList()
        val chips = ArrayList<FlixChip>()
        if (vod) {
            val used = HashSet<Int>()
            rows.indexOfFirst { it.title.startsWith("Trending", ignoreCase = true) }.takeIf { it >= 0 }?.let {
                chips += FlixChip("trending", "Trending", "✦", it); used += it
            }
            rows.indexOfFirst { it.title.startsWith("Top rated", ignoreCase = true) }.takeIf { it >= 0 }?.let {
                chips += FlixChip("top", "Top rated", "★", it); used += it
            }
            rows.forEachIndexed { i, row ->
                if (i in used) return@forEachIndexed
                val genre = genreOf(row.title) ?: return@forEachIndexed
                if (row.tiles.any { it is MainActivity.Tile.Mv }) chips += FlixChip("g$i", genre, glyphForGenre(genre), i)
            }
            if (tab == 8) rows.forEachIndexed { i, row ->
                if (chips.any { it.row == i }) return@forEachIndexed
                if (row.title == "Continue watching") return@forEachIndexed
                if (row.tiles.any { it is MainActivity.Tile.Mv }) chips += FlixChip("p$i", row.title, "●", i)
            }
        } else {
            chips += FlixChip("all", "All", "✦", 0)
            rows.indexOfFirst { it.title == "Favorites" }.takeIf { it >= 0 }?.let {
                chips += FlixChip("fav", "Favorites", "♥", it)
            }
            rows.forEachIndexed { i, row ->
                if (i == 0 || row.title in SKIP) return@forEachIndexed
                if (row.tiles.none { it is MainActivity.Tile.Ch }) return@forEachIndexed
                chips += FlixChip("c$i", row.title, glyphForCategory(row.title), i)
            }
        }
        return chips.take(MAX_CHIPS)
    }

    /** "Action movies" -> "Action"; platform and cross rows are not genre rows. */
    fun genreOf(title: String): String? {
        val lower = title.lowercase()
        val suffix = when {
            lower.endsWith(" movies") -> " movies"
            lower.endsWith(" series") -> " series"
            else -> return null
        }
        return title.dropLast(suffix.length).trim().takeIf { it.isNotEmpty() && "·" !in it && it.length < 24 }
    }

    fun glyphForGenre(genre: String): String = when (genre.lowercase()) {
        "action" -> "◆"; "adventure" -> "▲"; "animation" -> "●"; "comedy" -> "☾"
        "crime" -> "◆"; "documentary" -> "▶"; "drama" -> "★"; "family" -> "♥"
        "fantasy" -> "✦"; "horror" -> "☾"; "mystery" -> "●"; "romance" -> "♥"
        "sci-fi", "science fiction" -> "✦"; "thriller" -> "▲"; "reality-tv" -> "★"
        else -> "●"
    }

    fun glyphForCategory(category: String): String {
        val c = category.lowercase()
        return when {
            "news" in c -> "●"; "sport" in c || "cricket" in c || "football" in c -> "▲"
            "movie" in c || "film" in c -> "▶"; "kid" in c || "cartoon" in c || "child" in c -> "◆"
            "music" in c -> "♪"; "entertain" in c || "drama" in c || "serial" in c -> "★"
            else -> "●"
        }
    }
}

/**
 * The sort/filter pill above the shelves. Pure row reshaping on already-loaded rows: no network,
 * nothing to wait for. The default state is the identity — the shelves draw exactly as built.
 */
internal object FlixView {
    val SORT_DEFAULT = 0
    val SORT_AZ = 1
    val SORT_RATING = 2

    fun apply(
        rows: List<MainActivity.Row>,
        sort: Int,
        filterOn: Boolean,
        favs: Set<String>,
        vod: Boolean
    ): List<MainActivity.Row> {
        if (sort == SORT_DEFAULT && !filterOn) return rows
        return rows.mapNotNull { row ->
            var tiles = row.tiles
            if (filterOn) tiles = tiles.filter { keep(it, favs, vod) }
            if (sort != SORT_DEFAULT) tiles = sortTiles(tiles, sort)
            if (tiles.isEmpty()) null else MainActivity.Row(row.title, tiles, row.landscape)
        }
    }

    private fun keep(t: MainActivity.Tile, favs: Set<String>, vod: Boolean): Boolean = when {
        vod && t is MainActivity.Tile.Mv -> ratingOf(t.m) >= 6.0
        !vod && t is MainActivity.Tile.Ch -> t.c.key in favs
        else -> true   // hints and actions always stay: the shelf never goes blank
    }

    private fun sortTiles(tiles: List<MainActivity.Tile>, sort: Int): List<MainActivity.Tile> =
        tiles.sortedWith { a, b ->
            when {
                a is MainActivity.Tile.Mv && b is MainActivity.Tile.Mv ->
                    if (sort == SORT_RATING) ratingOf(b.m).compareTo(ratingOf(a.m))
                    else a.m.name.lowercase().compareTo(b.m.name.lowercase())
                a is MainActivity.Tile.Ch && b is MainActivity.Tile.Ch ->
                    if (sort == SORT_AZ) a.c.name.lowercase().compareTo(b.c.name.lowercase()) else 0
                else -> 0
            }
        }

    fun ratingOf(m: Movies.Title): Double = m.rating.toDoubleOrNull() ?: 0.0

    /** The section header: the reference screen says "Trending in Animation" over a genre. */
    fun headerFor(tabName: String, chip: FlixChip?, vod: Boolean): String {
        if (chip == null || chip.id == "all") return tabName
        if (vod && (chip.id.startsWith("g") || chip.id == "trending" || chip.id == "top")) {
            if (chip.id == "trending" || chip.id == "top") return chip.label
            return "Trending in ${chip.label}"
        }
        return chip.label
    }
}
