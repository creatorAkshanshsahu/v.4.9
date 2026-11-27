package com.aktv.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
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
    /** True live red: channel-tile dots and the channel hero's pulse. The theme's LIVE stays cyan. */
    val LIVE_DOT = 0xFFFF3B30.toInt()
    val GOLD = 0xFFFFB84D.toInt()
    val TEXT = 0xFFF5F5F5.toInt()
    val DIM = 0xFF98AFD5.toInt()
    val FAINT = 0xFF687AA0.toInt()

    /** Cyberpunk signal yellow: HUD brackets, focus locks, the play cut in the logo. */
    val ACID = 0xFFFCEE0A.toInt()
    /** Neon violet between the wordmark's magenta and cyan: the middle of the Aurora Glass gradient. */
    val VIOLET = 0xFF7B5CFF.toInt()
    /** A deep indigo bloom that keeps the aurora from washing out the dark ground. */
    val INDIGO_GLOW = 0xFF3B2BBF.toInt()
    /** Status colours for the channel scan badge, tuned for glass. */
    val OK = 0xFF19B27F.toInt()
    val WARN = 0xFFD08A1E.toInt()
    val BAD = 0xFFE0405E.toInt()

    /** Aurora Glass gradient in STREAMR's own neon: magenta -> violet -> cyan (the wordmark's sweep). */
    fun aurora(): IntArray = intArrayOf(BRAND, VIOLET, BRAND_LIGHT)

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
    fun alpha(c: Int, a: Float) = ColorUtils.setAlphaComponent(c, (a.coerceIn(0f, 1f) * 255).toInt())

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

/**
 * Liquid glass surface, drawn without any blur pass so it stays smooth on 32-bit TV boxes:
 *  - a translucent body (white, or a channel tint) that brightens on focus,
 *  - an optional aurora fill that cross-fades in on focus (primary buttons),
 *  - a specular lens over the upper half and a gradient rim light,
 *  - a one-shot light sweep across the glass when it gains focus.
 * State changes are animated, so focus "melts" between states instead of snapping.
 */
class GlassDrawable(
    private val dp: Float,
    radiusDp: Float,
    glassTint: Int = Color.WHITE,
    var restAlpha: Float = 0.08f,
    var activeAlpha: Float = 0.14f,
    var focusAlpha: Float = 0.20f,
    /** When set, focus cross-fades the body to this gradient (left -> right). */
    var focusFill: IntArray? = null,
    /** Accent mixed into the rim when focused. */
    var accent: Int = Accent.BRAND_LIGHT,
    var shine: Boolean = true,
    var rimAlpha: Float = 1f,
    /** Rim strength at rest (0 = invisible until focused/active, for quiet nav items). */
    var restRim: Float = 1f,
    /** Cyberpunk cut corners (top-left and bottom-right), in dp. 0 keeps the rounded [radius]. */
    chamferDp: Float = Cyber.CHAMFER_DP,
    /** Acid-yellow HUD brackets that lock onto the two uncut corners on focus. */
    var brackets: Boolean = true,
) : Drawable(), Animatable {
    var chamfer = chamferDp * dp
        set(value) { field = value; rebuild(); invalidateSelf() }
    var radius = radiusDp * dp
        set(value) { field = value; rebuild(); invalidateSelf() }
    var glassTint: Int = glassTint
        set(value) { field = value; rebuild(); invalidateSelf() }

    private val rect = RectF()
    private val clip = Path()
    private val rimPath = Path()
    private val lens = Path()
    private val rimRect = RectF()
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Accent.ACID; strokeCap = Paint.Cap.SQUARE }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specular = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val accentRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
    private var alphaMul = 255

    /** 0 rest, 1 focused; "active" (selected/activated) sits at [activeLevel]. */
    private var level = 0f
    private var activeLevel = 0f
    private var sweepAt = -1f
    private var focused = false
    private var active = false
    private var levelAnim: ValueAnimator? = null
    private var sweepAnim: ValueAnimator? = null

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val f = state.contains(android.R.attr.state_focused) || state.contains(android.R.attr.state_pressed)
        val a = state.contains(android.R.attr.state_activated) || state.contains(android.R.attr.state_selected)
        if (f == focused && a == active) return false
        val gained = f && !focused
        focused = f; active = a
        animateTo(if (f) 1f else 0f, if (a) 1f else 0f)
        if (gained && shine && Motion.enabled) runSweep()
        return true
    }

    /** Programmatic focus look, for previews and views that track focus themselves. */
    fun setLook(focused: Boolean, active: Boolean = false) {
        this.focused = focused; this.active = active
        levelAnim?.cancel(); level = if (focused) 1f else 0f; activeLevel = if (active) 1f else 0f
        invalidateSelf()
    }

    private fun animateTo(target: Float, activeTarget: Float) {
        levelAnim?.cancel()
        if (!Motion.enabled || bounds.isEmpty) { level = target; activeLevel = activeTarget; invalidateSelf(); return }
        val from = level; val fromActive = activeLevel
        levelAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (target > from) 200 else 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                level = from + (target - from) * t; activeLevel = fromActive + (activeTarget - fromActive) * t
                invalidateSelf()
            }
            start()
        }
    }

    private fun runSweep() {
        sweepAnim?.cancel()
        sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900; startDelay = 60; interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener { sweepAt = it.animatedValue as Float; invalidateSelf() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { sweepAt = -1f; invalidateSelf() }
            })
            start()
        }
    }

    override fun jumpToCurrentState() {
        levelAnim?.cancel(); sweepAnim?.cancel(); sweepAt = -1f
        level = if (focused) 1f else 0f; activeLevel = if (active) 1f else 0f
        invalidateSelf()
    }

    override fun onBoundsChange(b: Rect) { rebuild() }

    private fun rebuild() {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val h = rect.height(); val w = rect.width()
        shape(clip, rect)
        val stroke = max(1f, dp)
        rimRect.set(rect); rimRect.inset(stroke / 2, stroke / 2); shape(rimPath, rimRect)
        body.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(Ui.alpha(Ui.mix(glassTint, Color.WHITE, 0.35f), 1f), glassTint), null, Shader.TileMode.CLAMP)
        focusFill?.let { fill.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, it, null, Shader.TileMode.CLAMP) }
        // Specular lens: follows the top corners and ends in a soft downward arc at ~46% height.
        lens.reset()
        val lensBottom = rect.top + h * 0.46f
        lens.moveTo(rect.left, lensBottom - h * 0.08f)
        lens.quadTo(rect.centerX(), lensBottom + h * 0.06f, rect.right, lensBottom - h * 0.08f)
        lens.lineTo(rect.right, rect.top); lens.lineTo(rect.left, rect.top); lens.close()
        lens.op(clip, Path.Op.INTERSECT)
        specular.shader = LinearGradient(0f, rect.top, 0f, lensBottom,
            intArrayOf(Ui.alpha(Color.WHITE, 0.30f), Ui.alpha(Color.WHITE, 0.02f)), null, Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(Ui.alpha(Color.WHITE, 0.55f), Ui.alpha(Color.WHITE, 0.10f), Ui.alpha(Color.WHITE, 0.22f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        accentRim.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(Ui.mix(accent, Color.WHITE, 0.55f), accent, Ui.mix(accent, Accent.BRAND_LIGHT, 0.6f)), null, Shader.TileMode.CLAMP)
        val band = max(w, h) * 0.42f
        sweep.shader = LinearGradient(0f, 0f, band, 0f,
            intArrayOf(Color.TRANSPARENT, Ui.alpha(Color.WHITE, 0.30f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }

    /** Effective cut size: never more than a third of the short side, so small pills stay legible. */
    private fun cut(r: RectF) = if (chamfer <= 0f) 0f else minOf(chamfer, minOf(r.width(), r.height()) * 0.34f)

    private fun shape(out: Path, r: RectF) {
        out.reset()
        val c = cut(r)
        if (c <= 0f) { out.addRoundRect(r, minOf(radius, minOf(r.width(), r.height()) / 2), minOf(radius, minOf(r.width(), r.height()) / 2), Path.Direction.CW); return }
        out.moveTo(r.left + c, r.top); out.lineTo(r.right, r.top); out.lineTo(r.right, r.bottom - c)
        out.lineTo(r.right - c, r.bottom); out.lineTo(r.left, r.bottom); out.lineTo(r.left, r.top + c); out.close()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val m = alphaMul / 255f
        val bodyAlpha = restAlpha + (activeAlpha - restAlpha) * activeLevel + (focusAlpha - restAlpha) * level
        body.alpha = (255 * bodyAlpha.coerceIn(0f, 1f) * m).toInt()
        canvas.drawPath(clip, body)
        if (focusFill != null && level > 0f) {
            fill.alpha = (255 * level * m).toInt()
            canvas.drawPath(clip, fill)
        }
        specular.alpha = (255 * (0.55f + 0.45f * level) * (restRim + (1f - restRim) * max(level, activeLevel)) * m).toInt()
        canvas.drawPath(lens, specular)
        val stroke = max(1f, dp * (1f + 0.6f * level))
        rim.strokeWidth = stroke; accentRim.strokeWidth = stroke
        val lit = max(level, activeLevel)
        rim.alpha = (255 * rimAlpha * (restRim + (1f - restRim) * lit) * (0.75f + 0.25f * level) * m).toInt()
        canvas.drawPath(rimPath, rim)
        val accentLevel = max(level, activeLevel * 0.55f)
        if (accentLevel > 0f && focusFill == null) {
            accentRim.alpha = (255 * accentLevel * m).toInt()
            canvas.drawPath(rimPath, accentRim)
        }
        // HUD lock: acid brackets slide onto the two uncut corners as focus arrives.
        if (brackets && level > 0.01f) {
            val bw = max(2f, 2f * dp)
            val len = minOf(14f * dp, rect.width() * 0.22f, rect.height() * 0.32f) * (0.55f + 0.45f * level)
            val o = bw / 2 + (1f - level) * 4f * dp
            bracketPaint.strokeWidth = bw
            bracketPaint.alpha = (255 * level * m).toInt()
            canvas.drawLine(rect.right - o - len, rect.top + o, rect.right - o, rect.top + o, bracketPaint)
            canvas.drawLine(rect.right - o, rect.top + o, rect.right - o, rect.top + o + len, bracketPaint)
            canvas.drawLine(rect.left + o, rect.bottom - o - len, rect.left + o, rect.bottom - o, bracketPaint)
            canvas.drawLine(rect.left + o, rect.bottom - o, rect.left + o + len, rect.bottom - o, bracketPaint)
        }
        if (sweepAt >= 0f) {
            val save = canvas.save()
            canvas.clipPath(clip)
            val travel = rect.width() + rect.height()
            canvas.translate(rect.left - rect.height() * 0.6f + travel * sweepAt - rect.width() * 0.2f, rect.top)
            canvas.skew(-0.45f, 0f)
            sweep.alpha = (255 * sin(Math.PI * sweepAt).toFloat() * m).toInt()
            canvas.drawRect(0f, 0f, max(rect.width(), rect.height()) * 0.42f, rect.height() * 1.6f, sweep)
            canvas.restoreToCount(save)
        }
    }

    override fun getOutline(outline: Outline) {
        if (cut(rect) > 0f && !clip.isEmpty) {
            // The cut-corner silhouette is convex, so it can carry the shadow on every supported API level.
            if (android.os.Build.VERSION.SDK_INT >= 30) outline.setPath(clip) else @Suppress("DEPRECATION") outline.setConvexPath(clip)
        } else outline.setRoundRect(bounds, radius)
        // Glass casts a soft shadow, not a hard one.
        outline.alpha = 0.35f + 0.45f * level
    }

    override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
    override fun getAlpha() = alphaMul
    override fun setColorFilter(colorFilter: ColorFilter?) { body.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun start() { if (Motion.enabled && shine) runSweep() }
    override fun stop() { levelAnim?.cancel(); sweepAnim?.cancel() }
    override fun isRunning() = levelAnim?.isRunning == true || sweepAnim?.isRunning == true
}

/** GlassKit factories with the STREAMR defaults, so every screen uses the same material. */
object GlassKit {
    /** Floating panel (rail, info card, guide): frosted, a little denser so text stays readable. */
    fun panel(dp: Float, radiusDp: Float = 24f, density: Float = 0.10f) = GlassDrawable(dp, radiusDp,
        glassTint = Accent.SURFACE_HI, restAlpha = 0.62f + density, activeAlpha = 0.62f + density, focusAlpha = 0.62f + density, shine = false)

    /** Content card (channel tile, poster frame): clear glass that lifts and glows on focus. */
    fun card(dp: Float, radiusDp: Float = 18f, tint: Int = Color.WHITE) = GlassDrawable(dp, radiusDp,
        glassTint = tint, restAlpha = 0.10f, activeAlpha = 0.16f, focusAlpha = 0.24f)

    /** Pill control: clear at rest, aurora-filled with a white rim on focus. */
    fun pill(dp: Float, radiusDp: Float = 100f, rest: Float = 0.08f) = GlassDrawable(dp, radiusDp,
        restAlpha = rest, activeAlpha = 0.16f, focusAlpha = 0.20f, focusFill = Accent.aurora(), chamferDp = 8f)

    /** Primary action: bright frosted white that turns into the aurora on focus. */
    fun primary(dp: Float, radiusDp: Float = 100f) = GlassDrawable(dp, radiusDp,
        restAlpha = 0.92f, activeAlpha = 0.92f, focusAlpha = 0.92f, focusFill = Accent.aurora())
}

/** Shared motion language: springy focus lift with a coloured glow, and staggered entrances. */
object Motion {
    val enabled: Boolean get() = ValueAnimator.areAnimatorsEnabled()
    private val settle = DecelerateInterpolator(1.8f)

    /** Focus lift for cards and buttons: a soft overshoot up, an easy settle down, aurora-coloured glow. */
    fun focus(v: View, focused: Boolean, scale: Float = 1.06f, liftDp: Float = 14f, glow: Int = Accent.BRAND_LIGHT) {
        val dp = v.resources.displayMetrics.density
        v.outlineSpotShadowColor = glow
        v.outlineAmbientShadowColor = Ui.alpha(glow, 0.55f)
        v.animate().cancel()
        val s = if (focused) scale else 1f
        if (!enabled) { v.scaleX = s; v.scaleY = s; v.translationZ = if (focused) liftDp * dp else 0f; return }
        v.animate().setStartDelay(0).scaleX(s).scaleY(s).translationZ(if (focused) liftDp * dp else 0f)
            .setDuration(if (focused) 340 else 220)
            .setInterpolator(if (focused) OvershootInterpolator(1.3f) else settle).start()
    }

    /**
     * Cyberpunk glitch: a quick RGB-split jitter (cyan / magenta shadow offsets, sideways twitch) that
     * settles in ~0.35 s. The view's own shadow is restored afterwards. Off when animations are off.
     */
    fun glitch(v: android.widget.TextView, durationMs: Long = 360) {
        if (!enabled) return
        val dp = v.resources.displayMetrics.density
        (v.getTag(R.id.glitch_anim) as? ValueAnimator)?.cancel()
        val radius = v.shadowRadius; val dx = v.shadowDx; val dy = v.shadowDy; val color = v.shadowColor
        val rnd = java.util.Random()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { a ->
                val amp = (1f - a.animatedFraction) * 3.5f * dp
                val right = rnd.nextBoolean()
                v.setShadowLayer(1f, if (right) amp else -amp, 0f, if (right) Accent.BRAND_LIGHT else Accent.BRAND)
                v.translationX = (rnd.nextFloat() - 0.5f) * amp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    v.setShadowLayer(radius, dx, dy, color); v.translationX = 0f
                }
            })
        }
        v.setTag(R.id.glitch_anim, anim)
        anim.start()
    }

    /** Fade + rise for content arriving on screen. */
    fun rise(v: View, delay: Long = 0, distanceDp: Float = 18f, duration: Long = 420) {
        if (!enabled) { v.alpha = 1f; v.translationY = 0f; return }
        val dp = v.resources.displayMetrics.density
        v.animate().cancel()
        v.alpha = 0f; v.translationY = distanceDp * dp
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(duration).setInterpolator(settle).start()
    }
}

/** Cyberpunk constants and textures shared by every screen. */
object Cyber {
    /** Default cut-corner size for glass surfaces, in dp. */
    const val CHAMFER_DP = 10f

    /** A tiled CRT scanline texture (one dark line every 3 dp) for heroes and the player HUD. */
    fun scanlines(res: android.content.res.Resources, alpha: Int = 34): Drawable {
        val step = max(2, (3 * res.displayMetrics.density).toInt())
        val bmp = Bitmap.createBitmap(1, step, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, Color.argb(alpha, 0, 0, 0))
        return BitmapDrawable(res, bmp).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }
}

/**
 * Living aurora backdrop in STREAMR neon: four soft colour fields drift on slow orbits behind the glass. It is
 * rasterised at 1/8 resolution on the CPU and upscaled with bilinear filtering, so the GPU only
 * draws one full-screen texture per frame. Runs at ~24 fps, pauses when hidden, behind playback,
 * or when the system has animations off.
 */
class AuroraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private data class Blob(var color: Int, val fx: Float, val fy: Float, val r: Float, val ax: Float, val ay: Float, val speed: Float, val phase: Float)

    private val blobs = listOf(
        Blob(Accent.BRAND, 0.78f, 0.10f, 0.62f, 0.10f, 0.07f, 0.050f, 0.0f),
        Blob(Accent.VIOLET, 0.35f, 0.05f, 0.55f, 0.12f, 0.08f, 0.038f, 1.7f),
        Blob(Accent.BRAND_LIGHT, 0.95f, 0.70f, 0.60f, 0.08f, 0.10f, 0.044f, 3.1f),
        Blob(Accent.INDIGO_GLOW, 0.10f, 0.95f, 0.50f, 0.09f, 0.06f, 0.031f, 4.6f),
    )
    private val base = intArrayOf(Accent.BRAND, Accent.VIOLET, Accent.BRAND_LIGHT, Accent.INDIGO_GLOW)
    private val sprite: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        c.drawCircle(32f, 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(32f, 32f, 32f, intArrayOf(Color.WHITE, Ui.alpha(Color.WHITE, 0.45f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
    }
    private var buffer: Bitmap? = null
    private var bufferCanvas: Canvas? = null
    private val blobPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val upscale = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF(); private val src = RectF()
    private val start = SystemClock.uptimeMillis()
    private var lastFrame = 0L
    private var running = false
    /** Overall strength of the colour fields (0..1). */
    var intensity = 0.55f
        set(value) { field = value; invalidate() }
    /** Pause from the host (e.g. while the player covers the screen). */
    var paused = false
        set(value) { field = value; updateRunning() }
    private var tintAnim: ValueAnimator? = null
    /** Synthwave perspective grid rolling toward the viewer under the aurora (cyberpunk). */
    var grid = true
        set(value) { field = value; invalidate() }
    var gridColor = Accent.BRAND
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sweepPaint = Paint()

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            if (now - lastFrame >= 40) { lastFrame = now; invalidate() }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Leans the aurora toward [color] (e.g. the focused channel's tint) with a slow cross-fade. */
    fun tintTo(color: Int?) {
        val targets = IntArray(4) { i -> if (color == null) base[i] else Ui.mix(base[i], color, if (i == 0) 0.70f else if (i == 3) 0.30f else 0.42f) }
        val from = IntArray(4) { blobs[it].color }
        tintAnim?.cancel()
        if (!Motion.enabled) { blobs.forEachIndexed { i, b -> b.color = targets[i] }; invalidate(); return }
        tintAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900; interpolator = DecelerateInterpolator()
            addUpdateListener { a -> val t = a.animatedValue as Float
                blobs.forEachIndexed { i, b -> b.color = Ui.mix(from[i], targets[i], t) }
                if (!running) invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        buffer?.recycle(); buffer = null; bufferCanvas = null
        if (w <= 0 || h <= 0) return
        buffer = Bitmap.createBitmap(max(16, w / 8), max(9, h / 8), Bitmap.Config.ARGB_8888).also { bufferCanvas = Canvas(it) }
    }

    override fun onDraw(canvas: Canvas) {
        val buf = buffer ?: return; val c = bufferCanvas ?: return
        val bw = buf.width.toFloat(); val bh = buf.height.toFloat()
        c.drawColor(Accent.ABYSS, PorterDuff.Mode.SRC)
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        src.set(0f, 0f, sprite.width.toFloat(), sprite.height.toFloat())
        for (b in blobs) {
            val x = (b.fx + b.ax * sin(t * b.speed * 2f + b.phase)) * bw
            val y = (b.fy + b.ay * cos(t * b.speed * 1.6f + b.phase)) * bh
            val r = b.r * max(bw, bh) * (1f + 0.06f * sin(t * b.speed * 3f + b.phase))
            blobPaint.colorFilter = PorterDuffColorFilter(b.color, PorterDuff.Mode.SRC_IN)
            blobPaint.alpha = (255 * intensity).toInt()
            dst.set(x - r, y - r, x + r, y + r)
            c.drawBitmap(sprite, null, dst, blobPaint)
        }
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(buf, null, dst, upscale)
        if (grid) drawGrid(canvas, t)
    }

    /** ~40 lines per frame: a vanishing-point grid in the lower half, plus a slow cyan scan sweep. */
    private fun drawGrid(canvas: Canvas, t: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val d = resources.displayMetrics.density
        val horizon = h * 0.60f
        val cx = w / 2f
        gridPaint.strokeWidth = max(1f, 1.1f * d)
        val strength = (0.45f + intensity) * 0.5f
        // Rays toward the vanishing point.
        gridPaint.color = Ui.alpha(gridColor, 0.20f * strength)
        for (i in -14..14) canvas.drawLine(cx + i * w * 0.010f, horizon, cx + i * w * 0.115f, h, gridPaint)
        // Cross lines advancing toward the viewer; brighter as they come near.
        val phase = (t * 0.35f) % 1f
        for (k in 0 until 18) {
            val z = 1f + k - phase
            if (z <= 0.2f) continue
            val y = horizon + (h - horizon) / z
            if (y > h) continue
            val near = ((y - horizon) / (h - horizon)).coerceIn(0f, 1f)
            gridPaint.color = Ui.alpha(gridColor, (0.06f + 0.30f * near) * strength)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        // Horizon glow line.
        gridPaint.color = Ui.alpha(Accent.BRAND_LIGHT, 0.30f * strength)
        canvas.drawLine(0f, horizon, w, horizon, gridPaint)
        // Scan sweep: a faint band rolls down the screen every eight seconds.
        val sy = ((t % 8f) / 8f) * h
        sweepPaint.shader = LinearGradient(0f, sy - 40f * d, 0f, sy, intArrayOf(Color.TRANSPARENT, Ui.alpha(Accent.BRAND_LIGHT, 0.06f)),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, sy - 40f * d, w, sy, sweepPaint)
    }

    private fun updateRunning() {
        val should = isAttachedToWindow && windowVisibility == VISIBLE && isShown && !paused && Motion.enabled
        if (should == running) return
        running = should
        if (running) Choreographer.getInstance().postFrameCallback(frame)
        else Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onDetachedFromWindow() { running = false; Choreographer.getInstance().removeFrameCallback(frame); tintAnim?.cancel(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateRunning() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); updateRunning() }
}

/** A breathing LIVE dot: solid core with a ring that swells and fades, looping while visible. */
class PulseDotDrawable(private val dp: Float, color: Int = Accent.LIVE_DOT, sizeDp: Float = 12f) : Drawable(), Animatable {
    private val size = (sizeDp * dp).toInt()
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    var color: Int = color
        set(value) { field = value; core.color = value; ring.color = value; invalidateSelf() }
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidateSelf() }
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
        val maxR = min(bounds.width(), bounds.height()) / 2f
        val coreR = maxR * 0.42f
        ring.alpha = (150 * (1f - phase)).toInt()
        canvas.drawCircle(cx, cy, coreR + (maxR - coreR) * phase, ring)
        canvas.drawCircle(cx, cy, coreR, core)
    }
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }
    override fun start() { if (Motion.enabled && !anim.isStarted) anim.start() }
    override fun stop() { anim.cancel(); phase = 0f }
    override fun isRunning() = anim.isRunning
    override fun setAlpha(alpha: Int) { core.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { core.colorFilter = colorFilter; ring.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Cyberpunk HUD panel for the player: a cut-corner ink glass slab with a thin cyan edge, an acid
 * signal stripe down the left side and tick marks on the top-right corner.
 */
class HudPanelDrawable(private val dp: Float, private val cutDp: Float = 16f, private val fillAlpha: Float = 0.62f) : Drawable() {
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.alpha(Accent.BRAND_LIGHT, 0.45f) }
    private val acid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Accent.ACID }
    private var alphaMul = 255

    override fun onBoundsChange(b: Rect) {
        val r = RectF(b); val c = minOf(cutDp * dp, r.height() * 0.3f)
        path.reset()
        path.moveTo(r.left + c, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.bottom - c)
        path.lineTo(r.right - c, r.bottom); path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.top + c); path.close()
        fill.shader = LinearGradient(r.left, 0f, r.right, 0f,
            intArrayOf(Ui.alpha(Accent.ABYSS, fillAlpha + 0.18f), Ui.alpha(Accent.ABYSS, fillAlpha), Ui.alpha(Accent.ABYSS, fillAlpha * 0.35f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds; if (b.isEmpty) return
        val m = alphaMul / 255f
        fill.alpha = alphaMul; canvas.drawPath(path, fill)
        edge.strokeWidth = max(1f, dp); edge.alpha = (115 * m).toInt(); canvas.drawPath(path, edge)
        val c = minOf(cutDp * dp, b.height() * 0.3f)
        acid.alpha = alphaMul
        canvas.drawRect(b.left.toFloat(), b.top + c + 6 * dp, b.left + 3 * dp, b.top + b.height() * 0.62f, acid)
        // Corner ticks: three short marks on the top-right, like a sensor readout.
        for (i in 0 until 3) canvas.drawRect(b.right - (10 + i * 7) * dp, b.top + 4 * dp, b.right - (6 + i * 7) * dp, b.top + 6 * dp, acid)
    }

    override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Segmented neon progress bar (programme progress): filled segments run acid -> magenta, the rest are
 * dim. Driven by the drawable level, so it works as a ProgressBar's progressDrawable.
 */
class SegmentedBarDrawable(private val dp: Float, private val segments: Int = 40) : Drawable() {
    private val on = Paint(Paint.ANTI_ALIAS_FLAG)
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.alpha(Color.WHITE, 0.16f) }

    override fun onBoundsChange(b: Rect) {
        on.shader = LinearGradient(b.left.toFloat(), 0f, b.right.toFloat(), 0f, intArrayOf(Accent.ACID, Accent.BRAND, Accent.BRAND_LIGHT), null, Shader.TileMode.CLAMP)
    }
    override fun onLevelChange(level: Int): Boolean { invalidateSelf(); return true }
    override fun draw(canvas: Canvas) {
        val b = bounds; if (b.isEmpty) return
        val gap = max(1f, 2 * dp)
        val segW = (b.width() - gap * (segments - 1)) / segments
        val filled = (level / 10_000f * segments)
        for (i in 0 until segments) {
            val x = b.left + i * (segW + gap)
            canvas.drawRect(x, b.top.toFloat(), x + segW, b.bottom.toFloat(), if (i < filled) on else off)
        }
    }
    override fun setAlpha(alpha: Int) { on.alpha = alpha; off.alpha = (alpha * 0.16f).toInt(); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { on.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
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
