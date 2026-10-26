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

/** Short, skippable AK TV brand reveal with an original sound mark; no copied music or loading delay. */
class AkIntroView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onReveal: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null
    private val dp get() = resources.displayMetrics.density
    private val mark: Drawable? = runCatching { context.getDrawable(R.drawable.aktv_logo_cinematic) }.getOrNull()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Accent.DIM; textAlign = Paint.Align.CENTER; textSize = 13 * dp }
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

    override fun onDraw(canvas: Canvas) {
        val exit = ((progress - 0.90f) / 0.10f).coerceIn(0f, 1f)
        val alpha = (255 * (1f - exit)).toInt()
        backgroundPaint.color = Color.argb(alpha, 7, 10, 18)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val centerX = width / 2f
        val shortScreen = height < 420 * dp
        val appear = (progress / 0.30f).coerceIn(0f, 1f)
        val settle = (progress / 0.46f).coerceIn(0f, 1f)
        val logoY = height * if (shortScreen) 0.37f else 0.38f
        val scale = 0.96f + 0.04f * settle
        val markWidth = minOf(width * (if (shortScreen) 0.68f else 0.58f), height * 0.76f) * scale
        val markHeight = markWidth * ((mark?.intrinsicHeight ?: 1).toFloat() / (mark?.intrinsicWidth ?: 1).coerceAtLeast(1))
        destination.set(centerX - markWidth / 2, logoY - markHeight / 2, centerX + markWidth / 2, logoY + markHeight / 2)
        mark?.let {
            it.alpha = (alpha * appear).toInt()
            it.setBounds(destination.left.toInt(), destination.top.toInt(), destination.right.toInt(), destination.bottom.toInt())
            it.draw(canvas)
        }

        hintPaint.alpha = (alpha * 0.74f).toInt()
        canvas.drawText("Loading...", centerX, height * 0.93f, hintPaint)
        val half = 100 * dp
        soundTarget.set(centerX - half, height - 40 * dp, centerX + half, height.toFloat())
    }
}
