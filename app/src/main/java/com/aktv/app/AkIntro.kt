package com.aktv.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.media.AudioManager
import android.media.SoundPool
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Short, skippable AK TV "Aurora Glass" reveal with an original sound mark; no copied music or loading delay. */
class AkIntroView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onReveal: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null
    private val dp get() = resources.displayMetrics.density
    private val mark: Drawable? = runCatching { context.getDrawable(R.drawable.aktv_logo) }.getOrNull()
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Accent.FAINT; textAlign = Paint.Align.CENTER; textSize = 11 * dp }
    private val bylinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Accent.DIM; textAlign = Paint.Align.CENTER; textSize = 11 * dp; letterSpacing = 0.08f
    }
    private val destination = RectF()
    private val soundTarget = RectF()
    private var progress = 0f
    private var revealed = false
    private var finished = false
    private var detached = false
    private var soundEnabled = context.getSharedPreferences("aktv", Context.MODE_PRIVATE).getBoolean("intro_sound_enabled", true)
    private var soundPool: SoundPool? = null
    private var soundId = 0
    private var soundStream = 0
    private var soundLoaded = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        interpolator = DecelerateInterpolator(1.35f)
        addUpdateListener {
            progress = it.animatedValue as Float
            if (it.currentPlayTime >= 1180) reveal()
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { if (!detached) finish() }
        })
    }

    init { updateAccessibilityLabel() }

    private fun updateAccessibilityLabel() {
        contentDescription = "AK TV intro. Sound ${if (soundEnabled) "on" else "off"}. Tap the lower edge to toggle sound; activate elsewhere to skip."
    }

    fun start() {
        if (finished || animator.isStarted) return
        detached = false
        if (!ValueAnimator.areAnimatorsEnabled()) { finish(); return }
        // Start the animator first: SoundPool can finish loading synchronously on fast devices, and
        // playSting intentionally refuses to play before the reveal is running.
        animator.start()
        prepareSound()
    }
    fun skip() = finish()

    private fun prepareSound() {
        if (!soundEnabled || soundPool != null) return
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
            soundPool = pool
            pool.setOnLoadCompleteListener { readyPool, id, status ->
                if (status == 0 && !finished && !detached && id == soundId) {
                    soundLoaded = true
                    playSting(readyPool)
                }
            }
            soundId = pool.load(context, R.raw.aktv_intro_sting, 1)
        } catch (_: RuntimeException) { releaseSound() }
    }

    private fun playSting(pool: SoundPool? = soundPool) {
        if (!soundEnabled || finished || detached || !animator.isRunning || !soundLoaded) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT || audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) return
        soundStream = runCatching { pool?.play(soundId, 0.95f, 0.95f, 1, 0, 1f) ?: 0 }.getOrDefault(0)
    }

    private fun toggleSound() {
        soundEnabled = !soundEnabled
        context.getSharedPreferences("aktv", Context.MODE_PRIVATE).edit().putBoolean("intro_sound_enabled", soundEnabled).apply()
        if (soundEnabled) prepareSound() else releaseSound()
        updateAccessibilityLabel(); sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        invalidate()
    }

    private fun releaseSound() {
        val pool = soundPool; val stream = soundStream
        soundPool = null; soundLoaded = false; soundId = 0; soundStream = 0
        runCatching { if (stream != 0) pool?.stop(stream); pool?.release() }
    }

    private fun reveal() { if (!revealed && !detached) { revealed = true; onReveal?.invoke() } }
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
                if (soundTarget.contains(event.x, event.y)) { super.performClick(); toggleSound() }
                else performClick()
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
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) { toggleSound(); return true }
        return super.performAccessibilityAction(action, arguments)
    }
    override fun onDetachedFromWindow() {
        detached = true; animator.cancel(); releaseSound()
        super.onDetachedFromWindow()
    }

    // ---- Aurora Glass entrance: aurora bloom -> glass tile springs in -> light sweep -> wordmark tracks in ----
    private val ink = Paint()
    private val bloomRose = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloomIris = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Accent.TEXT; textAlign = Paint.Align.CENTER
        typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.sora_bold) }.getOrNull()
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val atop = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
    private val spring = android.view.animation.OvershootInterpolator(1.7f)
    private val ease = DecelerateInterpolator(1.6f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = maxOf(w, h) * 0.55f
        bloomRose.shader = android.graphics.RadialGradient(0f, 0f, r, intArrayOf(0x99FF4F7B.toInt(), 0x33B65CFF, 0x00000000), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        bloomIris.shader = android.graphics.RadialGradient(0f, 0f, r, intArrayOf(0x996366FF.toInt(), 0x22B65CFF, 0x00000000), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        barPaint.shader = android.graphics.LinearGradient(0f, 0f, 120 * dp, 0f, intArrayOf(Accent.ROSE, Accent.ORCHID, Accent.IRIS), null, android.graphics.Shader.TileMode.MIRROR)
    }

    private fun window(p: Float, from: Float, to: Float) = ((p - from) / (to - from)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        val exit = window(progress, 0.90f, 1f)
        val alpha = 1f - exit
        ink.color = Color.argb((255 * alpha).toInt(), 6, 8, 18)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ink)

        val centerX = width / 2f
        val shortScreen = height < 420 * dp
        val markY = height * if (shortScreen) 0.38f else 0.40f

        // 1. Aurora bloom: two colour fields swell and drift apart behind the mark.
        val bloom = ease.getInterpolation(window(progress, 0f, 0.55f))
        val drift = 40 * dp * bloom
        bloomRose.alpha = (255 * bloom * alpha).toInt(); bloomIris.alpha = (255 * bloom * alpha).toInt()
        canvas.save(); canvas.translate(centerX - drift * 2.2f, markY - drift); canvas.scale(0.6f + 0.4f * bloom, 0.6f + 0.4f * bloom)
        canvas.drawCircle(0f, 0f, maxOf(width, height) * 0.55f, bloomRose); canvas.restore()
        canvas.save(); canvas.translate(centerX + drift * 2.2f, markY + drift); canvas.scale(0.6f + 0.4f * bloom, 0.6f + 0.4f * bloom)
        canvas.drawCircle(0f, 0f, maxOf(width, height) * 0.55f, bloomIris); canvas.restore()

        // 2. Glass tile springs in.
        val appear = window(progress, 0.04f, 0.40f)
        val scale = 0.55f + 0.45f * spring.getInterpolation(appear)
        val side = minOf(width, height) * (if (shortScreen) 0.34f else 0.30f) * scale
        destination.set(centerX - side / 2, markY - side / 2, centerX + side / 2, markY + side / 2)
        // 3. A light sweep crosses the glass once, composited only onto the mark's own pixels.
        val sweep = window(progress, 0.36f, 0.68f)
        val sweeping = sweep in 0.001f..0.999f && mark != null
        val layer = if (sweeping) canvas.saveLayer(destination, null) else -1
        mark?.let {
            it.alpha = (255 * alpha * minOf(1f, appear * 2.5f)).toInt()
            it.setBounds(destination.left.toInt(), destination.top.toInt(), destination.right.toInt(), destination.bottom.toInt())
            it.draw(canvas)
        }
        if (sweeping) {
            val band = side * 0.45f
            sweepPaint.shader = android.graphics.LinearGradient(0f, 0f, band, 0f,
                intArrayOf(0x00FFFFFF, 0x80FFFFFF.toInt(), 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP)
            sweepPaint.alpha = (255 * alpha).toInt()
            sweepPaint.xfermode = atop
            canvas.save()
            canvas.translate(destination.left - band * 1.5f + (side + band * 2.5f) * sweep, destination.top - side * 0.2f)
            canvas.skew(-0.4f, 0f)
            canvas.drawRect(0f, 0f, band, side * 1.4f, sweepPaint)
            canvas.restore()
            canvas.restoreToCount(layer)
        }

        // 4. Wordmark tracks in: letters start wide and settle.
        val word = ease.getInterpolation(window(progress, 0.28f, 0.62f))
        wordPaint.textSize = (if (shortScreen) 22 else 28) * dp
        wordPaint.letterSpacing = 0.18f + 0.55f * (1f - word)
        wordPaint.alpha = (255 * word * alpha).toInt()
        val wordY = destination.bottom + (if (shortScreen) 40 else 54) * dp
        canvas.drawText("AK TV", centerX, wordY, wordPaint)

        bylinePaint.alpha = (255 * window(progress, 0.45f, 0.70f) * alpha * 0.86f).toInt()
        val bylineY = minOf(wordY + 24 * dp, height * 0.86f)
        canvas.drawText("by Akshansh Sahu", centerX, bylineY, bylinePaint)

        // 5. Aurora loading bar shimmering along a frosted track.
        val barW = minOf(width * 0.22f, 220 * dp); val barH = 3 * dp; val barY = height * 0.92f
        trackPaint.alpha = (38 * alpha).toInt()
        canvas.drawRoundRect(centerX - barW / 2, barY, centerX + barW / 2, barY + barH, barH, barH, trackPaint)
        val fill = ease.getInterpolation(window(progress, 0.05f, 0.90f))
        barPaint.alpha = (255 * alpha).toInt()
        canvas.save(); canvas.translate(progress * 240 * dp, 0f)
        canvas.drawRoundRect(centerX - barW / 2 - progress * 240 * dp, barY, centerX - barW / 2 + barW * fill - progress * 240 * dp, barY + barH, barH, barH, barPaint)
        canvas.restore()
        hintPaint.alpha = (180 * alpha).toInt()
        canvas.drawText("Loading…", centerX, barY - 10 * dp, hintPaint)
        val half = 100 * dp
        soundTarget.set(centerX - half, height - 40 * dp, centerX + half, height.toFloat())
    }
}
