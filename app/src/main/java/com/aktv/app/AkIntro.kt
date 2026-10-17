package com.aktv.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs

/** The AK ribbon mark: same shapes as the launcher icon and TV banner. Box is 136 x 100 units. */
object AkMark {
    const val W = 136f
    const val H = 100f
    class Piece(val name: String, val pts: FloatArray, val shade: Int)

    // shade: 0 light, 1 mid, 2 dark, 3 fold, 4 bar. Listed back to front.
    val PIECES = listOf(
        Piece("A_left", floatArrayOf(24.50f, 0.00f, 39.50f, 0.00f, 15.00f, 100.00f, 0.00f, 100.00f), 1),
        Piece("A_right", floatArrayOf(24.50f, 0.00f, 39.50f, 0.00f, 64.00f, 100.00f, 49.00f, 100.00f), 2),
        Piece("A_fold", floatArrayOf(24.50f, 0.00f, 39.50f, 0.00f, 32.00f, 30.61f), 3),
        Piece("A_bar", floatArrayOf(23.31f, 62.00f, 40.69f, 62.00f, 43.88f, 75.00f, 20.12f, 75.00f), 4),
        Piece("K_leg", floatArrayOf(87.00f, 58.00f, 93.80f, 40.00f, 131.00f, 100.00f, 113.00f, 100.00f), 2),
        Piece("K_arm", floatArrayOf(116.00f, 0.00f, 134.00f, 0.00f, 87.00f, 75.81f, 87.00f, 46.77f), 1),
        Piece("K_stem", floatArrayOf(72.00f, 0.00f, 87.00f, 0.00f, 87.00f, 100.00f, 72.00f, 100.00f), 0)
    )

    // top colour, bottom colour of the ribbon gradient (runs the full height of the mark)
    val SHADES = arrayOf(
        intArrayOf(0xFF9ADCFF.toInt(), 0xFF52A8FF.toInt()),
        intArrayOf(0xFF55A0FF.toInt(), 0xFF3B72F2.toInt()),
        intArrayOf(0xFF3C62E4.toInt(), 0xFF2A3CB6.toInt()),
        intArrayOf(0xFF1E2C8E.toInt(), 0xFF1E2C8E.toInt()),
        intArrayOf(0xFF4672F0.toInt(), 0xFF3458D8.toInt())
    )
}

/**
 * Netflix-style entrance in gold and black. The AKTV logo slams in from huge to rest with a
 * flash, a shock ring and gold light streaks burst out, the logo pulses once, then it zooms
 * through the camera into the home screen. Any key press skips to the zoom-through.
 */
class AkIntroView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    var onReveal: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var wordmark: Typeface? = null
        set(v) { field = v; creditP.typeface = v }

    private val total = 3200L
    private val exitAt = 2300L
    private var t = 0L
    private var revealed = false
    private var finished = false

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = total; interpolator = LinearInterpolator()
        addUpdateListener {
            t = (it.animatedFraction * total).toLong()
            if (!revealed && t >= exitAt + 80) { revealed = true; onReveal?.invoke() }
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { finish() }
        })
    }

    private val logo: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_intro)
    private val logoP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val flashP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowP = Paint()
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val streakP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenP = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private val creditP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.08f }
    private val dst = RectF()
    private var cx = 0f; private var cy = 0f; private var lw = 0f; private var lh = 0f
    private val ns = 22
    private val sAng = FloatArray(ns); private val sLen = FloatArray(ns)

    fun start() { if (!anim.isStarted) anim.start() }
    fun skip() { if (t < exitAt - 120) anim.currentPlayTime = exitAt - 120 }

    private fun finish() {
        if (finished) return
        finished = true
        if (!revealed) { revealed = true; onReveal?.invoke() }
        onDone?.invoke()
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h * 0.46f
        lw = w * 0.52f; lh = lw * logo.height / logo.width
        glowP.shader = RadialGradient(cx, cy, w * 0.55f,
            intArrayOf(Color.argb(150, 255, 190, 60), Color.argb(50, 160, 100, 20), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        flashP.shader = RadialGradient(cx, cy, w * 0.45f,
            intArrayOf(Color.argb(255, 255, 244, 210), Color.argb(120, 255, 200, 90), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        sheenP.shader = LinearGradient(0f, 0f, lw * 0.25f, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(210, 255, 250, 230), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        for (i in 0 until ns) {
            val j = ((i * 37) % 13) / 12f
            sAng[i] = i * 360f / ns + j * 9f
            sLen[i] = w * (0.35f + 0.4f * j)
        }
        streakP.strokeCap = Paint.Cap.ROUND
        creditP.textSize = h * 0.026f
    }

    private fun phase(now: Long, from: Long, to: Long) = ((now - from).toFloat() / (to - from)).coerceIn(0f, 1f)
    private fun smooth(x: Float) = x * x * (3f - 2f * x)
    private fun easeOut(x: Float): Float { val k = 1f - x; return 1f - k * k * k }
    private fun easeIn(x: Float) = x * x * x

    override fun onDraw(cv: Canvas) {
        if (lw == 0f) return
        val w = width.toFloat(); val h = height.toFloat()
        val bgA = 1f - smooth(phase(t, exitAt + 200, total))
        cv.drawColor(Color.argb((bgA * 255).toInt(), 4, 3, 2))

        // slam: logo drops from 3.2x to 1x with a tiny overshoot
        val slam = phase(t, 200, 620)
        val e = easeIn(slam)
        var s = 3.2f - 2.2f * e
        val settle = phase(t, 620, 900)
        if (slam >= 1f) s = 1f - 0.05f * kotlin.math.sin(settle * Math.PI).toFloat()
        val pulse = phase(t, 1500, 1900)
        s *= 1f + 0.06f * kotlin.math.sin(pulse * Math.PI).toFloat()
        val exitZ = easeIn(phase(t, exitAt, total))
        s *= 1f + 9f * exitZ
        val markA = (smooth(phase(t, 200, 420)) * (1f - smooth(phase(t, exitAt + 250, total - 100)))).coerceIn(0f, 1f)

        // ambient gold glow
        val gA = (smooth(phase(t, 600, 1100)) * 0.8f + 0.4f * kotlin.math.sin(pulse * Math.PI).toFloat()).coerceIn(0f, 1f) * markA
        if (gA > 0f) { glowP.alpha = (gA * 255).toInt(); cv.drawRect(0f, 0f, w, h, glowP) }

        // streak burst on impact
        val bs = phase(t, 620, 1500)
        if (bs > 0f && bs < 1f) {
            val out = easeOut(bs); val a = (1f - bs) * markA
            for (i in 0 until ns) {
                val rad = Math.toRadians(sAng[i].toDouble())
                val dx = kotlin.math.cos(rad).toFloat(); val dy = kotlin.math.sin(rad).toFloat() * 0.6f
                val r0 = lw * 0.25f + sLen[i] * out * 0.4f; val r1 = lw * 0.25f + sLen[i] * out
                streakP.shader = LinearGradient(cx + dx * r0, cy + dy * r0, cx + dx * r1, cy + dy * r1,
                    Color.argb((a * 230).toInt(), 255, 214, 120), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                streakP.strokeWidth = h * (0.004f + 0.006f * (i % 3))
                cv.drawLine(cx + dx * r0, cy + dy * r0, cx + dx * r1, cy + dy * r1, streakP)
            }
            // shock ring
            ringP.color = Color.rgb(255, 200, 90); ringP.alpha = ((1f - bs) * 200 * markA).toInt()
            ringP.strokeWidth = h * 0.012f * (1f - bs) + 1f
            cv.save(); cv.scale(1f, 0.55f, cx, cy)
            cv.drawCircle(cx, cy, lw * 0.3f + w * 0.6f * out, ringP); cv.restore()
        }

        // logo + light sweep in one layer
        val hw = lw * s / 2f; val hh = lh * s / 2f
        dst.set(cx - hw, cy - hh, cx + hw, cy + hh)
        cv.saveLayerAlpha(dst, (markA * 255).toInt())
        cv.drawBitmap(logo, null, dst, logoP)
        val sw = phase(t, 1100, 1700)
        if (sw > 0f && sw < 1f) {
            cv.save(); cv.rotate(20f, cx, cy)
            cv.translate(dst.left - lw * 0.3f + (dst.width() + lw * 0.6f) * smooth(sw), 0f)
            cv.drawRect(0f, cy - h, lw * 0.25f, cy + h, sheenP); cv.restore()
        }
        cv.restore()

        // impact flash
        val fl = phase(t, 600, 900)
        if (fl > 0f && fl < 1f) { flashP.alpha = ((1f - fl) * 255).toInt(); cv.drawRect(0f, 0f, w, h, flashP) }

        val ca = smooth(phase(t, 1300, 1700)) * (1f - smooth(phase(t, exitAt, exitAt + 350)))
        if (ca > 0f) {
            creditP.color = 0xFFB8925A.toInt(); creditP.alpha = (ca * 255).toInt()
            cv.drawText("by Akshansh Sahu", w / 2f, h * 0.93f, creditP)
        }
    }
}
