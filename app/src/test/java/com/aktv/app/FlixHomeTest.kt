package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

/** The Flix home's view rules: chips, sort/filter, and the section header. No screen needed. */
class FlixHomeTest {

    private fun ch(id: String, name: String, category: String = "News") =
        MainActivity.Tile.Ch(MainActivity.Channel(id = id, name = name, category = category, logo = ""))

    private fun mv(name: String, rating: String = "", year: String = "2026", genres: List<String> = listOf("Action")) =
        MainActivity.Tile.Mv(Movies.Title(imdb = "tt$name", tmdb = 1, type = "movie", name = name,
            poster = "", background = "", logo = "", year = year, rating = rating, genres = genres, description = ""))

    private fun vodRows() = listOf(
        MainActivity.Row("Trending movies", listOf(mv("A"))),
        MainActivity.Row("Top rated movies", listOf(mv("B"))),
        MainActivity.Row("Action movies", listOf(mv("C"))),
        MainActivity.Row("Comedy movies", listOf(mv("D"))),
        MainActivity.Row("Continue watching", listOf(mv("E")))
    )

    private fun channelRows() = listOf(
        MainActivity.Row("Live Channels", listOf(ch("1", "One"))),
        MainActivity.Row("Playlists", listOf(MainActivity.Tile.Hint)),
        MainActivity.Row("Favorites", listOf(ch("2", "Two"))),
        MainActivity.Row("Trending Now", listOf(mv("F"))),
        MainActivity.Row("News", listOf(ch("3", "Three"))),
        MainActivity.Row("Sports", listOf(ch("4", "Four")))
    )

    @Test fun `VOD chips are trending, top rated, then the loaded genres`() {
        val chips = FlixChips.chipsFor(3, vodRows(), vod = true)
        assertEquals(listOf("Trending", "Top rated", "Action", "Comedy"), chips.map { it.label })
        assertEquals(listOf(0, 1, 2, 3), chips.map { it.row })
        assertEquals("✦", chips[0].glyph)
        assertEquals("★", chips[1].glyph)
        assertEquals("◆", chips[2].glyph)
    }

    @Test fun `channel chips are all, favourites, then the real categories`() {
        val chips = FlixChips.chipsFor(0, channelRows(), vod = false)
        assertEquals(listOf("All", "Favorites", "News", "Sports"), chips.map { it.label })
        assertEquals(listOf(0, 2, 4, 5), chips.map { it.row })
        assertEquals("♥", chips[1].glyph)
    }

    @Test fun `no rows means no chips`() {
        assertTrue(FlixChips.chipsFor(0, emptyList(), vod = false).isEmpty())
    }

    @Test fun `genre titles parse, platform and broad rows do not`() {
        assertEquals("Action", FlixChips.genreOf("Action movies"))
        assertEquals("Sci-Fi", FlixChips.genreOf("Sci-Fi series"))
        assertNull(FlixChips.genreOf("Netflix · Movies"))
        assertNull(FlixChips.genreOf("Continue watching"))
    }

    @Test fun `the default view leaves the shelves exactly as built`() {
        val rows = channelRows()
        assertSame(rows, FlixView.apply(rows, FlixView.SORT_DEFAULT, filterOn = false, favs = emptySet(), vod = false))
    }

    @Test fun `A to Z sorts channels by name`() {
        val rows = listOf(MainActivity.Row("News", listOf(ch("2", "Zulu"), ch("1", "alpha"), ch("3", "Mike"))))
        val names = FlixView.apply(rows, FlixView.SORT_AZ, filterOn = false, favs = emptySet(), vod = false)
            .first().tiles.filterIsInstance<MainActivity.Tile.Ch>().map { it.c.name }
        assertEquals(listOf("alpha", "Mike", "Zulu"), names)
    }

    @Test fun `top rated sorts films down, unrated last`() {
        val rows = listOf(MainActivity.Row("Action movies", listOf(mv("Low", "5.1"), mv("None"), mv("High", "8.4"))))
        val names = FlixView.apply(rows, FlixView.SORT_RATING, filterOn = false, favs = emptySet(), vod = true)
            .first().tiles.filterIsInstance<MainActivity.Tile.Mv>().map { it.m.name }
        assertEquals(listOf("High", "Low", "None"), names)
    }

    @Test fun `the channel filter keeps only favourites, never hints`() {
        val rows = listOf(
            MainActivity.Row("News", listOf(ch("1", "One"), ch("2", "Two"))),
            MainActivity.Row("Favorites", listOf(MainActivity.Tile.Hint))
        )
        val out = FlixView.apply(rows, FlixView.SORT_DEFAULT, filterOn = true, favs = setOf("2"), vod = false)
        assertEquals(2, out.size)
        assertEquals(listOf("Two"), out[0].tiles.filterIsInstance<MainActivity.Tile.Ch>().map { it.c.name })
        assertEquals(1, out[1].tiles.size)
    }

    @Test fun `the VOD filter keeps only films rated six and up`() {
        val rows = listOf(MainActivity.Row("Action movies", listOf(mv("Low", "5.9"), mv("High", "6.0"))))
        val out = FlixView.apply(rows, FlixView.SORT_DEFAULT, filterOn = true, favs = emptySet(), vod = true)
        assertEquals(listOf("High"), out.first().tiles.filterIsInstance<MainActivity.Tile.Mv>().map { it.m.name })
    }

    @Test fun `the header names the tab, or the chip's shelf`() {
        assertEquals("Movies", FlixView.headerFor("Movies", null, vod = true))
        assertEquals("Live TV", FlixView.headerFor("Live TV", FlixChip("all", "All", "✦", 0), vod = false))
        assertEquals("Trending in Animation", FlixView.headerFor("Movies", FlixChip("g6", "Animation", "●", 6), vod = true))
        assertEquals("Trending", FlixView.headerFor("Movies", FlixChip("trending", "Trending", "✦", 0), vod = true))
        assertEquals("News", FlixView.headerFor("Live TV", FlixChip("c4", "News", "●", 4), vod = false))
    }

    @Test fun `ratings parse, blanks count as zero`() {
        assertEquals(8.1, FlixView.ratingOf((mv("A", "8.1") as MainActivity.Tile.Mv).m), 0.001)
        assertEquals(0.0, FlixView.ratingOf((mv("B") as MainActivity.Tile.Mv).m), 0.001)
    }

    @Test fun `resume fraction is halfway at halfway, zero when finished or unknown`() {
        assertEquals(0.5f, FlixView.RailProgress.fraction(60_000, 120_000))
        assertEquals(0.25f, FlixView.RailProgress.fraction(30_000, 120_000))
        assertEquals(0f, FlixView.RailProgress.fraction(0, 120_000))
        assertEquals(0f, FlixView.RailProgress.fraction(60_000, 0))
        assertEquals(0f, FlixView.RailProgress.fraction(120_000, 120_000))
        assertEquals(0f, FlixView.RailProgress.fraction(100_000, 120_000))
        assertEquals(0f, FlixView.RailProgress.fraction(3_000, 6_000))
    }

    @Test fun `skeleton placeholders survive sort and filter`() {
        val rows = listOf(MainActivity.Row("Loading…", List(8) { MainActivity.Tile.Skeleton }))
        val shaped = FlixView.apply(rows, FlixView.SORT_AZ, filterOn = true, favs = emptySet(), vod = true)
        assertEquals(1, shaped.size)
        assertEquals(8, shaped.first().tiles.size)
        assertTrue(shaped.first().tiles.all { it is MainActivity.Tile.Skeleton })
    }

    @Test fun `skeleton rows earn no chips`() {
        val rows = listOf(MainActivity.Row("Loading…", List(8) { MainActivity.Tile.Skeleton }))
        assertTrue(FlixChips.chipsFor(3, rows, vod = true).isEmpty())
    }
}
