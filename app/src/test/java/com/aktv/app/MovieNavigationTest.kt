package com.aktv.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MovieNavigationTest {
    private fun title(series: Boolean) = Movies.Title(if (series) "tv213562" else "mv1288069", if (series) 213562 else 1288069,
        if (series) "series" else "movie", "Sample title", "", "", "", "", "", emptyList(), "")
    private fun episode(s: Int, e: Int) = Movies.Episode(s, e, "Episode $e", "", "")

    @Test fun `movie navigation passes a TMDB identity not a browser or stream URL`() {
        val intent = MoviePlayerActivity.playbackIntent(RuntimeEnvironment.getApplication(), title(false), 7, 3, listOf(episode(1, 1)))
        assertEquals(MoviePlayerActivity::class.java.name, intent.component!!.className)
        assertEquals(1288069, intent.getIntExtra("tmdb", 0))
        assertEquals("movie", intent.getStringExtra("type"))
        assertEquals(0, intent.getIntExtra("season", -1))
        assertEquals(0, intent.getIntExtra("episode", -1))
        assertEquals(0, intent.getIntArrayExtra("episodes")!!.size)
        assertNull(intent.getStringExtra("url")); assertNull(intent.data)
    }
    @Test fun `series navigation preserves selected and available episodes`() {
        val intent = MoviePlayerActivity.playbackIntent(RuntimeEnvironment.getApplication(), title(true), 2, 3,
            listOf(episode(1, 1), episode(2, 3), episode(2, 4)))
        assertEquals("tv", intent.getStringExtra("type"))
        assertEquals(213562, intent.getIntExtra("tmdb", 0))
        assertEquals(2, intent.getIntExtra("season", 0)); assertEquals(3, intent.getIntExtra("episode", 0))
        assertEquals(listOf(1 to 1, 2 to 3, 2 to 4), VodProgress.episodes(intent.getIntArrayExtra("episodes")))
    }
    @Test fun `episode IPC is bounded and never sends thumbnails signed URLs or whole metadata payloads`() {
        val intent = MoviePlayerActivity.playbackIntent(RuntimeEnvironment.getApplication(), title(true), 1, 1,
            (1..5000).map { episode(1, it) })
        assertEquals(4000, intent.getIntArrayExtra("episodes")!!.size)
        assertEquals(setOf("tmdb", "type", "season", "episode", "name", "episodes"), intent.extras!!.keySet())
    }
}
