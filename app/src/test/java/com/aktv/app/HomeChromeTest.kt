package com.aktv.app

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeChromeTest {
    private fun screen(width: Int, height: Int, movies: Boolean = false, test: (View) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = controller.get(); activity.setTheme(R.style.Theme_AKTV)
            val root = LayoutInflater.from(activity).inflate(R.layout.home_content, null)
            activity.setContentView(root)
            HomeChrome.apply(root, width, height, 1f, movies)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            test(root)
        } finally { controller.destroy() }
    }
    @Test @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    fun `TV home has separated navigation content and real focus targets`() = screen(960, 540) { root ->
        val rail = root.findViewById<View>(R.id.navRail)
        val content = root.findViewById<View>(R.id.content)
        assertTrue(content.left >= rail.right)
        val media = root.findViewById<View>(R.id.heroMedia)
        assertTrue(media.left >= rail.right); assertTrue(media.right <= root.width)
        for (id in listOf(R.id.segLive, R.id.segV2, R.id.segSites, R.id.segSeries, R.id.searchBtn)) {
            val view = root.findViewById<TextView>(id)
            assertTrue(view.isFocusable); assertTrue(view.isClickable)
            assertEquals(R.id.rows, view.nextFocusRightId)
        }
    }
    @Test @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun `short landscape phone keeps search and the content shelf on screen`() = screen(800, 360) { root ->
        val search = root.findViewById<View>(R.id.searchBtn)
        assertTrue(search.top >= 0); assertTrue(search.bottom <= 360)
        assertTrue(root.findViewById<View>(R.id.rows).height >= 100)
        assertTrue(root.findViewById<View>(R.id.heroTitle).width > 220)
    }
    @Test @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun `movie mode leaves room for poster shelves on compact screens`() = screen(800, 360, movies = true) { root ->
        assertTrue(root.findViewById<View>(R.id.rows).height >= 165)
        assertEquals(1, root.findViewById<TextView>(R.id.heroTitle).maxLines)
    }
}
