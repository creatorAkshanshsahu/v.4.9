package com.aktv.app

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AkIntroTest {
    @Test fun `skipping reveals the home exactly once without a long entrance`() {
        val view = AkIntroView(RuntimeEnvironment.getApplication())
        var revealed = 0; var done = 0
        view.onReveal = { revealed++ }; view.onDone = { done++ }
        view.start(); view.skip(); view.skip()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, revealed); assertEquals(1, done)
    }
    @Test fun `a skip before the first animation frame is still safe`() {
        val view = AkIntroView(RuntimeEnvironment.getApplication())
        var done = 0; view.onDone = { done++ }
        view.skip(); view.start(); view.skip()
        assertEquals(1, done)
    }
    @Test fun `detaching an unfinished entrance cannot reveal a destroyed screen`() {
        val view = AkIntroView(RuntimeEnvironment.getApplication())
        var done = 0; view.onDone = { done++ }
        view.start()
        val beforeDetach = done // Reduced-motion environments may legitimately finish immediately.
        view.javaClass.getDeclaredMethod("onDetachedFromWindow").apply { isAccessible = true }.invoke(view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(beforeDetach, done)
    }
}
