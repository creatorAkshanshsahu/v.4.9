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
    fun `TV home matches the reference bar and its two hero cards`() = screen(960, 540) { root ->
        val box = root.findViewById<View>(R.id.flixContainer)
        assertEquals(View.VISIBLE, box.visibility)
        assertTrue("the container floats inside the screen", box.left in 10..30 && box.top in 4..20)
        val nav = root.findViewById<View>(R.id.navRail)
        assertEquals(View.VISIBLE, nav.visibility)
        assertTrue("the bar sits at the top of the container", nav.top >= box.top && nav.top <= box.top + 24)
        assertTrue("the bar is a 76dp strip", nav.height in 68..84)
        assertEquals("the wordmark is the only brand in the bar", "STREAMR",
            root.findViewById<TextView>(R.id.akText).text.toString())
        assertEquals(View.GONE, root.findViewById<View>(R.id.brandMark).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.railTagline).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.navProfile).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.flixBellBox).visibility)
        val search = root.findViewById<TextView>(R.id.searchBtn)
        assertEquals("search lives at the end of the tab pill", R.id.navPills, (search.parent as View).id)
        assertEquals("search is a square glyph button", search.width, search.height)
        assertEquals("", search.text.toString())
        val left = root.findViewById<View>(R.id.heroMedia)
        val right = root.findViewById<View>(R.id.heroCard2)
        assertEquals(View.VISIBLE, left.visibility)
        assertEquals(View.VISIBLE, right.visibility)
        assertTrue("the feature is narrow left, the next one wide right", left.width in 1 until right.width)
        assertTrue("a gap sits between the cards", left.right < right.left)
        assertEquals("the section header is on screen", View.VISIBLE, root.findViewById<View>(R.id.sectionTitle).visibility)
        assertEquals("the chip strip is on screen", View.VISIBLE, root.findViewById<View>(R.id.flixChipScroll).visibility)
        assertTrue("the first shelf peeks below the header", root.findViewById<View>(R.id.rows).height >= 100)
        for (id in listOf(R.id.segLive, R.id.segJio, R.id.segPortal, R.id.segFusion, R.id.segSites, R.id.segSeries, R.id.segOg, R.id.segStar)) {
            val view = root.findViewById<TextView>(id)
            assertTrue(view.isFocusable); assertTrue(view.isClickable)
            assertEquals("$id must lead down to the feature card", R.id.heroMedia, view.nextFocusDownId)
        }
        assertTrue(left.isFocusable); assertTrue(left.isClickable)
        assertTrue(right.isFocusable); assertTrue(right.isClickable)
        assertEquals("OG", root.findViewById<TextView>(R.id.segOg).text.toString())
        assertEquals("OG", root.findViewById<TextView>(R.id.phoneOg).text.toString())
        assertEquals("StarShare", root.findViewById<TextView>(R.id.segStar).text.toString())
        assertEquals("StarShare", root.findViewById<TextView>(R.id.phoneStar).text.toString())
        assertNull("TG TV section pill must be removed", root.findViewById<View?>(root.resources.getIdentifier("segTag", "id", root.context.packageName)))
        assertNull("TG TV phone pill must be removed", root.findViewById<View?>(root.resources.getIdentifier("phoneTag", "id", root.context.packageName)))
    }
    @Test @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun `short landscape phone keeps horizontal section pills and one hero card`() = screen(800, 360) { root ->
        assertEquals(View.GONE, root.findViewById<View>(R.id.navRail).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.phoneNavScroll).visibility)
        val phoneOg = root.findViewById<TextView>(R.id.phoneOg)
        assertEquals(View.VISIBLE, phoneOg.visibility)
        assertEquals("OG", phoneOg.text.toString())
        assertTrue("OG pill must fit on-screen without scrolling on mobile (right=${phoneOg.right})", phoneOg.right in 1..800)
        val phoneStar = root.findViewById<TextView>(R.id.phoneStar)
        assertEquals(View.VISIBLE, phoneStar.visibility)
        assertEquals("StarShare", phoneStar.text.toString())
        assertTrue("StarShare pill must fit on-screen without scrolling on mobile (right=${phoneStar.right})", phoneStar.right in 1..800)
        val search = root.findViewById<View>(R.id.phoneSearch)
        assertTrue(search.top >= 0); assertTrue(search.bottom <= 360)
        assertEquals("phones keep one hero so the shelves stay on screen", View.GONE, root.findViewById<View>(R.id.heroCard2).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.heroMedia).visibility)
        assertTrue(root.findViewById<View>(R.id.rows).height >= 50)
        assertTrue(root.findViewById<View>(R.id.heroTitle).width > 220)
    }
    @Test @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun `movie mode leaves room for poster shelves on compact screens`() = screen(800, 360, movies = true) { root ->
        assertTrue("Poster shelf height: ${root.findViewById<View>(R.id.rows).height}", root.findViewById<View>(R.id.rows).height >= 50)
        assertEquals(2, root.findViewById<TextView>(R.id.heroTitle).maxLines)
    }
    @Test @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    fun `TV movie hero keeps the bar, the cards and the header clear of each other`() = screen(960, 540, movies = true) { root ->
        val nav = root.findViewById<View>(R.id.navRail)
        val hero = root.findViewById<View>(R.id.heroArea)
        val header = root.findViewById<View>(R.id.sectionHeader)
        assertTrue("the cards clear the bar", hero.top >= nav.bottom)
        assertTrue("the header clears the cards", header.top >= hero.bottom)
        assertTrue(root.findViewById<TextView>(R.id.heroTitle).maxLines in 1..2)
    }

}
