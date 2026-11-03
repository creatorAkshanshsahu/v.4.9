package com.aktv.app

import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The crimson identity must stay one family: no cyan regressions, no rainbow tile wall. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccentIdentityTest {

    private fun hue(color: Int): Float = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }[0]
    private fun saturation(color: Int): Float = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }[1]

    /** Red band: -25..35 degrees, i.e. crimson through warm rose, never green/blue. */
    private fun isCrimsonFamily(color: Int): Boolean {
        val h = hue(color)
        return h <= 35f || h >= 335f
    }

    @Test fun `the brand primary is a saturated crimson`() {
        assertTrue(isCrimsonFamily(Accent.CRIMSON))
        assertTrue(isCrimsonFamily(Accent.CRIMSON_BRIGHT))
        assertTrue(isCrimsonFamily(Accent.CRIMSON_DEEP))
        assertTrue(saturation(Accent.CRIMSON) > 0.6f)
    }

    @Test fun `channel tints sampled from any hue stay in the crimson family`() {
        // Judge red, green, blue, cyan and garlic against the harmoniser.
        val samples = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFF00FFFF.toInt(), 0xFFE7B969.toInt(), 0xFF8178F5.toInt())
        for (sample in samples) {
            val tint = Accent.normalize(sample)
            assertTrue("sample ${Integer.toHexString(sample)} produced ${Integer.toHexString(tint)}",
                isCrimsonFamily(tint))
        }
    }

    @Test fun `the hue projection covers the whole wheel without leaving the red arc`() {
        // Exhaustive: every degree must fold into 335..25 degrees, never through magenta or green.
        for (degree in 0 until 360) {
            val projected = Accent.projectHue(degree.toFloat())
            assertTrue("hue $degree projected to $projected",
                projected <= 35f || projected >= 335f)
            assertTrue("hue $degree produced an out-of-range angle", projected in 0f..360f)
        }
    }

    @Test fun `greyscale logos and missing logos fall back to the brand crimson`() {
        assertEquals(Accent.CRIMSON_BRIGHT, Accent.normalize(null))
        assertTrue(isCrimsonFamily(Accent.normalize(0xFF808080.toInt())))
    }

    @Test fun `nameless-channel tints are stable and inside the warm band`() {
        val first = Accent.fromName("Sony YAY")
        assertEquals("the same name must always get the same tint", first, Accent.fromName("Sony YAY"))
        for (name in listOf("Sony YAY", "Star Sports", "Zee Cinema", "DD News", "MTV", "Cartoon Network")) {
            assertTrue("$name tinted outside the crimson family", isCrimsonFamily(Accent.fromName(name)))
        }
    }

    @Test fun `hero partners stay dark so the panel keeps depth`() {
        for (color in listOf(Accent.CRIMSON, Accent.CRIMSON_BRIGHT, Accent.CRIMSON_DEEP)) {
            val partner = Accent.partner(color)
            assertTrue(isCrimsonFamily(partner))
            val lightness = FloatArray(3).also { ColorUtils.colorToHSL(partner, it) }[2]
            assertTrue("partner should stay dark for a gradient", lightness <= 0.36f)
        }
    }
}
