package com.aktv.app

import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The STREAMR cyber-neon identity (screen-safari-studio) stays inside the cyan-to-magenta neon spectrum. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccentIdentityTest {

    private fun hsl(color: Int): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
    private fun hue(color: Int): Float = hsl(color)[0]
    private fun saturation(color: Int): Float = hsl(color)[1]
    private fun lightness(color: Int): Float = hsl(color)[2]

    private val arcStart = 180f
    private val arcEnd = 350f

    /** Cyber neon band: 180..350 degrees (electric cyan -> indigo/violet -> neon magenta). */
    private fun isBrandFamily(color: Int): Boolean {
        val h = hue(color)
        return h in arcStart..arcEnd
    }

    @Test fun `the brand primary is saturated cyber magenta and the accent is electric cyan`() {
        assertTrue(isBrandFamily(Accent.BRAND))
        assertTrue(isBrandFamily(Accent.BRAND_LIGHT))
        assertTrue(isBrandFamily(Accent.BRAND_DEEP))
        assertTrue(saturation(Accent.BRAND) > 0.8f)
        assertTrue(saturation(Accent.BRAND_LIGHT) > 0.8f)
    }

    @Test fun `LIVE and SIGNAL use the electric cyan accent so badges pop on dark violet glass`() {
        assertTrue(isBrandFamily(Accent.LIVE))
        assertEquals(Accent.SIGNAL, Accent.LIVE)
    }

    @Test fun `channel tints sampled from any hue stay in the cyber cyan-to-magenta family`() {
        val samples = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFF00FFFF.toInt(), 0xFFE7B969.toInt(), 0xFF8178F5.toInt())
        for (sample in samples) {
            val tint = Accent.normalize(sample)
            assertTrue("sample ${Integer.toHexString(sample)} produced ${Integer.toHexString(tint)}",
                isBrandFamily(tint))
        }
    }

    @Test fun `the hue projection covers the whole wheel without leaving the cyber arc`() {
        for (degree in 0 until 360) {
            val projected = Accent.projectHue(degree.toFloat())
            assertTrue("hue $degree projected to $projected",
                projected in Accent.ARC_START..(Accent.ARC_START + Accent.ARC_SPAN))
        }
    }

    @Test fun `greyscale logos and missing logos fall back to the brand magenta`() {
        assertEquals(Accent.BRAND, Accent.normalize(null))
        assertTrue(isBrandFamily(Accent.normalize(0xFF808080.toInt())))
    }

    @Test fun `nameless-channel tints are stable and inside the cyber band`() {
        val first = Accent.fromName("Sony YAY")
        assertEquals("the same name must always get the same tint", first, Accent.fromName("Sony YAY"))
        for (name in listOf("Sony YAY", "Star Sports", "Zee Cinema", "DD News", "MTV", "Cartoon Network")) {
            assertTrue("$name tinted outside the cyber family", isBrandFamily(Accent.fromName(name)))
        }
    }

    @Test fun `hero partners stay dark so the panel keeps depth`() {
        for (color in listOf(Accent.BRAND, Accent.BRAND_LIGHT, Accent.BRAND_DEEP)) {
            assertTrue("partner should stay dark for a gradient", lightness(Accent.partner(color)) <= 0.36f)
        }
    }

    @Test fun `the wordmark runs cyber magenta into electric cyan, matching screen-safari-studio`() {
        assertArrayEquals(intArrayOf(0xFFFF00D0.toInt(), 0xFF00ECF7.toInt()), Accent.WORDMARK_STOPS)
        assertEquals(0xFFFF00D0.toInt(), Accent.BRAND)
        assertEquals(0xFF00ECF7.toInt(), Accent.LIVE)
        assertEquals(0xFF060410.toInt(), Accent.ABYSS)
    }
}
