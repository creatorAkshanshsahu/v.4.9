package com.aktv.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.BitmapShader
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

/**
 * Cold starts play the entrance; warm returns go straight to the shelf. Pure, so the rule itself
 * is unit-tested.
 */
internal object IntroPolicy {
    const val WARM_WINDOW_MS = 15 * 60_000L
    fun shouldPlay(lastLaunchMs: Long, nowMs: Long): Boolean =
        lastLaunchMs <= 0 || nowMs - lastLaunchMs >= WARM_WINDOW_MS
}

/** The boot script the entrance types out. Pure, so the copy and its timing are unit-tested. */
internal object CyberBoot {
    val LINES = listOf(
        "> STREAMR//OS  COLD BOOT",
        "> LINKING LIVE NODES ............ OK",
        "> DECRYPTING STREAM KEYS ........ OK",
        "> SYNCING MOVIES + SERIES ....... OK",
        "> OPERATOR ....... AKSHANSH SAHU",
        "> NEURAL INTERFACE .......... ONLINE"
    )
    private const val FIRST = 0.02f
    private const val STAGGER = 0.07f
    private const val TYPE = 0.065f

    /** How many characters of line [index] are visible at entrance [progress] (0..1). */
    fun typed(index: Int, progress: Float): Int {
        val line = LINES.getOrNull(index) ?: return 0
        val t = ((progress - (FIRST + index * STAGGER)) / TYPE).coerceIn(0f, 1f)
        return (line.length * t).toInt()
    }
}

/**
 * STREAMR cyberpunk entrance (~4.4 s): a code-drawn boot sequence - terminal lines typing, a synthwave
 * grid rolling in, the logo and wordmark (with the "BY AKSHANSH SAHU" credit) glitching into place,
 * a segmented sync bar, and a scan-collapse exit. Skippable by any key or tap; the lower edge toggles
 * the sound sting; reduced-motion devices skip straight to the shelf.
 */
class AkIntroView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var onReveal: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null

    private val dp get() = resources.displayMetrics.density
    private val iconMark: Drawable? = runCatching { context.getDrawable(R.drawable.streamr_mark) }.getOrNull()
    private val fullLogo: Drawable? = runCatching { context.getDrawable(R.drawable.streamr_logo_full) }.getOrNull()
    private val rajdhani: Typeface? = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.rajdhani_bold) }.getOrNull()

    private val ink = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scanPaint = Paint()
    private val termPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; color = Accent.BRAND_LIGHT }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Accent.ACID; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Accent.DIM; textAlign = Paint.Align.CENTER }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val markRect = RectF()
    private val wordRect = RectF()
    private val soundTarget = RectF()

    // Kept named `progress` for BrandPreviewTest deterministic frame capture.
    private var progress = 0f
    private var revealed = false
    private var finished = false
    private var detached = false

    private var soundEnabled = context.getSharedPreferences("aktv", Context.MODE_PRIVATE)
        .getBoolean("intro_sound_enabled", true)
    private var soundPool: SoundPool? = null
    private var soundId = 0
    private var soundLoaded = false

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4400L
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            if (it.currentPlayTime >= 3600L) reveal()
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { if (!detached) finish() }
        })
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        updateAccessibilityLabel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val step = maxOf(2, (3 * dp).toInt())
        val bmp = Bitmap.createBitmap(1, step, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.argb(60, 0, 0, 0)) }
        scanPaint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun updateAccessibilityLabel() {
        contentDescription = "STREAMR boot sequence. Sound ${if (soundEnabled) "on" else "off"}. Tap the lower edge to toggle sound; activate elsewhere to skip."
    }

    fun start() {
        if (finished || animator.isStarted) return
        detached = false
        if (!ValueAnimator.areAnimatorsEnabled()) { finish(); return }
        animator.start()
        prepareSound()
    }

    fun skip() = finish()

    private fun prepareSound() {
        if (!soundEnabled || soundPool != null) return
        try {
            val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            soundPool = pool
            pool.setOnLoadCompleteListener { ready, id, status ->
                if (status == 0 && id == soundId && !finished && !detached) { soundLoaded = true; playSting(ready) }
            }
            soundId = pool.load(context, R.raw.aktv_intro_sting, 1)
        } catch (_: RuntimeException) { releaseSound() }
    }

    private fun playSting(pool: SoundPool? = soundPool) {
        if (!soundEnabled || finished || detached || !soundLoaded) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT || audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) return
        runCatching { pool?.play(soundId, 0.9f, 0.9f, 1, 0, 1f) }
    }

    private fun releaseSound() {
        val pool = soundPool
        soundPool = null; soundLoaded = false; soundId = 0
        runCatching { pool?.release() }
    }

    private fun toggleSound() {
        soundEnabled = !soundEnabled
        context.getSharedPreferences("aktv", Context.MODE_PRIVATE).edit().putBoolean("intro_sound_enabled", soundEnabled).apply()
        if (soundEnabled) prepareSound() else releaseSound()
        updateAccessibilityLabel()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        invalidate()
    }

    private fun reveal() {
        if (!revealed && !detached) { revealed = true; onReveal?.invoke() }
    }

    private fun finish() {
        if (finished || detached) return
        finished = true
        animator.cancel()
        releaseSound()
        reveal()
        onDone?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (soundTarget.contains(event.x, event.y)) { super.performClick(); toggleSound() } else performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); skip(); return true }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isLongClickable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) { toggleSound(); return true }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onDetachedFromWindow() {
        detached = true
        animator.cancel()
        releaseSound()
        super.onDetachedFromWindow()
    }

    private fun window(from: Float, to: Float) = ((progress - from) / (to - from)).coerceIn(0f, 1f)

    /** Draws [d] with an RGB-split glitch whose strength is [glitch] (0 = clean). Deterministic per frame. */
    private fun drawGlitched(canvas: Canvas, d: Drawable, r: RectF, alpha: Int, glitch: Float, seed: Int) {
        val rnd = java.util.Random(seed.toLong())
        val amp = glitch * 10f * dp
        if (glitch > 0.02f) {
            for ((colour, dir) in listOf(Accent.BRAND_LIGHT to -1f, Accent.BRAND to 1f)) {
                d.setColorFilter(android.graphics.PorterDuffColorFilter(colour, PorterDuff.Mode.SRC_IN))
                d.alpha = (alpha * 0.55f * glitch).toInt()
                val dx = dir * amp * (0.6f + rnd.nextFloat() * 0.6f)
                d.setBounds((r.left + dx).toInt(), r.top.toInt(), (r.right + dx).toInt(), r.bottom.toInt()); d.draw(canvas)
            }
            d.colorFilter = null
        }
        d.alpha = alpha
        if (glitch > 0.05f) {
            // Slice the art into bands and knock a few sideways.
            val bands = 7
            val bh = r.height() / bands
            for (b in 0 until bands) {
                val shift = if (rnd.nextFloat() < 0.45f) (rnd.nextFloat() - 0.5f) * amp * 2.2f else 0f
                canvas.save()
                canvas.clipRect(r.left - amp * 2, r.top + b * bh, r.right + amp * 2, r.top + (b + 1) * bh)
                d.setBounds((r.left + shift).toInt(), r.top.toInt(), (r.right + shift).toInt(), r.bottom.toInt()); d.draw(canvas)
                canvas.restore()
            }
        } else {
            d.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()); d.draw(canvas)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val exit = window(0.90f, 1f)
        val a = 1f - exit
        val alpha = (255 * a).toInt()
        val t = progress * 4.4f
        ink.color = Color.argb(alpha, 5, 4, 11)
        canvas.drawRect(0f, 0f, w, h, ink)

        // Horizon bloom and synthwave grid rolling toward the viewer.
        val horizon = h * 0.66f
        val grid = window(0.06f, 0.30f)
        glowPaint.shader = RadialGradient(w / 2f, horizon, maxOf(w, h) * 0.55f,
            intArrayOf(Color.argb((alpha * 0.55f * grid).toInt(), 255, 0, 208), Color.argb((alpha * 0.18f * grid).toInt(), 123, 92, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, glowPaint)
        gridPaint.strokeWidth = maxOf(1f, 1.2f * dp)
        val cx = w / 2f
        gridPaint.color = Color.argb((alpha * 0.45f * grid).toInt(), 255, 0, 208)
        for (i in -16..16) canvas.drawLine(cx + i * w * 0.012f, horizon, cx + i * w * 0.13f, h, gridPaint)
        val phase = (t * 0.6f) % 1f
        for (k in 0 until 16) {
            val z = 1f + k - phase
            val y = horizon + (h - horizon) / z
            if (y > h) continue
            val near = ((y - horizon) / (h - horizon)).coerceIn(0f, 1f)
            gridPaint.color = Color.argb((alpha * grid * (0.10f + 0.6f * near)).toInt(), 255, 0, 208)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        gridPaint.color = Color.argb((alpha * 0.8f * grid).toInt(), 0, 236, 247)
        canvas.drawLine(0f, horizon, w, horizon, gridPaint)

        // Terminal boot lines, top-left.
        termPaint.textSize = (if (h < 420 * dp) 9.5f else 11.5f) * dp
        termPaint.alpha = (alpha * 0.9f).toInt()
        val lineH = termPaint.textSize * 1.45f
        var y = 28f * dp + termPaint.textSize
        CyberBoot.LINES.forEachIndexed { i, line ->
            val n = CyberBoot.typed(i, progress)
            if (n > 0) {
                termPaint.color = if (line.endsWith("ONLINE") && n == line.length) Accent.ACID else Accent.BRAND_LIGHT
                termPaint.alpha = (alpha * 0.9f).toInt()
                val shown = line.substring(0, n)
                canvas.drawText(shown, 28f * dp, y, termPaint)
                if (n < line.length && ((t * 6).toInt() % 2 == 0)) {
                    val cw = termPaint.measureText(shown)
                    canvas.drawRect(28f * dp + cw + 2 * dp, y - termPaint.textSize * 0.8f, 28f * dp + cw + 2 * dp + termPaint.textSize * 0.55f, y + 2 * dp, termPaint)
                }
            }
            y += lineH
        }

        // Logo mark glitches in, then the wordmark (carrying the "BY AKSHANSH SAHU" credit).
        val short = h < 420 * dp
        val markIn = window(0.24f, 0.46f)
        val markSide = minOf(w, h) * (if (short) 0.28f else 0.30f) * (0.9f + 0.1f * markIn)
        val markCy = h * (if (short) 0.36f else 0.38f)
        markRect.set(cx - markSide / 2, markCy - markSide / 2, cx + markSide / 2, markCy + markSide / 2)
        iconMark?.let { if (markIn > 0f) drawGlitched(canvas, it, markRect, (alpha * minOf(1f, markIn * 1.6f)).toInt(), (1f - markIn) + glitchBurst(0.70f), (progress * 60).toInt()) }
        val wordIn = window(0.42f, 0.64f)
        val wordW = minOf(w * 0.72f, 640f * dp)
        val ratio = (fullLogo?.intrinsicHeight ?: 278).toFloat() / (fullLogo?.intrinsicWidth ?: 1200).coerceAtLeast(1)
        val wordTop = markRect.bottom + 14f * dp
        wordRect.set(cx - wordW / 2, wordTop, cx + wordW / 2, wordTop + wordW * ratio)
        fullLogo?.let { if (wordIn > 0f) drawGlitched(canvas, it, wordRect, (alpha * minOf(1f, wordIn * 1.6f)).toInt(), (1f - wordIn) + glitchBurst(0.78f), (progress * 60).toInt() + 7) }

        // CRT scanlines over everything drawn so far.
        scanPaint.alpha = alpha
        canvas.drawRect(0f, 0f, w, h, scanPaint)

        // HUD frame: acid brackets in the four corners.
        hudPaint.strokeWidth = 2f * dp; hudPaint.alpha = (alpha * window(0.0f, 0.12f)).toInt()
        val m = 14f * dp; val L = 26f * dp
        canvas.drawLine(m, m, m + L, m, hudPaint); canvas.drawLine(m, m, m, m + L, hudPaint)
        canvas.drawLine(w - m, m, w - m - L, m, hudPaint); canvas.drawLine(w - m, m, w - m, m + L, hudPaint)
        canvas.drawLine(m, h - m, m + L, h - m, hudPaint); canvas.drawLine(m, h - m, m, h - m - L, hudPaint)
        canvas.drawLine(w - m, h - m, w - m - L, h - m, hudPaint); canvas.drawLine(w - m, h - m, w - m, h - m - L, hudPaint)

        // Exit: the picture collapses to a bright scan line, then lets the shelf through.
        if (exit > 0f) {
            flashPaint.color = Color.argb((255 * (1f - exit) * 0.8f).toInt(), 0, 236, 247)
            val half = (h / 2f) * (1f - exit)
            canvas.drawRect(0f, h / 2f - maxOf(1.5f * dp, half * 0.02f), w, h / 2f + maxOf(1.5f * dp, half * 0.02f), flashPaint)
        }
    }

    /** A short second glitch burst around [at] so the logo twitches once after it lands. */
    private fun glitchBurst(at: Float): Float {
        val d = kotlin.math.abs(progress - at)
        return if (d < 0.015f) (1f - d / 0.015f) * 0.6f else 0f
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val alpha = (255 * (1f - window(0.90f, 1f))).toInt()
        val cx = w / 2f
        // Segmented sync bar with a percentage readout.
        val segments = 24
        val barW = minOf(w * 0.42f, 360f * dp); val segGap = 3f * dp
        val segW = (barW - segGap * (segments - 1)) / segments; val segH = 5f * dp
        val left = cx - barW / 2; val top = h - 46f * dp
        val filled = (window(0.04f, 0.88f) * segments).toInt()
        for (i in 0 until segments) {
            segPaint.color = if (i < filled) Accent.ACID else Color.argb(60, 152, 175, 213)
            segPaint.alpha = (alpha * (if (i < filled) 1f else 0.6f)).toInt()
            val x = left + i * (segW + segGap)
            canvas.drawRect(x, top, x + segW, top + segH, segPaint)
        }
        labelPaint.typeface = rajdhani ?: Typeface.DEFAULT_BOLD
        labelPaint.textSize = 12f * dp; labelPaint.letterSpacing = 0.18f
        labelPaint.color = Accent.ACID; labelPaint.alpha = alpha
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("SYNC", left, top - 7f * dp, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(java.util.Locale.ROOT, "%03d%%", (window(0.04f, 0.88f) * 100).toInt()), left + barW, top - 7f * dp, labelPaint)
        hintPaint.textSize = 10.5f * dp; hintPaint.alpha = (alpha * 0.7f).toInt()
        hintPaint.typeface = rajdhani
        hintPaint.letterSpacing = 0.12f
        val soundHint = if (soundEnabled) "SOUND ON" else "MUTED"
        canvas.drawText("$soundHint  //  TAP OR PRESS ANY KEY TO SKIP", cx, h - 18f * dp, hintPaint)
        val half = 120f * dp
        soundTarget.set(cx - half, h - 44f * dp, cx + half, h)
    }
}
