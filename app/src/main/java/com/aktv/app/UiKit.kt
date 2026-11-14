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
 * STREAMR cyber-neon palette (from `screen-safari-studio/src/styles.css`):
 * deep cyber violet-abyss ground (`#060410`), electric magenta primary (`#FF00D0`),
 * and electric cyan accent/focus/live (`#00ECF7`), with a magenta-to-cyan wordmark gradient.
 */
object Accent {
    val ABYSS = 0xFF060410.toInt()
    val NIGHT = 0xFF020107.toInt()
    val SURFACE = 0xFF100A23.toInt()
    val SURFACE_HI = 0xFF1B1630.toInt()
    /** Primary cyber magenta (`--primary` / `--brand-from`): active pills, Play CTA, seek thumb. */
    val BRAND = 0xFFFF00D0.toInt()
    /** Electric cyber cyan (`--accent` / `--brand-to` / `--live` / `--focus`): eyebrows, focus rings, LIVE, profile. */
    val BRAND_LIGHT = 0xFF00ECF7.toInt()
    val BRAND_DEEP = 0xFFBB009B.toInt()
    val CRIMSON = BRAND
    val CRIMSON_BRIGHT = BRAND_LIGHT
    val CRIMSON_DEEP = BRAND_DEEP
    val WINE = 0xFF1B0928.toInt()
    /** Specular highlight in gloss gradients. */
    val GLOSS_HI = 0xFFFFFFFF.toInt()
    val SIGNAL = BRAND_LIGHT
    val ION = 0xFF5BF3FF.toInt()
    val INDIGO = 0xFF5E6C92.toInt()
    val DEEP = 0xFF120D28.toInt()
    val LIVE = BRAND_LIGHT
    val GOLD = 0xFFFFB84D.toInt()
    val TEXT = 0xFFF5F5F5.toInt()
    val DIM = 0xFF98AFD5.toInt()
    val FAINT = 0xFF687AA0.toInt()

    /** Gloss stops: cyan highlight -> magenta body -> deep violet-magenta edge. */
    fun glossStops(): IntArray = intArrayOf(BRAND_LIGHT, BRAND, BRAND_DEEP)

    /** The wordmark's two stops, left to right (`--gradient-brand`: magenta -> cyan). */
    val WORDMARK_STOPS = intArrayOf(BRAND, BRAND_LIGHT)

    /** Cyber neon arc: 190..345 degrees (electric cyan -> indigo/violet -> neon magenta). */
    const val ARC_START = 190f
    const val ARC_SPAN = 155f

    /**
     * Makes any logo colour usable as a cyber card tint. Sampled hues are folded onto the
     * cyan-to-magenta arc (`190..345`) so channel artwork varies without warm brown/olive clashes.
     */
    fun normalize(c: Int?): Int {
        if (c == null) return BRAND
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        if (hsl[1] < 0.18f) return BRAND
        val hue = projectHue(hsl[0])
        return ColorUtils.HSLToColor(floatArrayOf(hue, max(hsl[1], 0.65f), hsl[2].coerceIn(0.36f, 0.52f)))
    }

    /** Fold any hue onto the cyber arc (`190..345` degrees). */
    fun projectHue(hue: Float): Float {
        val h = ((hue % 360f) + 360f) % 360f
        val within = ((h - ARC_START) % 360f + 360f) % 360f
        if (within <= ARC_SPAN) return h
        val t = (within - ARC_SPAN) / (360f - ARC_SPAN)
        val folded = if (t <= 0.5f) t * 2f else (1f - t) * 2f
        return ((ARC_START + folded * ARC_SPAN) % 360f + 360f) % 360f
    }

    /** Darker neighbouring cyber violet/blue so hero & card panels keep depth. */
    fun partner(c: Int): Int {
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        hsl[0] = projectHue(hsl[0] - 24f)
        hsl[1] = hsl[1].coerceAtLeast(0.50f)
        hsl[2] = 0.24f
        return ColorUtils.HSLToColor(hsl)
    }

    /** Stable cyber tint for logo-less channels: same name -> same colour along the cyan-magenta arc. */
    fun fromName(name: String): Int {
        var h = 7
        for (ch in name.lowercase()) h = (h * 31 + ch.code) and 0x7fffffff
        val spread = (h % 100) / 99f
        val hue = ((ARC_START + spread * ARC_SPAN) % 360f + 360f) % 360f
        val lightness = 0.28f + ((h / 100) % 12) / 100f
        return ColorUtils.HSLToColor(floatArrayOf(hue, 0.72f, lightness))
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

/** Cyber glass & neon-ringed surfaces. */
object Ui {
    fun mix(a: Int, b: Int, f: Float) = ColorUtils.blendARGB(a, b, f)

    /** Rounded fill. radiusDp = 100 (default) gives a pill. */
    fun pill(dp: Float, color: Int, radiusDp: Float = 100f) =
        GradientDrawable().apply { cornerRadius = radiusDp * dp; setColor(color) }

    /**
     * List-row background driven by view state: electric cyan ring + cyber glass when focused,
     * magenta tint when activated, [rest] otherwise.
     */
    fun focusBg(dp: Float, radiusDp: Float, rest: Int = Color.TRANSPARENT, active: Int = 0x38FF00D0) =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                pill(dp, 0x331B1630, radiusDp).apply { setStroke((2 * dp).toInt().coerceAtLeast(2), Accent.SIGNAL) }
            )
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
