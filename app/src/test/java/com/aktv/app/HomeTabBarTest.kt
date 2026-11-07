package com.aktv.app

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The top bar is the only way into JioTV, Movies and Series on a television. There is no touch screen
 * and no mouse: if D-pad focus cannot walk from one pill to the next, those sections are unreachable and
 * the app looks like it only has Live TV. This pins the bar's behaviour down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television")
class HomeTabBarTest {

    @Test
    fun `a remote walks the top bar one pill at a time`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val live = activity.findViewById<TextView>(R.id.segLive)
            val jio = activity.findViewById<TextView>(R.id.segJio)
            val movies = activity.findViewById<TextView>(R.id.segSites)
            val series = activity.findViewById<TextView>(R.id.segSeries)
            val search = activity.findViewById<TextView>(R.id.searchBtn)

            assertTrue("Live TV has to be able to take the remote", live.requestFocus())
            assertSame("the remote starts on Live TV", live, activity.currentFocus)

            // RIGHT used to be hijacked straight back down to the shelf, which stranded the bar.
            for (expected in listOf(jio, movies, series, search)) {
                press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
                assertSame("RIGHT has to move to the next pill", expected, activity.currentFocus)
            }
            for (expected in listOf(series, movies, jio)) {
                press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
                assertSame("LEFT has to move back along the bar", expected, activity.currentFocus)
            }

            // LEFT used to be swallowed whole, so nothing was ever reachable.
            press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
            assertSame("Live TV is the left end of the bar", live, activity.currentFocus)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `the bar lets go of the remote at its right end`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val search = activity.findViewById<TextView>(R.id.searchBtn)
            assertTrue(search.requestFocus())
            press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue("past the search button the bar gives focus back to the shelf",
                activity.currentFocus == null || !onBar(activity.currentFocus))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `OK on a focused pill opens that section`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val jio = activity.findViewById<TextView>(R.id.segJio)
            val movies = activity.findViewById<TextView>(R.id.segSites)
            assertTrue(jio.performClick())
            assertEquals("JioTV", title(activity))
            assertTrue(movies.performClick())
            assertEquals("Movies", title(activity))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun title(activity: MainActivity): String {
        val section = activity.findViewById<TextView>(R.id.sectionTitle)
        assertNotNull("the shelf has to say which section is open", section)
        return section.text.toString()
    }

    private fun onBar(view: View?): Boolean = view != null && (
        view.id == R.id.segLive || view.id == R.id.segJio || view.id == R.id.segSites ||
            view.id == R.id.segSeries || view.id == R.id.searchBtn)

    private fun press(activity: MainActivity, keyCode: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
