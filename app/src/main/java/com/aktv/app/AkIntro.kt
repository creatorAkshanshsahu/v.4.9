package com.aktv.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Short brand reveal, not a second loading screen. No flash, particles, sound or giant zoom layers. */
class AkIntroView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onReveal: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var wordmark: Typeface? = null
        set(value) { field = value; titlePaint.typeface = value; subtitlePaint.typeface = value }
    private val dp get() = resources.displayMetrics.density
    private val mark = BitmapFactory.decodeResource(resources, R.drawable.ic_mark)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 2 * dp }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Accent.TEXT; textAlign = Paint.Align.CENTER; textSize = 23 * dp; letterSpacing = 0.10f }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Accent.DIM; textAlign = Paint.Align.CENTER; textSize = 9 * dp; letterSpacing = 0.10f }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Accent.FAINT; textAlign = Paint.Align.CENTER; textSize = 9 * dp }
    private val destination = RectF()
    private var progress = 0f
    private var revealed = false
    private var finished = false
    private var detached = false
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

    fun start() {
        if (finished || animator.isStarted) return
        detached = false
        if (!ValueAnimator.areAnimatorsEnabled()) finish() else animator.start()
    }
    fun skip() = finish()
    private fun reveal() { if (!revealed && !detached) { revealed = true; onReveal?.invoke() } }
    private fun finish() {
        if (finished || detached) return
        finished = true
        animator.cancel()
        reveal()
        onDone?.invoke()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) { performClick(); skip() }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { detached = true; animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val exit = ((progress - 0.93f) / 0.07f).coerceIn(0f, 1f)
        val alpha = (255 * (1f - exit)).toInt()
        backgroundPaint.color = Color.argb(alpha, 8, 13, 20)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val appear = (progress / 0.32f).coerceIn(0f, 1f)
        val centerX = width / 2f
        val centerY = height * 0.40f + (1f - appear) * 8 * dp
        val markWidth = minOf(width * 0.26f, 240 * dp) * (0.94f + 0.06f * appear)
        val markHeight = markWidth * mark.height / mark.width
        destination.set(centerX - markWidth / 2, centerY - markHeight / 2, centerX + markWidth / 2, centerY + markHeight / 2)
        bitmapPaint.alpha = (alpha * appear).toInt()
        canvas.drawBitmap(mark, null, destination, bitmapPaint)
        titlePaint.alpha = (alpha * appear).toInt()
        canvas.drawText("AK TV", centerX, destination.bottom + 34 * dp, titlePaint)
        subtitlePaint.alpha = (alpha * ((progress - 0.22f) / 0.35f).coerceIn(0f, 1f)).toInt()
        canvas.drawText("LIVE. FILMS. YOUR WORLD.", centerX, destination.bottom + 57 * dp, subtitlePaint)
        linePaint.color = Accent.SIGNAL; linePaint.alpha = (alpha * 0.65f).toInt()
        val span = 48 * dp * ((progress - 0.18f) / 0.6f).coerceIn(0f, 1f)
        canvas.drawLine(centerX - span, destination.bottom + 78 * dp, centerX + span, destination.bottom + 78 * dp, linePaint)
        hintPaint.alpha = (alpha * 0.75f).toInt()
        canvas.drawText("Press any key or tap to skip", centerX, height - 22 * dp, hintPaint)
    }
}
