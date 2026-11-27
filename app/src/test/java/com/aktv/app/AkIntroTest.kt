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

    @Test fun `entrance sting is a non-silent PCM wav with usable headroom`() {
        val bytes = RuntimeEnvironment.getApplication().resources.openRawResource(R.raw.aktv_intro_sting).use { it.readBytes() }
        assertTrue(bytes.size > 44)
        assertEquals('R'.code.toByte(), bytes[0]); assertEquals('I'.code.toByte(), bytes[1])
        var peak = 0
        var i = 44
        while (i + 1 < bytes.size) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
            peak = maxOf(peak, kotlin.math.abs(sample)); i += 2
        }
        assertTrue("entrance audio should not be effectively silent", peak >= 18_000)
        assertTrue("leave headroom to avoid clipping", peak < 32_000)
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
        AkIntroView::class.java.getDeclaredMethod("onDetachedFromWindow").apply { isAccessible = true }.invoke(view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals(beforeDetach, done)
    }

    @Test fun `the boot sequence credits the operator and types every line before the reveal`() {
        assertTrue("the entrance must keep the creator's name", CyberBoot.LINES.any { "AKSHANSH SAHU" in it })
        assertTrue(CyberBoot.LINES.first().contains("STREAMR"))
        CyberBoot.LINES.indices.forEach { i ->
            assertEquals("line $i hidden at the start", 0, CyberBoot.typed(i, 0f))
            // The home is revealed at 3.6 s of 4.4 s; every line has finished typing by then.
            assertEquals("line $i fully typed before the reveal", CyberBoot.LINES[i].length, CyberBoot.typed(i, 3_600f / 4_400f))
        }
        assertEquals(0, CyberBoot.typed(99, 0.5f))
    }

    @Test fun `entrance plays on cold starts and skips on warm returns`() {
        assertTrue(IntroPolicy.shouldPlay(0, 1_000_000))
        assertTrue(IntroPolicy.shouldPlay(1_000_000, 1_000_000 + IntroPolicy.WARM_WINDOW_MS))
        assertTrue(IntroPolicy.shouldPlay(1_000_000, 1_000_000 + IntroPolicy.WARM_WINDOW_MS + 1))
        assertFalse(IntroPolicy.shouldPlay(1_000_000, 1_000_000 + IntroPolicy.WARM_WINDOW_MS - 1))
        assertFalse(IntroPolicy.shouldPlay(1_000_000, 1_000_001))
    }
}
