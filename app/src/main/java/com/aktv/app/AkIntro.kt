package com.aktv.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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

/** Short, skippable AK TV brand reveal with an original sound mark; no copied music or loading delay. */
class AkIntroView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onReveal: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var wordmark: Typeface? = null
        set(value) { field = value; titlePaint.typeface = value; subtitlePaint.typeface = value }
    private val dp get() = resources.displayMetrics.density
    private val mark: Drawable? = runCatching { context.getDrawable(R.drawable.ic_brand) }.getOrNull()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 2 * dp }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 5 * dp }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Accent.TEXT; textAlign = Paint.Align.CENTER; textSize = 23 * dp; letterSpacing = 0.10f }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Accent.DIM; textAlign = Paint.Align.CENTER; textSize = 9 * dp; letterSpacing = 0.10f }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Accent.FAINT; textAlign = Paint.Align.CENTER; textSize = 9 * dp }
    private val soundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Accent.ION; textAlign = Paint.Align.CENTER; textSize = 8 * dp; letterSpacing = 0.08f }
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
        duration = 1600
        interpolator = DecelerateInterpolator(1.25f)
        addUpdateListener {
            progress = it.animatedValue as Float
            if (it.currentPlayTime >= 1050) reveal()
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { if (!detached) finish() }
        })
    }

    init { updateAccessibilityLabel() }

    private fun updateAccessibilityLabel() {
        contentDescription = "AK TV intro. Sound ${if (soundEnabled) "on" else "off"}. Activate to skip; long press to toggle sound."
    }

    fun start() {
        if (finished || animator.isStarted) return
        detached = false
        if (!ValueAnimator.areAnimatorsEnabled()) { finish(); return }
        prepareSound()
        animator.start()
    }
    fun skip() = finish()

    private fun prepareSound() {
        if (!soundEnabled || soundPool != null) return
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
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
        soundStream = runCatching { pool?.play(soundId, 0.78f, 0.78f, 1, 0, 1f) ?: 0 }.getOrDefault(0)
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

    override fun onDraw(canvas: Canvas) {
        val exit = ((progress - 0.93f) / 0.07f).coerceIn(0f, 1f)
        val alpha = (255 * (1f - exit)).toInt()
        backgroundPaint.color = Color.argb(alpha, 8, 11, 20)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val appear = (progress / 0.32f).coerceIn(0f, 1f)
        val centerX = width / 2f
        val centerY = height * 0.39f + (1f - appear) * 13 * dp
        val scale = 0.91f + 0.09f * appear
        val markWidth = minOf(width * 0.25f, 220 * dp) * scale
        val markHeight = markWidth * ((mark?.intrinsicHeight ?: 1).toFloat() / (mark?.intrinsicWidth ?: 1).coerceAtLeast(1))
        destination.set(centerX - markWidth / 2, centerY - markHeight / 2, centerX + markWidth / 2, centerY + markHeight / 2)
        mark?.let {
            it.alpha = (alpha * appear).toInt()
            it.setBounds(destination.left.toInt(), destination.top.toInt(), destination.right.toInt(), destination.bottom.toInt())
            it.draw(canvas)
        }
        titlePaint.alpha = (alpha * appear).toInt()
        canvas.drawText("AK TV", centerX, destination.bottom + 34 * dp, titlePaint)
        subtitlePaint.alpha = (alpha * ((progress - 0.22f) / 0.35f).coerceIn(0f, 1f)).toInt()
        canvas.drawText("LIVE  ·  FILMS  ·  YOUR WORLD", centerX, destination.bottom + 57 * dp, subtitlePaint)
        val linePhase = ((progress - 0.18f) / 0.6f).coerceIn(0f, 1f)
        val span = 54 * dp * linePhase
        glowPaint.color = Accent.INDIGO; glowPaint.alpha = (alpha * 0.16f).toInt()
        canvas.drawLine(centerX - span, destination.bottom + 78 * dp, centerX + span, destination.bottom + 78 * dp, glowPaint)
        linePaint.color = Accent.ION; linePaint.alpha = (alpha * 0.8f).toInt()
        canvas.drawLine(centerX - span, destination.bottom + 78 * dp, centerX + span, destination.bottom + 78 * dp, linePaint)
        hintPaint.alpha = (alpha * 0.72f).toInt()
        canvas.drawText("Press any key  ·  tap to skip", centerX, height - 48 * dp, hintPaint)
        soundPaint.alpha = (alpha * 0.82f).toInt()
        val soundLabel = if (soundEnabled) "SOUND ON  ·  tap to mute" else "SOUND OFF  ·  tap to enable"
        canvas.drawText(soundLabel, centerX, height - 22 * dp, soundPaint)
        val half = 100 * dp
        soundTarget.set(centerX - half, height - 42 * dp, centerX + half, height.toFloat())
    }
}
