package com.aktv.app

import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The STREAMR identity must stay one family: no cyan regressions, no rainbow tile wall. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccentIdentityTest {

    private fun hue(color: Int): Float = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }[0]
    private fun saturation(color: Int): Float = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }[1]

    /** Amber band: 18..50 degrees. Warm gold through deep orange, never green or blue. */
    private fun isBrandFamily(color: Int): Boolean = hue(color) in 18f..50f

    @Test fun `the brand signal is a saturated amber`() {
        assertTrue(isBrandFamily(Accent.AMBER))
        assertTrue(isBrandFamily(Accent.AMBER_LIGHT))
        assertTrue(isBrandFamily(Accent.AMBER_DEEP))
        assertTrue(saturation(Accent.AMBER) > 0.5f)
    }

    @Test fun `the single red is reserved for LIVE and progress`() {
        // One red, and it is not in the amber band: that is what keeps LIVE legible on the amber wall.
        assertFalse(isBrandFamily(Accent.LIVE))
        assertTrue(saturation(Accent.LIVE) > 0.6f)
    }

    @Test fun `channel tints sampled from any hue stay in the brand family`() {
        val samples = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFF00FFFF.toInt(), 0xFFE7B969.toInt(), 0xFF8178F5.toInt())
        for (sample in samples) {
            val tint = Accent.normalize(sample)
            assertTrue("sample ${Integer.toHexString(sample)} produced ${Integer.toHexString(tint)}",
                isBrandFamily(tint))
        }
    }

    @Test fun `the hue projection covers the whole wheel without leaving the amber arc`() {
        for (degree in 0 until 360) {
            val projected = Accent.projectHue(degree.toFloat())
            assertTrue("hue $degree projected to $projected", projected in 24f..46f)
        }
    }

    @Test fun `greyscale logos and missing logos fall back to the brand amber`() {
        assertEquals(Accent.AMBER, Accent.normalize(null))
        assertTrue(isBrandFamily(Accent.normalize(0xFF808080.toInt())))
    }

    @Test fun `nameless-channel tints are stable and inside the warm band`() {
        val first = Accent.fromName("Sony YAY")
        assertEquals("the same name must always get the same tint", first, Accent.fromName("Sony YAY"))
        for (name in listOf("Sony YAY", "Star Sports", "Zee Cinema", "DD News", "MTV", "Cartoon Network")) {
            assertTrue("$name tinted outside the brand family", isBrandFamily(Accent.fromName(name)))
        }
    }

    @Test fun `hero partners stay dark so the panel keeps depth`() {
        for (color in listOf(Accent.AMBER, Accent.AMBER_LIGHT, Accent.AMBER_DEEP)) {
            val partner = Accent.partner(color)
            val lightness = FloatArray(3).also { ColorUtils.colorToHSL(partner, it) }[2]
            assertTrue("partner should stay dark for a gradient", lightness <= 0.36f)
        }
    }
}
