package com.aktv.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
 * AK TV "Aurora Glass" palette (4.10): navy-ink depth, frosted white glass, and a rose -> orchid ->
 * iris aurora for focus, live cues and brand moments. Older token names are kept as aliases so
 * playback code, layouts and tests keep a single source of truth.
 */
object Accent {
    val INK = 0xFF060812.toInt()
    val NIGHT = 0xFF0B0E1C.toInt()
    val SURFACE = 0xFF151A2E.toInt()
    val SURFACE_HI = 0xFF1F2642.toInt()

    /** The aurora: warm rose (brand primary), orchid, and cool iris. */
    val ROSE = 0xFFFF4F7B.toInt()
    val ORCHID = 0xFFB65CFF.toInt()
    val IRIS = 0xFF6366FF.toInt()
    val ROSE_BRIGHT = 0xFFFF7A9C.toInt()
    val ROSE_DEEP = 0xFFB0204F.toInt()
    /** A warm bloom used low in the aurora so the glass never looks cold. */
    val EMBER = 0xFFFF8A5C.toInt()

    val LIVE = 0xFFFF3D5E.toInt()
    val GOLD = 0xFFFFC85C.toInt()
    val OK = 0xFF2FD39A.toInt()
    val WARN = 0xFFFFB547.toInt()
    val TEXT = 0xFFF6F7FF.toInt()
    val DIM = 0xFFB9BED6.toInt()
    val FAINT = 0xFF7C82A3.toInt()
    /** Near-white with a cool cast; the specular highlight in glass. */
    val GLOSS_HI = 0xFFF4F1FF.toInt()

    // ---- legacy names (pre-4.10) mapped onto the aurora ----
    val ABYSS = INK
    val SIGNAL = ROSE
    val ION = ORCHID
    val INDIGO = IRIS
    val DEEP = SURFACE_HI
    val CRIMSON = ROSE
    val CRIMSON_BRIGHT = ROSE_BRIGHT
    val CRIMSON_DEEP = ROSE_DEEP
    val WINE = 0xFF2A1240.toInt()

    /** Rose -> orchid -> iris, the brand gradient used for primary focus fills. */
    fun aurora(): IntArray = intArrayOf(ROSE, ORCHID, IRIS)
    /** Kept for older call sites: the lacquer stops are now the aurora. */
    fun glossStops(): IntArray = aurora()

    /**
     * Makes any logo colour usable as a tint. The sampled hue is folded onto the aurora arc so
     * channel artwork still varies, but every surface reads as one AK TV identity.
     */
    fun normalize(c: Int?): Int {
        if (c == null) return ROSE
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        if (hsl[1] < 0.18f) return ROSE
        return ColorUtils.HSLToColor(floatArrayOf(projectHue(hsl[0]), max(hsl[1], 0.66f), hsl[2].coerceIn(0.52f, 0.64f)))
    }

    /** Aurora arc: iris (236 deg) through orchid to rose (352 deg). */
    const val ARC_START = 236f
    const val ARC_END = 352f

    /**
     * Fold any hue onto the aurora arc instead of blending in RGB (which turns blue+red into mud).
     * Warm hues land on the rose end, greens/cyans on the iris end, purples stay roughly put.
     */
    fun projectHue(hue: Float): Float {
        val h = ((hue % 360f) + 360f) % 360f
        return when {
            h in ARC_START..ARC_END -> h
            // Reds, oranges and yellows (352..360, 0..90) slide toward rose.
            h > ARC_END || h <= 90f -> {
                val distance = if (h > ARC_END) h - ARC_END else h + (360f - ARC_END)
                ARC_END - distance / (98f) * 22f
            }
            // Greens and cyans (90..236) slide toward iris.
            else -> ARC_START + (ARC_START - h) / 146f * 22f
        }
    }

    /** A darker neighbouring hue so a tinted panel keeps depth instead of one flat colour. */
    fun partner(c: Int): Int {
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        hsl[0] = projectHue(hsl[0] - 28f)
        hsl[1] = hsl[1].coerceAtLeast(0.50f)
        hsl[2] = 0.30f
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * Stable tint for channels with no logo to sample (V2 / 24-7): same name, same colour, spread
     * across the aurora so a wall of tiles shimmers instead of reading as one flat block.
     */
    fun fromName(name: String): Int {
        var h = 7
        for (ch in name.lowercase()) h = (h * 31 + ch.code) and 0x7fffffff
        val spread = (h % 100) / 99f
        val hue = ARC_START + 4f + spread * (ARC_END - ARC_START - 8f)
        val lightness = 0.50f + ((h / 100) % 10) / 100f
        return ColorUtils.HSLToColor(floatArrayOf(hue, 0.70f, lightness))
    }

    fun from(d: Drawable, cb: (Int) -> Unit) {
        val bmp = (d as? BitmapDrawable)?.bitmap
        if (bmp == null || bmp.config == Bitmap.Config.HARDWARE) { cb(ROSE); return }
        Palette.from(bmp).maximumColorCount(16).generate { p ->
            val sw = p?.vibrantSwatch ?: p?.lightVibrantSwatch ?: p?.darkVibrantSwatch ?: p?.dominantSwatch
            cb(normalize(sw?.rgb))
        }
    }
}

/** Small colour/shape helpers shared by every screen. */
object Ui {
    fun mix(a: Int, b: Int, f: Float) = ColorUtils.blendARGB(a, b, f)
    fun alpha(c: Int, a: Float) = ColorUtils.setAlphaComponent(c, (a.coerceIn(0f, 1f) * 255).toInt())

    /** Rounded flat fill. radiusDp = 100 (default) gives a pill. */
    fun pill(dp: Float, color: Int, radiusDp: Float = 100f) =
        GradientDrawable().apply { cornerRadius = radiusDp * dp; setColor(color) }

    /** List-row background driven by the view's own state, now a glass row with an aurora focus. */
    fun focusBg(dp: Float, radiusDp: Float, rest: Int = Color.TRANSPARENT, active: Int = Ui.alpha(Color.WHITE, 0.10f)) =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                Accent.aurora()).apply { cornerRadius = radiusDp * dp })
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
    var accent: Int = Accent.ROSE,
    var shine: Boolean = true,
    var rimAlpha: Float = 1f,
    /** Rim strength at rest (0 = invisible until focused/active, for quiet nav items). */
    var restRim: Float = 1f,
) : Drawable(), Animatable {
    var radius = radiusDp * dp
        set(value) { field = value; rebuild(); invalidateSelf() }
    var glassTint: Int = glassTint
        set(value) { field = value; rebuild(); invalidateSelf() }

    private val rect = RectF()
    private val clip = Path()
    private val lens = Path()
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
        rect.set(b); clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val h = rect.height(); val w = rect.width()
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
            intArrayOf(Ui.mix(accent, Color.WHITE, 0.55f), accent, Ui.mix(accent, Accent.IRIS, 0.6f)), null, Shader.TileMode.CLAMP)
        val band = max(w, h) * 0.42f
        sweep.shader = LinearGradient(0f, 0f, band, 0f,
            intArrayOf(Color.TRANSPARENT, Ui.alpha(Color.WHITE, 0.30f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val m = alphaMul / 255f
        val bodyAlpha = restAlpha + (activeAlpha - restAlpha) * activeLevel + (focusAlpha - restAlpha) * level
        body.alpha = (255 * bodyAlpha.coerceIn(0f, 1f) * m).toInt()
        canvas.drawRoundRect(rect, radius, radius, body)
        if (focusFill != null && level > 0f) {
            fill.alpha = (255 * level * m).toInt()
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        specular.alpha = (255 * (0.55f + 0.45f * level) * (restRim + (1f - restRim) * max(level, activeLevel)) * m).toInt()
        canvas.drawPath(lens, specular)
        val stroke = max(1f, dp * (1f + 0.6f * level))
        rim.strokeWidth = stroke; accentRim.strokeWidth = stroke
        val inset = stroke / 2
        rect.inset(inset, inset)
        val r = max(0f, radius - inset)
        val lit = max(level, activeLevel)
        rim.alpha = (255 * rimAlpha * (restRim + (1f - restRim) * lit) * (0.75f + 0.25f * level) * m).toInt()
        canvas.drawRoundRect(rect, r, r, rim)
        val accentLevel = max(level, activeLevel * 0.55f)
        if (accentLevel > 0f && focusFill == null) {
            accentRim.alpha = (255 * accentLevel * m).toInt()
            canvas.drawRoundRect(rect, r, r, accentRim)
        }
        rect.inset(-inset, -inset)
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
        outline.setRoundRect(bounds, radius)
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

/** Glass factories with the AK TV defaults, so every screen uses the same material. */
object Glass {
    /** Floating panel (rail, info card, guide): frosted, a little denser so text stays readable. */
    fun panel(dp: Float, radiusDp: Float = 24f, density: Float = 0.10f) = GlassDrawable(dp, radiusDp,
        glassTint = Accent.SURFACE_HI, restAlpha = 0.62f + density, activeAlpha = 0.62f + density, focusAlpha = 0.62f + density, shine = false)

    /** Content card (channel tile, poster frame): clear glass that lifts and glows on focus. */
    fun card(dp: Float, radiusDp: Float = 18f, tint: Int = Color.WHITE) = GlassDrawable(dp, radiusDp,
        glassTint = tint, restAlpha = 0.10f, activeAlpha = 0.16f, focusAlpha = 0.24f)

    /** Pill control: clear at rest, aurora-filled with a white rim on focus. */
    fun pill(dp: Float, radiusDp: Float = 100f, rest: Float = 0.08f) = GlassDrawable(dp, radiusDp,
        restAlpha = rest, activeAlpha = 0.16f, focusAlpha = 0.20f, focusFill = Accent.aurora())

    /** Primary action: bright frosted white that turns into the aurora on focus. */
    fun primary(dp: Float, radiusDp: Float = 100f) = GlassDrawable(dp, radiusDp,
        restAlpha = 0.92f, activeAlpha = 0.92f, focusAlpha = 0.92f, focusFill = Accent.aurora())
}

/** Shared motion language: springy focus lift with a coloured glow, and staggered entrances. */
object Motion {
    val enabled: Boolean get() = ValueAnimator.areAnimatorsEnabled()
    private val settle = DecelerateInterpolator(1.8f)

    /** Focus lift for cards and buttons: a soft overshoot up, an easy settle down, aurora-coloured glow. */
    fun focus(v: View, focused: Boolean, scale: Float = 1.06f, liftDp: Float = 14f, glow: Int = Accent.ROSE) {
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

    /** Fade + rise for content arriving on screen. */
    fun rise(v: View, delay: Long = 0, distanceDp: Float = 18f, duration: Long = 420) {
        if (!enabled) { v.alpha = 1f; v.translationY = 0f; return }
        val dp = v.resources.displayMetrics.density
        v.animate().cancel()
        v.alpha = 0f; v.translationY = distanceDp * dp
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(duration).setInterpolator(settle).start()
    }
}

/**
 * Living aurora backdrop: four soft colour fields drift on slow orbits behind the glass. It is
 * rasterised at 1/8 resolution on the CPU and upscaled with bilinear filtering, so the GPU only
 * draws one full-screen texture per frame. Runs at ~24 fps, pauses when hidden, behind playback,
 * or when the system has animations off.
 */
class AuroraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private data class Blob(var color: Int, val fx: Float, val fy: Float, val r: Float, val ax: Float, val ay: Float, val speed: Float, val phase: Float)

    private val blobs = listOf(
        Blob(Accent.ROSE, 0.78f, 0.10f, 0.62f, 0.10f, 0.07f, 0.050f, 0.0f),
        Blob(Accent.ORCHID, 0.35f, 0.05f, 0.55f, 0.12f, 0.08f, 0.038f, 1.7f),
        Blob(Accent.IRIS, 0.95f, 0.70f, 0.60f, 0.08f, 0.10f, 0.044f, 3.1f),
        Blob(Accent.EMBER, 0.10f, 0.95f, 0.50f, 0.09f, 0.06f, 0.031f, 4.6f),
    )
    private val base = intArrayOf(Accent.ROSE, Accent.ORCHID, Accent.IRIS, Accent.EMBER)
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
        c.drawColor(Accent.INK, PorterDuff.Mode.SRC)
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
class PulseDotDrawable(private val dp: Float, color: Int = Accent.LIVE, sizeDp: Float = 12f) : Drawable(), Animatable {
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
