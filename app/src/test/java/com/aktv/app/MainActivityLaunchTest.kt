package com.aktv.app

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * The shelf has to open on a television. Nothing here touches the network on purpose: this is the guard
 * against an `onCreate` that throws, which on a TV looks like "the app closes by itself".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television")
class MainActivityLaunchTest {

    @Test
    fun `a television opens the shelf without throwing`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            assertNotNull("the shelf must exist after onCreate", activity.findViewById<View>(R.id.home))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `the first seconds of a television run do not throw either`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            // The entrance is skipped, focus is posted, the update check runs 3.5s in and the exit report
            // 15s in: all of them are posted work, so they need the looper to actually run.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
            assertNotNull(controller.get().findViewById<View>(R.id.home))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `a delayed callback that arrives after the screen is gone does not kill the process`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        // Queue the launch work (JioTV load, its 5s retry, prefetches), then tear the activity down the way
        // BACK or HOME does. Anything that fires afterwards used to reach a shut-down thread pool and throw.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        assertNotNull(activity)
    }

    @Test
    fun `a stack left by the last run is put on screen instead of the shelf`() {
        // On a television there is no log to read and a crash screen cannot be photographed in time. The
        // note the uncaught handler wrote last run has to reach the viewer, so this checks it does.
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "last-exit.txt").writeText(
            "2026-01-01 09:00:00\nCrash · main\nSTREAMR 5.4.4 (123)\n" +
                "java.lang.RuntimeException: boom\n\tat com.aktv.app.MainActivity.startUp(MainActivity.kt:1)\n\n")
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val texts = ArrayList<String>()
            collect(controller.get().window.decorView, texts)
            assertTrue("the failure screen must name itself", texts.any { it.contains("stopped on the last start") })
            assertTrue("the stack must be readable", texts.any { it.contains("RuntimeException: boom") })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `three starts that never reached the background are treated as a crash loop`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("aktv", Context.MODE_PRIVATE).edit().putInt("launch_streak", 4).apply()
        File(app.filesDir, "last-exit.txt").writeText("2026-01-01 09:00:00\nPrevious run ended without a clean stop\n")
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val texts = ArrayList<String>()
            collect(controller.get().window.decorView, texts)
            assertTrue("a repeated silent death must still be surfaced", texts.any { it.contains("stopped on the last start") })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `clicking StarShare and maxTV tabs with cached multi-thousand playlists never crashes or freezes`() {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "last-exit.txt").delete()
        app.getSharedPreferences("aktv", Context.MODE_PRIVATE).edit().clear().apply()

        // Seed 3,000 StarShare channels & 1,000 maxTV channels on disk so clicking StarShare & maxTV exercises real shelves.
        val starAccount = XtreamAccount.INBUILT
        val starChannels = (1..3_000).map { i ->
            val cat = when (i % 5) {
                0 -> "|IN| HINDI ENTERTAINMENT"
                1 -> "IN | SPORTS"
                2 -> "UK | MOVIES"
                3 -> "IN | TAMIL"
                else -> "BOLLYWOOD MOVIES"
            }
            val name = when (i % 5) {
                0 -> "Star Plus HD $i"
                1 -> "Star Sports 1 HD $i"
                2 -> "Sky Cinema $i"
                3 -> "Star Vijay $i"
                else -> "Sony Max HD $i"
            }
            XtreamLiveChannel(i.toString(), name, "", cat)
        }
        XtreamClient.saveLiveCache(File(app.filesDir, "xtream"), starAccount.cacheKey, starChannels)

        val maxAccount = StalkerAccount.DEFAULT
        val maxChannels = (1..1_000).map { i ->
            StalkerChannel(i.toString(), "Star Gold HD $i", "ffmpeg http://new.maxtv.be/ch/$i", genre = "10", logo = "/logos/$i.png")
        }
        StalkerProtocol.saveCache(File(app.filesDir, "portal"), maxAccount.cacheKey, maxChannels, mapOf("10" to "Hindi Movies"))

        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            // Wait briefly for background disk-cache warmup to populate slices and lay out RecyclerViews.
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
                measureAndLayout(activity)
                val liveTexts = ArrayList<String>()
                collect(activity.window.decorView, liveTexts)
                if (liveTexts.any { it.contains("maxTV · Playlist") }) break
                Thread.sleep(40)
            }

            // Live TV must expose maxTV as a playlist shelf!
            measureAndLayout(activity)
            val liveTexts = ArrayList<String>()
            collect(activity.window.decorView, liveTexts)
            assertTrue("Live TV must surface maxTV · Playlist shelf: $liveTexts", liveTexts.any { it.contains("maxTV · Playlist") })

            // Click StarShare tab and verify zero crashes & Hindi/Sports shelves render cleanly.
            activity.findViewById<View>(R.id.segStar).performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            measureAndLayout(activity)
            val starTexts = ArrayList<String>()
            collect(activity.window.decorView, starTexts)
            assertTrue("StarShare section title must appear: $starTexts", starTexts.any { it.contains("StarShare") })
            assertTrue("StarShare featured shelf must appear: $starTexts", starTexts.any { it.contains("StarShare · Featured") })
            assertTrue("No CrashLog report may be written when clicking StarShare", CrashLog.report(activity) == null)

            // Click maxTV tab and verify playlist shelves render cleanly.
            activity.findViewById<View>(R.id.segPortal).performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            measureAndLayout(activity)
            val maxTexts = ArrayList<String>()
            collect(activity.window.decorView, maxTexts)
            assertTrue("maxTV featured playlist shelf must appear: $maxTexts", maxTexts.any { it.contains("maxTV · Featured Playlist") })
            assertTrue("No CrashLog report may be written when clicking maxTV", CrashLog.report(activity) == null)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun measureAndLayout(activity: MainActivity) {
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 960, 1800)
    }

    private fun collect(view: View, out: MutableList<String>) {
        if (view is TextView) out.add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i), out)
    }
}
