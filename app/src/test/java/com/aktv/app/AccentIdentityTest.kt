package com.aktv.app

import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Aurora Glass identity must stay one family: rose -> orchid -> iris, never green/cyan/yellow tiles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccentIdentityTest {

    private fun hsl(color: Int) = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
    private fun hue(color: Int) = hsl(color)[0]

    /** Aurora band, with a little tolerance for HSL round-trips: 230..360 degrees. */
    private fun isAuroraFamily(color: Int): Boolean { val h = hue(color); return h >= Accent.ARC_START - 6f || h <= 2f }

    @Test fun `the brand aurora is three saturated stops in order`() {
        val stops = Accent.aurora()
        assertArrayEquals(intArrayOf(Accent.ROSE, Accent.ORCHID, Accent.IRIS), stops)
        for (c in stops) { assertTrue(isAuroraFamily(c)); assertTrue(hsl(c)[1] > 0.6f) }
        assertTrue("rose is the warm end", hue(Accent.ROSE) > hue(Accent.ORCHID))
        assertTrue("iris is the cool end", hue(Accent.ORCHID) > hue(Accent.IRIS))
    }

    @Test fun `legacy token names map onto the aurora`() {
        assertEquals(Accent.ROSE, Accent.SIGNAL); assertEquals(Accent.ORCHID, Accent.ION)
        assertEquals(Accent.IRIS, Accent.INDIGO); assertEquals(Accent.INK, Accent.ABYSS)
    }

    @Test fun `channel tints sampled from any hue stay in the aurora family`() {
        val samples = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFF00FFFF.toInt(), 0xFFE7B969.toInt(), 0xFF8178F5.toInt(), 0xFFFFFF00.toInt())
        for (sample in samples) {
            val tint = Accent.normalize(sample)
            assertTrue("sample ${Integer.toHexString(sample)} produced ${Integer.toHexString(tint)}", isAuroraFamily(tint))
        }
    }

    @Test fun `the hue projection covers the whole wheel without leaving the aurora arc`() {
        for (degree in 0 until 360) {
            val projected = Accent.projectHue(degree.toFloat())
            assertTrue("hue $degree projected to $projected", projected in Accent.ARC_START..Accent.ARC_END)
        }
        assertTrue("warm reds land on the rose end", Accent.projectHue(10f) > 320f)
        assertTrue("greens land on the iris end", Accent.projectHue(120f) < 270f)
    }

    @Test fun `greyscale logos and missing logos fall back to the brand rose`() {
        assertEquals(Accent.ROSE, Accent.normalize(null))
        assertEquals(Accent.ROSE, Accent.normalize(0xFF808080.toInt()))
    }

    @Test fun `nameless-channel tints are stable and inside the aurora`() {
        assertEquals("the same name must always get the same tint", Accent.fromName("Sony YAY"), Accent.fromName("Sony YAY"))
        val tints = listOf("Sony YAY", "Star Sports", "Zee Cinema", "DD News", "MTV", "Cartoon Network").map(Accent::fromName)
        tints.forEach { assertTrue(Integer.toHexString(it), isAuroraFamily(it)) }
        assertTrue("a wall of tiles should shimmer, not be one colour", tints.toSet().size > 3)
    }

    @Test fun `hero partners stay dark so tinted glass keeps depth`() {
        for (color in Accent.aurora()) {
            val partner = Accent.partner(color)
            assertTrue(isAuroraFamily(partner))
            assertTrue("partner should stay dark for a gradient", hsl(partner)[2] <= 0.36f)
        }
    }
}
