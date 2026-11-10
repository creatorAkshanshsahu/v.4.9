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

    private fun hsl(color: Int): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
    private fun hue(color: Int): Float = hsl(color)[0]
    private fun saturation(color: Int): Float = hsl(color)[1]
    private fun lightness(color: Int): Float = hsl(color)[2]

    private val arcStart = 340f
    private val arcEnd = 20f

    /** Crimson band: 340..20 degrees, across the wrap at 360. Never green, never blue, never amber. */
    private fun isBrandFamily(color: Int): Boolean {
        val h = hue(color)
        return h >= arcStart || h <= arcEnd
    }

    @Test fun `the brand signal is a saturated crimson`() {
        assertTrue(isBrandFamily(Accent.BRAND))
        assertTrue(isBrandFamily(Accent.BRAND_LIGHT))
        assertTrue(isBrandFamily(Accent.BRAND_DEEP))
        assertTrue(saturation(Accent.BRAND) > 0.6f)
    }

    @Test fun `LIVE is a brighter red than the brand, so it still reads on a crimson wall`() {
        assertTrue(isBrandFamily(Accent.LIVE))
        assertTrue("LIVE must out-shine the brand fill", lightness(Accent.LIVE) > lightness(Accent.BRAND))
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

    @Test fun `the hue projection covers the whole wheel without leaving the crimson arc`() {
        for (degree in 0 until 360) {
            val projected = Accent.projectHue(degree.toFloat())
            assertTrue("hue $degree projected to $projected",
                projected >= Accent.ARC_START || projected <= Accent.ARC_START + Accent.ARC_SPAN - 360f)
        }
    }

    @Test fun `greyscale logos and missing logos fall back to the brand crimson`() {
        assertEquals(Accent.BRAND, Accent.normalize(null))
        assertTrue(isBrandFamily(Accent.normalize(0xFF808080.toInt())))
    }

    @Test fun `nameless-channel tints are stable and inside the crimson band`() {
        val first = Accent.fromName("Sony YAY")
        assertEquals("the same name must always get the same tint", first, Accent.fromName("Sony YAY"))
        for (name in listOf("Sony YAY", "Star Sports", "Zee Cinema", "DD News", "MTV", "Cartoon Network")) {
            assertTrue("$name tinted outside the brand family", isBrandFamily(Accent.fromName(name)))
        }
    }

    @Test fun `hero partners stay dark so the panel keeps depth`() {
        for (color in listOf(Accent.BRAND, Accent.BRAND_LIGHT, Accent.BRAND_DEEP)) {
            assertTrue("partner should stay dark for a gradient", lightness(Accent.partner(color)) <= 0.36f)
        }
    }

    @Test fun `the wordmark runs brand deep into brand light, as the design file states`() {
        assertArrayEquals(intArrayOf(0xFFA8101A.toInt(), 0xFFE0243A.toInt()), Accent.WORDMARK_STOPS)
        assertEquals(0xFFC4161C.toInt(), Accent.BRAND)
        assertEquals(0xFFE5383B.toInt(), Accent.LIVE)
        assertEquals(0xFF0F1117.toInt(), Accent.ABYSS)
    }
}
