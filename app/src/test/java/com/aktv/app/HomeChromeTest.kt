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
            root.findViewById<View>(R.id.status).visibility = View.GONE // loaded catalogue, as in updateStatus()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            println("UI_GEOMETRY ${width}x$height movies=$movies rows=${root.findViewById<View>(R.id.rows).height} hero=${root.findViewById<View>(R.id.heroArea).height} titleLines=${root.findViewById<TextView>(R.id.heroTitle).maxLines}")
            test(root)
        } finally { controller.destroy() }
    }
    @Test @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    fun `TV home matches the site bar and its full-bleed hero`() = screen(960, 540) { root ->
        val nav = root.findViewById<View>(R.id.navRail)
        val content = root.findViewById<View>(R.id.content)
        assertEquals(View.VISIBLE, nav.visibility)
        assertEquals("the bar spans the screen", 0, nav.left)
        assertEquals(root.width, nav.right)
        assertEquals("the bar sits on the top edge", 0, nav.top)
        assertTrue("the bar is the site's 88dp strip", nav.height in 80..96)
        assertTrue("content begins at the top edge too, under the bar", content.top <= nav.bottom)
        assertEquals("mark and wordmark both belong in the bar", View.VISIBLE, root.findViewById<View>(R.id.brandMark).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.railTagline).visibility)
        assertEquals("the wordmark is the only brand in the bar", "STREAMR",
            root.findViewById<TextView>(R.id.akText).text.toString())
        val media = root.findViewById<View>(R.id.heroMedia)
        assertEquals("the hero bleeds to the left edge", 0, media.left)
        assertEquals("the hero bleeds to the right edge", root.width, media.right)
        assertEquals("the hero runs under the bar, like the site's fixed header", 0, media.top)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.navProfile).visibility)
        val search = root.findViewById<TextView>(R.id.searchBtn)
        assertEquals("search is a round glyph button", search.width, search.height)
        assertEquals("", search.text.toString())
        assertEquals("the profile chip is round", 38, root.findViewById<View>(R.id.profileAvatar).height)
        for (id in listOf(R.id.segLive, R.id.segJio, R.id.segPortal, R.id.segSites, R.id.segSeries, R.id.searchBtn)) {
            val view = root.findViewById<TextView>(id)
            assertTrue(view.isFocusable); assertTrue(view.isClickable)
            assertEquals("$id must lead down into the rows", R.id.rows, view.nextFocusDownId)
        }
    }
    @Test @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun `short landscape phone keeps horizontal section pills and the content shelf on screen`() = screen(800, 360) { root ->
        assertEquals(View.GONE, root.findViewById<View>(R.id.navRail).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.phoneNavScroll).visibility)
        val search = root.findViewById<View>(R.id.phoneSearch)
        assertTrue(search.top >= 0); assertTrue(search.bottom <= 360)
        assertTrue(root.findViewById<View>(R.id.rows).height >= 100)
        assertTrue(root.findViewById<View>(R.id.heroTitle).width > 220)
        val media = root.findViewById<View>(R.id.heroMedia)
        assertEquals("the hero bleeds on a phone too", 0, media.left)
        assertEquals(800, media.right)
    }
    @Test @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun `movie mode leaves room for poster shelves on compact screens`() = screen(800, 360, movies = true) { root ->
        assertTrue("Poster shelf height: ${root.findViewById<View>(R.id.rows).height}", root.findViewById<View>(R.id.rows).height >= 165)
        assertEquals(1, root.findViewById<TextView>(R.id.heroTitle).maxLines)
    }
    @Test @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    fun `TV movie hero leaves the complete first poster row and labels visible`() = screen(960, 540, movies = true) { root ->
        // A 2:3 poster at the rail's width, plus the label above it and the rail's own padding.
        // The rail peeks on a fixed screen, but the hero's own copy must never run into the bar.
        val hero = root.findViewById<View>(R.id.heroArea).height
        val copy = root.findViewById<View>(R.id.heroText)
        assertTrue("Hero copy ${copy.top}dp must clear the ${root.findViewById<View>(R.id.navRail).height}dp bar",
            copy.top >= root.findViewById<View>(R.id.navRail).height)
        assertTrue(root.findViewById<TextView>(R.id.heroTitle).maxLines in 1..2)
    }

}
