package com.aktv.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.ui.PlayerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Inflate only (create, not start): no resolver network calls, decoders or playback claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MoviePlayerUiTest {
    private fun intent() = Intent(RuntimeEnvironment.getApplication(), MoviePlayerActivity::class.java)
        .putExtra("tmdb", 1288069).putExtra("type", "movie").putExtra("name", "Sample film")
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun `movie screen inflates native player and focusable touch and remote controls without Gecko`() {
        val controller = Robolectric.buildActivity(MoviePlayerActivity::class.java, intent()).create()
        try {
            val activity = controller.get()
            val views = views(activity.findViewById(android.R.id.content))
            assertEquals(1, views.count { it is PlayerView })
            assertFalse(views.any { it.javaClass.name.contains("gecko", true) })
            for (label in listOf("Back", "Sources", "Audio / Subtitles", "Retry", "Details")) {
                assertTrue(label, views.filterIsInstance<TextView>().any { it.text.toString() == label && it.isFocusable && it.isClickable })
            }
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "Sample film" })
            assertNull(views.filterIsInstance<PlayerView>().single().player)
        } finally { controller.destroy() }
    }
    @Test fun `native saved position survives screen recreation without persisting a resolved URL`() {
        val context = RuntimeEnvironment.getApplication<android.app.Application>()
        val prefs = context.getSharedPreferences("aktv", 0)
        prefs.edit().putLong("vod_movie_1288069_0_0", 120_000).putLong("vod_movie_1288069_0_0_duration", 600_000).commit()
        val controller = Robolectric.buildActivity(MoviePlayerActivity::class.java, intent()).create()
        try {
            val state = Bundle()
            controller.saveInstanceState(state)
            assertEquals(120_000L, state.getLong("position"))
            assertFalse(state.containsKey("url"))
            controller.get().finish()
            assertTrue(controller.get().isFinishing)
        } finally { controller.destroy(); prefs.edit().clear().commit() }
    }
    @Test fun `invalid identity closes before opening a player or a source service`() {
        val controller = Robolectric.buildActivity(MoviePlayerActivity::class.java, intent().putExtra("tmdb", 0)).create()
        try { assertTrue(controller.get().isFinishing) } finally { controller.destroy() }
    }
}
