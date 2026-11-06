package com.aktv.app

import android.os.Looper
import android.view.View
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
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
}
