package com.aktv.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Full-screen skippable cyber-neon STREAMR entrance playing `R.raw.streamr_intro_video`
 * (`streamr-cyber-logo-credit-motion.mp4`), with a deterministic vector/drawable fallback for
 * reduced-motion, headless preview rendering, or constrained decoders.
 */
class AkIntroView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var onReveal: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null

    private val dp get() = resources.displayMetrics.density
    private val iconMark: Drawable? = runCatching { context.getDrawable(R.drawable.streamr_mark) }.getOrNull()
    private val fullLogo: Drawable? = runCatching { context.getDrawable(R.drawable.streamr_logo_full) }.getOrNull()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Accent.DIM; textAlign = Paint.Align.CENTER
        typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.sora_semibold) }.getOrNull()
    }
    private val bylinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Accent.SIGNAL; textAlign = Paint.Align.CENTER; letterSpacing = 0.28f
        typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.sora_bold) }.getOrNull()
    }

    private val iconRect = RectF()
    private val wordRect = RectF()
    private val barRect = RectF()
    private val soundTarget = RectF()

    // Kept named `progress` for BrandPreviewTest deterministic frame capture.
    private var progress = 0f
    private var revealed = false
    private var finished = false
    private var detached = false
    private var videoRendering = false
    private var videoWidth = 1920
    private var videoHeight = 1080

    private var soundEnabled = context.getSharedPreferences("aktv", Context.MODE_PRIVATE)
        .getBoolean("intro_sound_enabled", true)

    private var textureView: TextureView? = null
    private var mediaSurface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        // Matches the 6.04s motion logo duration with a clean safety ceiling if MediaPlayer doesn't fire completion.
        duration = 6100L
        interpolator = DecelerateInterpolator(1.15f)
        addUpdateListener {
            progress = it.animatedValue as Float
            if (it.currentPlayTime >= 4800L) reveal()
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!detached) finish()
            }
        })
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        updateAccessibilityLabel()
        setupVideoSurface()
    }

    private fun setupVideoSurface() {
        val tv = TextureView(context).apply {
            isOpaque = false
            alpha = 0f
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    if (finished || detached) return
                    updateVideoTransform(w, h)
                    if (animator.isStarted) attachAndPlayVideo(st)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    updateVideoTransform(w, h)
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    releaseVideo()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    if (!videoRendering && !finished && !detached) {
                        videoRendering = true
                        alpha = 1f
                        invalidate()
                    }
                }
            }
        }
        textureView = tv
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun updateVideoTransform(viewW: Int, viewH: Int) {
        val tv = textureView ?: return
        if (viewW <= 0 || viewH <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        // Aspect-fit (CENTER_INSIDE) on pure black so the full STREAMR cyber wordmark never crops on 20:9 phones or 16:9 TVs.
        val viewRatio = viewW.toFloat() / viewH.toFloat()
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (viewRatio > videoRatio) {
            scaleX = videoRatio / viewRatio
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewRatio / videoRatio
        }
        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        tv.setTransform(matrix)
    }

    private fun updateAccessibilityLabel() {
        contentDescription = "STREAMR cyber intro. Sound ${if (soundEnabled) "on" else "off"}. Tap the lower edge to toggle sound; activate elsewhere to skip."
    }

    fun start() {
        if (finished || animator.isStarted) return
        detached = false
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finish()
            return
        }
        animator.start()
        val tv = textureView
        if (tv != null && tv.isAvailable) {
            tv.surfaceTexture?.let { attachAndPlayVideo(it) }
        }
    }

    fun skip() = finish()

    private fun attachAndPlayVideo(st: SurfaceTexture) {
        if (finished || detached || mediaPlayer != null) return
        try {
            val surface = Surface(st)
            mediaSurface = surface
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.streamr_intro_video}")
            mp.setDataSource(context, uri)
            mp.setSurface(surface)
            mp.isLooping = false
            applyVolume(mp)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    videoWidth = w
                    videoHeight = h
                    textureView?.let { updateVideoTransform(it.width, it.height) }
                }
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && !finished && !detached) {
                    videoRendering = true
                    textureView?.alpha = 1f
                    invalidate()
                }
                false
            }
            mp.setOnPreparedListener { player ->
                if (finished || detached) {
                    releaseVideo()
                } else {
                    applyVolume(player)
                    runCatching { player.start() }
                }
            }
            mp.setOnCompletionListener {
                if (!detached) finish()
            }
            mp.setOnErrorListener { _, _, _ ->
                // Keep static cyber-neon canvas animation running briefly if hardware decoder declines, then reveal cleanly.
                releaseVideo()
                if (!finished && !detached && animator.currentPlayTime < 4300L) {
                    postDelayed({ if (!detached) finish() }, 1400L)
                }
                true
            }
            mp.prepareAsync()
        } catch (_: Throwable) {
            releaseVideo()
        }
    }

    private fun applyVolume(mp: MediaPlayer? = mediaPlayer) {
        val player = mp ?: return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val silent = audio != null && (
            audio.ringerMode == AudioManager.RINGER_MODE_SILENT ||
                audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0
            )
        val vol = if (soundEnabled && !silent) 0.92f else 0f
        runCatching { player.setVolume(vol, vol) }
    }

    private fun toggleSound() {
        soundEnabled = !soundEnabled
        context.getSharedPreferences("aktv", Context.MODE_PRIVATE)
            .edit().putBoolean("intro_sound_enabled", soundEnabled).apply()
        applyVolume()
        updateAccessibilityLabel()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        invalidate()
    }

    private fun releaseVideo() {
        val mp = mediaPlayer
        val surf = mediaSurface
        mediaPlayer = null
        mediaSurface = null
        videoRendering = false
        runCatching { mp?.setSurface(null) }
        runCatching { mp?.stop() }
        runCatching { mp?.reset() }
        runCatching { mp?.release() }
        runCatching { surf?.release() }
    }

    private fun reveal() {
        if (!revealed && !detached) {
            revealed = true
            onReveal?.invoke()
        }
    }

    private fun finish() {
        if (finished || detached) return
        finished = true
        animator.cancel()
        releaseVideo()
        reveal()
        onDone?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (soundTarget.contains(event.x, event.y)) {
                    super.performClick()
                    toggleSound()
                } else {
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        skip()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isLongClickable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
            toggleSound()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onDetachedFromWindow() {
        detached = true
        animator.cancel()
        releaseVideo()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val exit = ((progress - 0.92f) / 0.08f).coerceIn(0f, 1f)
        val alpha = (255 * (1f - exit)).toInt()
        textureView?.alpha = if (videoRendering) (1f - exit) else 0f

        // Pure obsidian-black ground so the motion MP4 blends edge-to-edge without any seam.
        backgroundPaint.color = Color.argb(alpha, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // When hardware video frames are actively rendering, let the full-motion logo take center stage.
        if (!videoRendering) {
            val cx = w / 2f
            val cy = h * 0.47f
            val radius = minOf(w, h) * 0.56f
            glowPaint.shader = RadialGradient(
                cx, cy, radius.coerceAtLeast(1f),
                intArrayOf(
                    Color.argb((alpha * 0.22f).toInt(), 255, 0, 208),
                    Color.argb((alpha * 0.14f).toInt(), 0, 236, 247),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, glowPaint)

            val appear = (progress / 0.24f).coerceIn(0f, 1f)
            val settle = (progress / 0.45f).coerceIn(0f, 1f)
            val scale = 0.94f + 0.06f * settle

            // Matches streamr-cyber-logo-credit-motion.mp4: full STREAMR + "BY AKSHANSH SAHU" lockup centered on black.
            val wordW = minOf(w * 0.82f, 760f * dp) * scale
            val ratio = (fullLogo?.intrinsicHeight ?: 278).toFloat() / (fullLogo?.intrinsicWidth ?: 1200).coerceAtLeast(1)
            val wordH = wordW * ratio
            wordRect.set(cx - wordW / 2f, cy - wordH / 2f, cx + wordW / 2f, cy + wordH / 2f)
            fullLogo?.let {
                it.alpha = (alpha * appear).toInt()
                it.setBounds(wordRect.left.toInt(), wordRect.top.toInt(), wordRect.right.toInt(), wordRect.bottom.toInt())
                it.draw(canvas)
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val exit = ((progress - 0.92f) / 0.08f).coerceIn(0f, 1f)
        val alpha = (255 * (1f - exit)).toInt()
        val cx = w / 2f

        // Subtle cyber progress hairline + skip hint over bottom edge of both video and canvas fallback.
        val barW = minOf(w * 0.28f, 220f * dp)
        val barH = (2.5f * dp).coerceAtLeast(2f)
        val barY = h - 34f * dp
        barRect.set(cx - barW / 2f, barY, cx + barW / 2f, barY + barH)
        barTrackPaint.color = Color.argb((alpha * 0.22f).toInt(), 152, 175, 213)
        canvas.drawRoundRect(barRect, barH, barH, barTrackPaint)

        val fillW = (barW * progress.coerceIn(0.04f, 1f))
        barFillPaint.shader = LinearGradient(
            barRect.left, barY, barRect.right, barY,
            intArrayOf(
                Color.argb(alpha, 255, 0, 208),
                Color.argb(alpha, 0, 236, 247)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(barRect.left, barY, barRect.left + fillW, barRect.bottom, barH, barH, barFillPaint)

        hintPaint.textSize = 11f * dp
        hintPaint.alpha = (alpha * 0.68f).toInt()
        val soundHint = if (soundEnabled) "Sound on" else "Muted"
        canvas.drawText("$soundHint  ·  Tap or press any key to skip", cx, h - 14f * dp, hintPaint)

        val half = 110f * dp
        soundTarget.set(cx - half, h - 44f * dp, cx + half, h)
    }
}
