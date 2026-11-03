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
 * AK TV glossy-crimson palette: ink-black surfaces, deep wine depth, lacquered red accents and
 * bright-crimson cues. Names are stable so layouts, players and tests keep one source of truth.
 */
object Accent {
    val ABYSS = 0xFF07040A.toInt()
    val NIGHT = 0xFF0C0710.toInt()
    val SURFACE = 0xFF17101B.toInt()
    val SURFACE_HI = 0xFF241528.toInt()
    /** Primary glossy crimson used for focus fills, live badges and active controls. */
    val CRIMSON = 0xFFE11D2E.toInt()
    val CRIMSON_BRIGHT = 0xFFFF3448.toInt()
    val CRIMSON_DEEP = 0xFF8C0F1C.toInt()
    val WINE = 0xFF4A0812.toInt()
    /** Near-white with a warm cast; the specular highlight in gloss gradients. */
    val GLOSS_HI = 0xFFFFEBEE.toInt()
    val SIGNAL = CRIMSON_BRIGHT
    val ION = 0xFFFF5C6B.toInt()
    val INDIGO = 0xFFC2185B.toInt()
    val DEEP = 0xFF2A1520.toInt()
    val LIVE = CRIMSON_BRIGHT
    val GOLD = 0xFFE7B969.toInt()
    val TEXT = 0xFFFCF7F8.toInt()
    val DIM = 0xFFC4B4BA.toInt()
    val FAINT = 0xFF8E7781.toInt()

    /** Gloss stops for a lacquered surface: highlight -> body -> deep edge. */
    fun glossStops(): IntArray = intArrayOf(CRIMSON_BRIGHT, CRIMSON, CRIMSON_DEEP)

    /**
     * Makes any logo colour usable as a tint. The sampled hue is pulled most of the way to the brand
     * crimson so channel artwork still varies, but every surface reads as one crimson identity.
     */
    fun normalize(c: Int?): Int {
        if (c == null) return CRIMSON_BRIGHT
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        if (hsl[1] < 0.18f) return CRIMSON_BRIGHT
        val sampled = ColorUtils.HSLToColor(floatArrayOf(hsl[0], max(hsl[1], 0.65f), hsl[2].coerceIn(0.48f, 0.62f)))
        return ColorUtils.blendARGB(CRIMSON, sampled, 0.28f)
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
     * Hues stay inside a warm crimson band so a wall of tiles reads as one brand, not a rainbow.
     */
    fun fromName(name: String): Int {
        var h = 7
        for (ch in name.lowercase()) h = (h * 31 + ch.code) and 0x7fffffff
        // 0..1 spread across crimson -> rose -> warm wine, all within the red family.
        val spread = (h % 100) / 99f
        val hue = if (spread < 0.5f) 348f + spread * 24f else 342f - (spread - 0.5f) * 30f
        val lightness = 0.34f + ((h / 100) % 12) / 100f
        return ColorUtils.HSLToColor(floatArrayOf((hue + 360f) % 360f, 0.62f, lightness))
    }

    fun from(d: Drawable, cb: (Int) -> Unit) {
        val bmp = (d as? BitmapDrawable)?.bitmap
        if (bmp == null || bmp.config == Bitmap.Config.HARDWARE) { cb(CRIMSON_BRIGHT); return }
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
    fun focusBg(dp: Float, radiusDp: Float, rest: Int = Color.TRANSPARENT, active: Int = 0xFF213542.toInt()) =
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
