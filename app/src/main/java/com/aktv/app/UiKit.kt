package com.aktv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/** Edge-to-edge system-bar control for cinematic screens and full-screen playback. */
internal object ImmersiveUi {
    fun hide(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun show(window: Window) {
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }
}

/**
 * STREAMR palette, v3 (streamr-android-tv-design_v3.md): a near-black blue ground, one crimson brand that
 * carries the wordmark, the eyebrow labels and every tile accent, and one brighter red kept for LIVE and
 * progress. Names are stable so layouts, players and tests keep one source of truth.
 */
object Accent {
    val ABYSS = 0xFF0F1117.toInt()
    val NIGHT = 0xFF0A0C11.toInt()
    val SURFACE = 0xFF1B1E26.toInt()
    val SURFACE_HI = 0xFF23262F.toInt()
    /** Primary crimson: focus fills, live badges, active controls. */
    val CRIMSON = 0xFFC4161C.toInt()
    val CRIMSON_BRIGHT = 0xFFE5383B.toInt()
    val CRIMSON_DEEP = 0xFF8A0F14.toInt()
    val WINE = 0xFF2A1013.toInt()
    /** The specular highlight in gloss gradients. */
    val GLOSS_HI = 0xFFFFFFFF.toInt()
    val SIGNAL = CRIMSON_BRIGHT
    val ION = 0xFFF2626B.toInt()
    val INDIGO = 0xFF59616E.toInt()
    val DEEP = 0xFF1A1F28.toInt()
    val LIVE = CRIMSON_BRIGHT
    val GOLD = 0xFFE0A24B.toInt()
    val TEXT = 0xFFF5F5F5.toInt()
    val DIM = 0xFFA4A7B0.toInt()
    val FAINT = 0xFF6E717A.toInt()
    /** Brand crimson: wordmark, eyebrow labels, the active pill's tick and the profile dot. */
    val BRAND = 0xFFC4161C.toInt()
    val BRAND_LIGHT = 0xFFE0243A.toInt()
    val BRAND_DEEP = 0xFFA8101A.toInt()

    /** Gloss stops for a lacquered surface: highlight -> body -> deep edge. */
    fun glossStops(): IntArray = intArrayOf(BRAND_LIGHT, BRAND, BRAND_DEEP)

    /** The wordmark's two stops, left to right, exactly as the design file states them. */
    val WORDMARK_STOPS = intArrayOf(BRAND_DEEP, BRAND_LIGHT)

    /** The brand arc: crimson, 344..16 degrees across the wrap at 360. */
    const val ARC_START = 344f
    const val ARC_SPAN = 32f

    /**
     * Makes any logo colour usable as a tint. The sampled hue is folded onto the brand arc so channel
     * artwork still varies, but every surface reads as one crimson identity.
     */
    fun normalize(c: Int?): Int {
        if (c == null) return BRAND
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        if (hsl[1] < 0.18f) return BRAND
        val hue = projectHue(hsl[0])
        return ColorUtils.HSLToColor(floatArrayOf(hue, max(hsl[1], 0.58f), hsl[2].coerceIn(0.40f, 0.54f)))
    }

    /**
     * Fold any hue onto the crimson arc instead of blending toward it in RGB, which drags saturated blues
     * through magenta. Greys are handled by the caller before this runs.
     */
    fun projectHue(hue: Float): Float {
        val h = ((hue % 360f) + 360f) % 360f
        // Position on the wheel measured from the start of the arc.
        val within = ((h - ARC_START) % 360f + 360f) % 360f
        if (within <= ARC_SPAN) return h
        // The rest of the wheel folds back into the arc: 180 degrees away lands at its middle.
        val t = (within - ARC_SPAN) / (360f - ARC_SPAN)
        val folded = if (t <= 0.5f) t * 2f else (1f - t) * 2f
        return ((ARC_START + folded * ARC_SPAN) % 360f + 360f) % 360f
    }

    /** A darker neighbouring crimson so the hero panel has depth instead of one flat colour. */
    fun partner(c: Int): Int {
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        hsl[0] = if (hsl[0] >= 180f) (hsl[0] - 14f + 360f) % 360f else (hsl[0] + 14f) % 360f
        hsl[1] = hsl[1].coerceAtLeast(0.45f)
        hsl[2] = 0.30f
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * Stable tint for channels with no logo to sample (V2 / 24-7): same name, same colour.
     * Hues stay inside the crimson band so a wall of tiles reads as one brand, not a rainbow.
     */
    fun fromName(name: String): Int {
        var h = 7
        for (ch in name.lowercase()) h = (h * 31 + ch.code) and 0x7fffffff
        val spread = (h % 100) / 99f
        val hue = ((ARC_START + spread * ARC_SPAN) % 360f + 360f) % 360f
        val lightness = 0.30f + ((h / 100) % 12) / 100f
        return ColorUtils.HSLToColor(floatArrayOf(hue, 0.62f, lightness))
    }

    fun from(d: Drawable, cb: (Int) -> Unit) {
        val bmp = (d as? BitmapDrawable)?.bitmap
        if (bmp == null || bmp.config == Bitmap.Config.HARDWARE) { cb(BRAND); return }
        Palette.from(bmp).maximumColorCount(16).generate { p ->
            val sw = p?.vibrantSwatch ?: p?.lightVibrantSwatch ?: p?.darkVibrantSwatch ?: p?.dominantSwatch
            cb(normalize(sw?.rgb))
        }
    }
}

/** Flat, cheap surfaces: plain colour fills, no shaders, no animators, no shadows. */
object Ui {
    fun mix(a: Int, b: Int, f: Float) = ColorUtils.blendARGB(a, b, f)

    /** Rounded flat fill. radiusDp = 100 (default) gives a pill. */
    fun pill(dp: Float, color: Int, radiusDp: Float = 100f) =
        GradientDrawable().apply { cornerRadius = radiusDp * dp; setColor(color) }

    /**
     * List-row background driven by the view's own state: white when focused, soft grey when activated
     * (the row that is "playing" / "open"), [rest] otherwise. The framework switches it; no code runs per focus change.
     */
    fun focusBg(dp: Float, radiusDp: Float, rest: Int = Color.TRANSPARENT, active: Int = 0x33C4161C) =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), pill(dp, Accent.TEXT, radiusDp))
            addState(intArrayOf(android.R.attr.state_activated), pill(dp, active, radiusDp))
            addState(intArrayOf(), pill(dp, rest, radiusDp))
        }
}

/** Keeps the focused item pinned to the start edge, like Google TV / Netflix rows. */
class SnapLayoutManager(c: Context, orientation: Int) : LinearLayoutManager(c, orientation, false) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect,
                                               immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        val lp = child.layoutParams as ViewGroup.MarginLayoutParams
        val d = if (orientation == RecyclerView.HORIZONTAL) child.left - lp.leftMargin - parent.paddingLeft
                else child.top - lp.topMargin - parent.paddingTop
        if (d == 0) return false
        if (orientation == RecyclerView.HORIZONTAL) { if (immediate) parent.scrollBy(d, 0) else parent.smoothScrollBy(d, 0) }
        else { if (immediate) parent.scrollBy(0, d) else parent.smoothScrollBy(0, d) }
        return true
    }
}
