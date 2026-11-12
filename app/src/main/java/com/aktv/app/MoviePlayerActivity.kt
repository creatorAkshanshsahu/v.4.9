package com.aktv.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Native TG Frost VOD. No Gecko session, browser URL scraping or live-TV DRM/token policy. */
class MoviePlayerActivity : Activity() {
    companion object {
        fun open(
            context: Context,
            title: Movies.Title,
            season: Int,
            episode: Int,
            episodes: List<Movies.Episode>,
            provider: String? = null
        ) {
            context.startActivity(playbackIntent(context, title, season, episode, episodes, provider))
        }
        internal fun playbackIntent(
            context: Context,
            title: Movies.Title,
            season: Int,
            episode: Int,
            episodes: List<Movies.Episode>,
            provider: String? = null
        ) =
            Intent(context, MoviePlayerActivity::class.java)
                .putExtra("tmdb", title.tmdb).putExtra("type", if (title.isSeries) "tv" else "movie")
                .putExtra("season", if (title.isSeries) season else 0).putExtra("episode", if (title.isSeries) episode else 0)
                .putExtra("name", title.name)
                .putExtra("episodes", (if (title.isSeries) episodes else emptyList()).take(2000)
                    .flatMap { listOf(it.season, it.episode) }.toIntArray())
                .apply { if (!provider.isNullOrBlank()) putExtra("provider", provider) }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val resolver = FrostResolver()
    private val prefs by lazy { getSharedPreferences("aktv", Context.MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }
    private val television by lazy { NativePlaybackPolicy.isTelevision(this) }
    private val lowMemory by lazy { (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true }
    private lateinit var title: FrostTitle
    private var titleName = ""
    private var initialized = false
    private var foreground = false
    private var generation = 0
    private var lookup: Future<*>? = null
    private var episodeLookup: Future<*>? = null
    private var episodesLoading = false
    private var episodes = emptyList<Pair<Int, Int>>()
    private var provider = FrostProvider.FROST
    private var sources = emptyList<FrostSource>()
    /** OG sources found for this title in this session: they stay in the menu even after another provider runs. */
    private var ogCache = emptyList<FrostSource>()
    private var currentSource: FrostSource? = null
    private val recovery = VodRecovery()
    private val sourceRecovery = VodStartupPolicy()
    /** One probe per provider per playback session; retries reuse the order it produced. */
    private val probed = HashSet<FrostProvider>()
    private val softwareRetried = HashSet<FrostProvider>()
    private var compatible = false
    private var diagnostics = PlaybackDiagnostics()
    private var trace: PlaybackDiagnostics.Attempt? = null
    private var player: ExoPlayer? = null
    private var surfaceWait: Pair<SurfaceHolder, SurfaceHolder.Callback>? = null
    private var dialog: Dialog? = null
    private var lastPosition = 0L
    private var lastDuration = 0L
    private var desiredPlay = true
    private var frame = false
    private var frameAt = 0L
    private var framePosition = 0L
    private var sourceConfirmed = false
    private var failed = false
    private var startupAt = 0L
    private var attemptAt = 0L
    private var bufferingAt = 0L
    private var savedAt = 0L
    private var lastFailure = ""

    private lateinit var view: PlayerView
    private lateinit var overlay: FrameLayout
    private lateinit var heading: TextView
    private lateinit var episodeLine: TextView
    private lateinit var timeLine: TextView
    /** One container correction per address: a mislabelled source is retried once, never in a loop. */
    private val containerRetried = HashSet<String>()
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var nextButton: TextView
    private lateinit var sourcesButton: TextView
    private lateinit var episodesButton: TextView
    private lateinit var autoNextButton: TextView
    private val actionButtons = mutableListOf<View>()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        CrashLog.install(this)
        try {
            title = FrostTitle(intent.getIntExtra("tmdb", 0), intent.getStringExtra("type").orEmpty(),
                saved?.getInt("season") ?: intent.getIntExtra("season", 0),
                saved?.getInt("episode") ?: intent.getIntExtra("episode", 0))
        } catch (bad: IllegalArgumentException) {
            Toast.makeText(this, "This movie or episode has no valid TMDB identity.", Toast.LENGTH_LONG).show()
            CrashLog.record(this, bad); finish(); return
        }
        titleName = intent.getStringExtra("name").orEmpty().take(180)
        val explicitProvider = saved?.getString("provider") ?: intent.getStringExtra("provider")
        provider = FrostProvider.values().firstOrNull {
            it.id == (explicitProvider ?: prefs.getString("vod_provider_${title.progressKey}", null))
        } ?: FrostProvider.FROST
        episodes = VodProgress.episodes(intent.getIntArrayExtra("episodes"))
        desiredPlay = saved?.getBoolean("play", true) ?: true
        restoreProgress()
        saved?.getLong("position")?.let { lastPosition = it.coerceAtLeast(0) }
        try {
            buildUi()
            initialized = true
        } catch (error: Throwable) {
            // A player that cannot build its own surface closes politely and leaves the reason behind.
            CrashLog.record(this, error)
            Toast.makeText(this, "The player could not open this title.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun buildUi() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        val root = FrameLayout(this).apply { setBackgroundColor(Accent.ABYSS) }
        view = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 5200
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowSubtitleButton(true)
            setShowNextButton(false); setShowPreviousButton(false)
            setKeepContentOnPlayerReset(false)
            setShutterBackgroundColor(Accent.ABYSS)
            setDefaultArtwork(getDrawable(R.drawable.home_hero_cinematic))
            useArtwork = true
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(Accent.BRAND); setScrubberColor(Accent.TEXT)
            setBufferedColor(0x4DFFFFFF); setUnplayedColor(0x29FFFFFF)
        }

        overlay = FrameLayout(this)
        overlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF20E1014.toInt(), 0xB30E1014.toInt(), 0x330E1014, Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(-1, px(245), Gravity.TOP))
        overlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF70E1014.toInt(), 0xC70E1014.toInt(), 0x330E1014, Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(-1, px(380), Gravity.BOTTOM))

        // Top bar: a glass back circle, the title, and the S/E line underneath — the site's watch header.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        // An ImageView with centre scaling: the arrow is drawn in the middle of the glass circle. As a
        // compound drawable on an empty TextView it was pinned to the start edge and sat against the rim.
        val back = ImageView(this).apply {
            background = androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.circle_glass, theme)
            setImageResource(R.drawable.ic_back)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Back"; isFocusable = true; isClickable = true
            setOnClickListener { finish() }
            setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.1f else 1f).scaleY(if (focused) 1.1f else 1f)
                    .setDuration(150).start()
                if (focused) { setOverlayVisible(true); if (!failed) view.showController() }
            }
        }
        actionButtons += back
        topBar.addView(back, LinearLayout.LayoutParams(px(46), px(46)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), 0, 0, 0)
        }
        topBar.addView(titles, LinearLayout.LayoutParams(-2, -2))
        val clock = android.widget.TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            textSize = 16f; setTextColor(Accent.TEXT)
            setShadowLayer(px(10).toFloat(), 0f, px(2).toFloat(), 0xAA000000.toInt())
        }
        overlay.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = px(40); topMargin = px(34)
        })

        heading = TextView(this).apply {
            textSize = 24f; setTextColor(Accent.TEXT); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = px(680)
            typeface = androidx.core.content.res.ResourcesCompat.getFont(this@MoviePlayerActivity, R.font.sora_bold)
            includeFontPadding = false
            setShadowLayer(px(12).toFloat(), 0f, px(2).toFloat(), 0xAA000000.toInt())
        }
        titles.addView(heading, LinearLayout.LayoutParams(-2, -2))
        episodeLine = TextView(this).apply {
            textSize = 13f; letterSpacing = 0.02f; setTextColor(Accent.DIM); maxLines = 1
            typeface = androidx.core.content.res.ResourcesCompat.getFont(this@MoviePlayerActivity, R.font.manrope)
            setShadowLayer(px(6).toFloat(), 0f, px(1).toFloat(), 0xAA000000.toInt())
        }
        titles.addView(episodeLine, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(3) })
        overlay.addView(topBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = px(42); topMargin = px(28)
        })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(8))
        }
        // Elapsed over duration, the one piece of state the site keeps at the bottom of the watch screen.
        timeLine = TextView(this).apply {
            textSize = 14f; letterSpacing = 0.04f; setTextColor(Accent.TEXT); maxLines = 1
            typeface = androidx.core.content.res.ResourcesCompat.getFont(this@MoviePlayerActivity, R.font.sora_semibold)
            setShadowLayer(px(6).toFloat(), 0f, px(1).toFloat(), 0xAA000000.toInt())
        }
        info.addView(timeLine, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            textSize = 11f; setTextColor(Accent.TEXT); maxLines = 2
            setPadding(px(12), px(7), px(12), px(7))
            background = GradientDrawable().apply {
                cornerRadius = px(14).toFloat(); setColor(0xD005070C.toInt())
                setStroke(px(1), 0x50F8F9FC)
            }
        }
        info.addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(12) })
        val maxInfoWidth = (resources.displayMetrics.widthPixels - px(90)).coerceAtLeast(px(260)).coerceAtMost(px(860))
        overlay.addView(info, FrameLayout.LayoutParams(maxInfoWidth, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = px(42); bottomMargin = px(190)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sourcesButton = button("Change source") { showSources() }.also(row::addView)
        row.addView(button("OG Server") { desiredPlay = true; begin(FrostProvider.OG, fresh = true) })
        row.addView(button("TG Server") { desiredPlay = true; begin(FrostProvider.FROST, fresh = true) })
        row.addView(button("Audio & Subtitles") { showTracks() })
        episodesButton = button("Episodes") { showEpisodes() }.also(row::addView)
        nextButton = button("Next episode") { nextEpisode() }.also(row::addView)
        autoNextButton = button("") {
            prefs.edit().putBoolean("vod_auto_next", !prefs.getBoolean("vod_auto_next", true)).apply()
            updateTitle()
        }.also(row::addView)
        row.addView(button("Retry") { desiredPlay = true; begin(provider, fresh = true) })
        row.addView(button("Playback details") { showDetails() })
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(row)
        }
        overlay.addView(scroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = px(36); rightMargin = px(36); bottomMargin = px(104)
        })
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        spinner = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(if (title.isSeries) Accent.BRAND_LIGHT else Accent.BRAND)
        }
        root.addView(spinner, FrameLayout.LayoutParams(px(48), px(48), Gravity.CENTER))
        setContentView(root)
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            setOverlayVisible(visibility == View.VISIBLE || failed || spinner.visibility == View.VISIBLE || actionHasFocus())
        })
        updateTitle()
        view.requestFocus()
    }

    private fun actionHasFocus() = actionButtons.any { it.isFocused && it.isShown }

    private fun setOverlayVisible(visible: Boolean) {
        val keepVisible = visible || actionHasFocus()
        overlay.animate().cancel()
        if (keepVisible) {
            if (overlay.visibility != View.VISIBLE) {
                overlay.alpha = 0f; overlay.translationY = px(12).toFloat(); overlay.visibility = View.VISIBLE
            }
            overlay.animate().alpha(1f).translationY(0f).setDuration(240)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        } else {
            overlay.animate().alpha(0f).translationY(px(10).toFloat()).setDuration(220)
                .withEndAction {
                    if (!failed && spinner.visibility != View.VISIBLE && !actionHasFocus()) {
                        overlay.visibility = View.GONE; overlay.alpha = 1f; overlay.translationY = 0f
                    }
                }.start()
        }
    }

    private fun px(value: Int) = (value * dp).toInt()
    private fun button(label: String, click: () -> Unit): TextView {
        val action = TextView(this).apply {
            text = label; textSize = 14f; setTextColor(Accent.TEXT); gravity = Gravity.CENTER
            isFocusable = true; isClickable = true; setPadding(px(22), px(12), px(22), px(12))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(12) }
            typeface = androidx.core.content.res.ResourcesCompat.getFont(this@MoviePlayerActivity, R.font.sora_semibold)
            fun style(focused: Boolean) {
                // The site's control row: rounded-lg glass, a white ring on focus. "Next" is the one
                // solid button on the row, exactly as Play is on the home hero.
                val primary = label.startsWith("Next")
                background = GradientDrawable().apply {
                    cornerRadius = px(12).toFloat()
                    setColor(if (primary) Accent.TEXT else if (focused) 0x4DFFFFFF else 0x33FFFFFF)
                    setStroke(if (focused && !primary) px(2) else 0, if (focused) 0xF2FFFFFF.toInt() else Color.TRANSPARENT)
                }
                setTextColor(if (primary) Accent.ABYSS else Accent.TEXT)
                elevation = if (focused) px(9).toFloat() else 0f
            }
            style(false)
            setOnFocusChangeListener { v, focused ->
                style(focused)
                v.animate().scaleX(if (focused) 1.045f else 1f).scaleY(if (focused) 1.045f else 1f).setDuration(150).start()
                if (focused) {
                    setOverlayVisible(true)
                    if (!failed) view.showController()
                }
            }
            setOnClickListener { click() }
        }
        actionButtons += action
        return action
    }

    private fun watchTime(positionMs: Long, durationMs: Long): String {
        fun clock(ms: Long): String {
            val total = (ms.coerceAtLeast(0) / 1000)
            val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
        }
        return "${clock(positionMs)}  /  ${clock(durationMs)}"
    }

    private fun updateWatchTime() {
        if (!::timeLine.isInitialized) return
        val owner = player
        val duration = owner?.duration?.takeIf { it > 0 } ?: 0L
        val position = owner?.currentPosition?.coerceAtLeast(0) ?: 0L
        timeLine.text = if (duration > 0) watchTime(position, duration) else watchTime(position, lastDuration)
        timeLine.visibility = if (failed || title.isSeries) View.GONE else View.VISIBLE
    }

    private fun updateTitle() {
        heading.text = titleName.ifBlank { "TMDB ${title.id}" }
        episodeLine.text = if (title.isSeries) "SEASON ${title.season}  ·  EPISODE ${title.episode}" else ""
        episodeLine.visibility = if (title.isSeries) View.VISIBLE else View.GONE
        episodesButton.visibility = if (title.isSeries) View.VISIBLE else View.GONE
        episodesButton.isEnabled = true
        nextButton.visibility = if (title.isSeries) View.VISIBLE else View.GONE
        nextButton.isEnabled = VodProgress.next(title, episodes) != null
        autoNextButton.visibility = if (title.isSeries) View.VISIBLE else View.GONE
        autoNextButton.text = "Auto next: ${if (prefs.getBoolean("vod_auto_next", true)) "On" else "Off"}"
    }

    private fun message(text: String, loading: Boolean = false) {
        status.text = text
        spinner.visibility = if (loading) View.VISIBLE else View.GONE
        setOverlayVisible(true)
        view.showController()
    }

    override fun onStart() {
        super.onStart()
        if (!initialized || isFinishing) return
        foreground = true
        begin(provider)
        loadEpisodes()
        ui.post(tick)
    }

    private fun begin(nextProvider: FrostProvider, selected: FrostSource? = null, fresh: Boolean = false) {
        captureProgress()
        recovery.reset(); sourceRecovery.reset(); softwareRetried.clear(); probed.clear(); diagnostics = PlaybackDiagnostics()
        failed = false; lastFailure = ""; compatible = false; startupAt = SystemClock.elapsedRealtime()
        containerRetried.clear()
        if (selected == null) resolve(nextProvider, fresh = fresh)
        else {
            generation++; lookup?.cancel(true); resolver.cancel()
            provider = nextProvider; recovery.visit(provider)
            sources = listOf(selected) + sources.filter { it !== selected }
            nextSource()
        }
    }

    private fun resolve(
        nextProvider: FrostProvider,
        preferred: String? = null,
        avoidIdentity: String? = null,
        fresh: Boolean = false
    ) {
        captureProgress(); releasePlayer(); dialog?.dismiss()
        provider = nextProvider; recovery.visit(provider); currentSource = null
        val request = title
        val token = ++generation
        lookup?.cancel(true); resolver.cancel()
        diagnostics.resolving(if (provider == FrostProvider.FROST) PlaybackDiagnostics.Provider.FROST else PlaybackDiagnostics.Provider.OUT)
        message("Resolving ${provider.label} · native movie sources…", true)
        lookup = io.submit {
            val result = try { Result.success(resolver.resolve(request, nextProvider, fresh)) } catch (error: Exception) { Result.failure(error) }
            ui.post {
                if (!foreground || token != generation || request != title) return@post
                result.fold(onSuccess = { list ->
                    if (nextProvider == FrostProvider.OG && list.isNotEmpty()) ogCache = list
                    val remembered = prefs.getString("vod_source_${request.progressKey}", null)
                    val favored = preferred ?: remembered?.takeUnless { it == avoidIdentity }
                    val ordered = VodSourceOrder.prioritize(list, favored, avoidIdentity)
                    val first = ordered.firstOrNull()
                    val rememberedFirst = preferred == null && first != null && first.identity == favored
                    if (rememberedFirst || !probed.add(nextProvider)) {
                        // Either the source that played last time is first again, or this provider was
                        // already probed in this session: start immediately.
                        sources = ordered
                        message("Starting ${first?.label.orEmpty()}…", true)
                        nextSource()
                        return@fold
                    }
                    // The website moves past a dead source inside one page load. A native player would
                    // spend a full startup cycle on it, which is what makes the app need a second try.
                    message("Checking native sources · ${minOf(ordered.size, VodPreflight.MAX_CANDIDATES)} of ${ordered.size}…", true)
                    io.submit {
                        val reachable = try { VodPreflight.verify(ordered) } catch (_: Exception) { emptySet() }
                        ui.post {
                            if (!foreground || token != generation || request != title) return@post
                            sources = VodPreflight.rank(ordered, reachable)
                            nextSource()
                        }
                    }
                }, onFailure = { error ->
                    diagnostics.metadataFailed(PlaybackProblems.from(error))
                    val problem = error as? FrostResolveException
                    lastFailure = (problem?.message ?: "Movie source lookup failed.") +
                        if (nextProvider == FrostProvider.OG) "\nOG servers: ${OgNative.lastOutcome()}" else ""
                    when {
                        problem?.status == 429 -> fail(lastFailure)
                        sourceRecovery.retryMetadata(nextProvider, problem?.status, problem?.kind) -> resolve(nextProvider, fresh = true)
                        else -> recovery.alternative()?.let { resolve(it) } ?: fail(lastFailure)
                    }
                })
            }
        }
    }

    private fun forgetFailedSource(source: FrostSource) {
        val key = "vod_source_${title.progressKey}"
        if (prefs.getString(key, null) == source.identity) {
            prefs.edit().remove(key).remove("vod_provider_${title.progressKey}").apply()
        }
    }

    private fun nextSource() {
        if (!foreground || failed) return
        currentSource?.let(::forgetFailedSource)
        captureProgress(); releasePlayer(); dialog?.dismiss()
        if (startupAt == 0L) startupAt = SystemClock.elapsedRealtime()
        if (SystemClock.elapsedRealtime() - startupAt > 45_000) { fail("No picture within 45 seconds. Try another source or retry."); return }
        val next = sources.firstOrNull { recovery.take(it) }
        if (next != null) startSource(next, softwareRetried.contains(next.provider))
        else recovery.alternative()?.let { resolve(it) } ?: fail(lastFailure.ifBlank { "No playable native movie source. Try again later or select another provider." })
    }

    private fun startSource(source: FrostSource, software: Boolean) {
        if (!foreground) return
        captureProgress(); releasePlayer(); dialog?.dismiss()
        currentSource = source; compatible = software; frame = false; frameAt = 0L; framePosition = 0L; sourceConfirmed = false; failed = false
        val attempt = diagnostics.begin(if (source.provider == FrostProvider.FROST) PlaybackDiagnostics.Provider.FROST else PlaybackDiagnostics.Provider.OUT,
            when (source.format) { VodFormat.DASH -> PlaybackDiagnostics.Media.DASH; VodFormat.HLS -> PlaybackDiagnostics.Media.HLS; VodFormat.MP4 -> PlaybackDiagnostics.Media.FILE },
            PlaybackDiagnostics.Drm.NONE, false, software)
        trace = attempt
        message("${source.label}\nStarting native playback${if (software) " · compatible decoder" else ""}…", true)
        attemptAt = SystemClock.elapsedRealtime(); bufferingAt = 0
        try {
            val profile = NativePlaybackPolicy.profile(television, lowMemory, software, if (television) 720 else 1080)
            val selector = FrostMedia.tracks(this, profile, source.language.contains("Hindi", true))
            val owner = ExoPlayer.Builder(this, NativePlaybackPolicy.renderers(this, profile))
                .setTrackSelector(selector).setLoadControl(FrostMedia.buffers(profile))
                .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
            player = owner; view.player = owner
            owner.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            owner.setHandleAudioBecomingNoisy(true)
            owner.addAnalyticsListener(NativePlaybackDiagnostics(attempt))
            owner.addListener(listener(owner, attempt))
            owner.setMediaSource(FrostMedia.create(source), lastPosition)
            owner.playWhenReady = desiredPlay
            if (!desiredPlay) { attemptAt = 0; startupAt = 0; bufferingAt = 0 }
            prepareOnSurface(owner, attempt)
        } catch (error: Exception) {
            attempt.failed(PlaybackProblems.from(error))
            lastFailure = "This movie source could not be opened by the native player."
            ui.post { if (foreground && currentSource === source && !failed) nextSource() }
        }
    }

    private fun prepareOnSurface(owner: ExoPlayer, attempt: PlaybackDiagnostics.Attempt) {
        val surface = view.videoSurfaceView as? SurfaceView
        if (surface == null || surface.holder.surface.isValid) { attempt.surfaceReady(); owner.prepare(); return }
        val holder = surface.holder
        val callback = object : SurfaceHolder.Callback {
            private val gate = SurfaceStartGate(
                active = { foreground && player === owner }, ready = { holder.surface.isValid },
                prepare = {
                    holder.removeCallback(this); surfaceWait = null; attempt.surfaceReady()
                    try { owner.prepare() } catch (error: Exception) {
                        attempt.failed(PlaybackProblems.from(error)); lastFailure = "The video surface or decoder could not start."
                        ui.post { if (player === owner && foreground) nextSource() }
                    }
                })
            override fun surfaceCreated(holder: SurfaceHolder) = gate.signal()
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = gate.signal()
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }
        surfaceWait = holder to callback
        holder.addCallback(callback)
        if (holder.surface.isValid) callback.surfaceCreated(holder)
    }

    private fun listener(owner: ExoPlayer, attempt: PlaybackDiagnostics.Attempt) = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (player !== owner) return
            attempt.state(when (state) { Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_READY -> "READY"; Player.STATE_ENDED -> "ENDED"; else -> "IDLE" })
            if (state == Player.STATE_BUFFERING) { if (bufferingAt == 0L) bufferingAt = SystemClock.elapsedRealtime() } else bufferingAt = 0
            if (state == Player.STATE_READY) {
                if (owner.duration > 0) lastDuration = owner.duration
                if (frame) spinner.visibility = View.GONE
            }
            if (state == Player.STATE_ENDED) {
                prefs.edit().putLong(title.progressKey, 0).putLong(title.progressKey + "_duration", lastDuration).apply()
                lastPosition = 0; frame = false; startupAt = 0; attemptAt = 0
                val next = VodProgress.next(title, episodes)
                if (title.isSeries && prefs.getBoolean("vod_auto_next", true) && next != null) changeEpisode(next)
                else { desiredPlay = false; message("Finished · Retry to replay, or choose another episode.") }
            }
        }
        override fun onRenderedFirstFrame() {
            if (player !== owner) return
            frame = true; frameAt = SystemClock.elapsedRealtime(); framePosition = owner.currentPosition; sourceConfirmed = false
            failed = false; startupAt = 0; attemptAt = 0; bufferingAt = 0
            attempt.frameRendered(); spinner.visibility = View.GONE
            status.text = "Picture received · confirming stream stability · ${currentSource?.label.orEmpty()}"
            view.hideController()
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (player !== owner) return
            val userRequest = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            if (userRequest) desiredPlay = playWhenReady
            if (playWhenReady && owner.playbackState == Player.STATE_BUFFERING) bufferingAt = SystemClock.elapsedRealtime()
            if (!frame && playWhenReady) {
                if (attemptAt == 0L) attemptAt = SystemClock.elapsedRealtime()
                if (startupAt == 0L) startupAt = attemptAt
            } else if (!playWhenReady && userRequest) {
                attemptAt = 0; bufferingAt = 0; if (!frame) startupAt = 0
            }
        }
        override fun onTracksChanged(tracks: Tracks) {
            if (player !== owner) return
            val minimum = if (television) C.FORMAT_EXCEEDS_CAPABILITIES else C.FORMAT_HANDLED
            for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) {
                val groups = tracks.groups.filter { it.type == type }
                if (groups.isNotEmpty() && groups.none { it.isSelected } && groups.all { group ->
                        (0 until group.length).all { group.getTrackSupport(it) < minimum }
                    }) {
                    val kind = if (type == C.TRACK_TYPE_VIDEO) "video" else "audio"
                    lastFailure = "This device cannot select this source's $kind codec or DRM. Trying another native source."
                    ui.post { if (player === owner && foreground) nextSource() }
                    break
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (player !== owner) return
            val problem = PlaybackProblems.from(error, error.errorCode)
            attempt.failed(problem)
            captureProgress()
            lastFailure = "Native playback failed: ${problem.describe()}"
            val source = currentSource ?: return
            forgetFailedSource(source)
            ui.post {
                if (player !== owner || !foreground) return@post
                if (problem.http == 429) {
                    val alternative = recovery.alternative()
                    if (alternative != null) resolve(alternative)
                    else fail("All native movie providers are rate limited (HTTP 429). Please wait before retrying.")
                    return@post
                }
                if (startupAt == 0L) startupAt = SystemClock.elapsedRealtime()
                // A source that dies on its first bytes was labelled wrong, not dead: ask for the same
                // address as the other container before spending the viewer's next provider on it.
                if (problem.http == null && retryOtherContainer(source, problem)) return@post
                when {
                    problem.family == PlaybackDiagnostics.Family.DECODER && retrySoftware(source) -> Unit
                    sourceRecovery.refreshNotFound(source.provider, problem.http) ->
                        resolve(source.provider, avoidIdentity = source.identity, fresh = true)
                    recovery.refresh(source.provider, problem.http) -> resolve(source.provider, preferred = source.identity, fresh = true)
                    else -> nextSource()
                }
            }
        }
    }

    /**
     * The same address, opened as the other container, once.
     *
     * TG OUT has served an HLS master labelled `type: "mp4"`; Media3 then feeds a playlist to the MP4
     * extractor, which rejects it on the first bytes. Without this the app burned the source in under a
     * second and moved on - the viewer saw a stream found and instantly skipped.
     */
    private fun retryOtherContainer(source: FrostSource, problem: PlaybackDiagnostics.Problem): Boolean {
        if (!foreground || failed) return false
        val fast = attemptAt > 0 && SystemClock.elapsedRealtime() - attemptAt <= 4_000
        val parseFailure = (problem.code ?: 0) in 3_001..3_999 ||
            problem.detail.contains("ParserException", true) ||
            problem.detail.contains("UnrecognizedInputFormat", true) ||
            problem.detail.contains("ContainerUnsupported", true)
        if (!fast || !parseFailure) return false
        val other = when (source.format) {
            VodFormat.HLS -> VodFormat.MP4
            VodFormat.MP4 -> VodFormat.HLS
            VodFormat.DASH -> return false
        }
        if (!containerRetried.add("${source.identity}|${source.format.name}")) return false
        lastFailure = "Retrying this source as ${other.name} · the stream was labelled ${source.format.name}."
        ui.post { if (foreground && !failed) startSource(source.withFormat(other), compatible) }
        return true
    }

    private fun retrySoftware(source: FrostSource): Boolean {
        if (!television || compatible || !softwareRetried.add(source.provider)) return false
        startSource(source, true)
        return true
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!foreground) return
            val now = SystemClock.elapsedRealtime()
            val owner = player
            if (!failed && (owner == null || owner.playWhenReady)) {
                val activeSource = currentSource
                if (!sourceConfirmed && frameAt > 0L && activeSource != null && VodSourceConfirmation.shouldRemember(
                        frameRendered = frame,
                        ready = owner?.playbackState == Player.STATE_READY,
                        playing = owner?.isPlaying == true,
                        elapsedSinceFrameMs = now - frameAt,
                        playbackAdvancedMs = (owner?.currentPosition ?: framePosition) - framePosition
                    )) {
                    prefs.edit().putString("vod_source_${title.progressKey}", activeSource.identity)
                        .putString("vod_provider_${title.progressKey}", activeSource.provider.id).apply()
                    sourceConfirmed = true
                    status.text = "Playing · ${activeSource.label}"
                }
                when {
                    startupAt > 0 && now - startupAt >= 45_000 -> fail("No picture within 45 seconds. Retry or choose another provider.")
                    // A source that has not produced a picture in sixteen seconds is buffering, not starting:
                    // move on rather than leave the viewer watching a spinner.
                    !frame && attemptAt > 0 && now - attemptAt >= 16_000 -> {
                        trace?.timeout()
                        lastFailure = "No picture from this movie source within 16 seconds."
                        val source = currentSource
                        if (source == null || trace?.canRetryDecoder() != true || !retrySoftware(source)) nextSource()
                    }
                    frame && bufferingAt > 0 && now - bufferingAt >= 20_000 -> {
                        lastFailure = "The movie stream stopped responding."
                        nextSource()
                    }
                }
            }
            updateWatchTime()
            if (now - savedAt >= 5000) { captureProgress(); savedAt = now }
            ui.postDelayed(this, 1000)
        }
    }

    private fun restoreProgress() {
        lastDuration = prefs.getLong(title.progressKey + "_duration", 0).coerceAtLeast(0)
        lastPosition = VodProgress.resume(prefs.getLong(title.progressKey, 0), lastDuration)
    }
    private fun captureProgress() {
        if (!initialized) return
        val owner = player
        if (owner != null && frame && owner.playbackState != Player.STATE_ENDED) {
            lastPosition = owner.currentPosition.coerceAtLeast(0)
            if (owner.duration > 0) lastDuration = owner.duration
        }
        prefs.edit().putLong(title.progressKey, lastPosition).putLong(title.progressKey + "_duration", lastDuration).apply()
    }

    private fun loadEpisodes() {
        if (!title.isSeries || episodes.isNotEmpty() || episodesLoading) return
        val id = title.id
        episodesLoading = true
        episodeLookup = io.submit {
            val tmdbEps = try { Movies.details("series", id).second.map { it.season to it.episode } } catch (_: Exception) { emptyList() }
            val list = tmdbEps.ifEmpty {
                try { ElfuraClient.episodes(id).map { it.season to it.episode } } catch (_: Exception) { emptyList() }
            }
            ui.post {
                episodesLoading = false
                if (!foreground || isDestroyed || title.id != id) return@post
                episodes = list.distinct().sortedWith(compareBy({ it.first }, { it.second })).take(2000)
                updateTitle()
            }
        }
    }

    private fun nextEpisode() {
        val next = VodProgress.next(title, episodes)
        if (next != null) changeEpisode(next)
        else { loadEpisodes(); Toast.makeText(this, "No following episode in the loaded episode list.", Toast.LENGTH_SHORT).show() }
    }
    private fun changeEpisode(next: FrostTitle) {
        captureProgress(); generation++; lookup?.cancel(true); resolver.cancel(); releasePlayer()
        title = next; currentSource = null; sources = emptyList(); frame = false; desiredPlay = true
        prefs.edit().putInt("last_season_${title.catalogKey}", title.season).putInt("last_ep_${title.catalogKey}", title.episode).apply()
        restoreProgress(); updateTitle(); begin(provider)
    }

    private fun showEpisodes() {
        if (episodes.isEmpty()) { loadEpisodes(); Toast.makeText(this, "Loading the episode list…", Toast.LENGTH_SHORT).show(); return }
        val choices = episodes.map { "Season ${it.first} · Episode ${it.second}" }.toTypedArray()
        val current = episodes.indexOf(title.season to title.episode)
        showDialog(AlertDialog.Builder(this).setTitle("Episodes").setSingleChoiceItems(choices, current) { d, index ->
            d.dismiss(); val episode = episodes[index]; changeEpisode(title.copy(season = episode.first, episode = episode.second))
        }.setNegativeButton("Close", null).create())
    }
    private fun showSources() {
        val labels = mutableListOf(
            "Reload OG · Elfura (native · embed.theogpiratebot.online)",
            "Try TG FROST (native)",
            "Try TG OUT (native)"
        )
        // The OG sources this title already produced stay listable: picking another provider used to replace
        // the list, so the only OG entry disappeared exactly when it was needed again.
        val ogFound = (ogCache.ifEmpty { OgNative.cached(title) }).distinctBy { it.identity }
        val firstFound = labels.size
        labels.addAll(ogFound.mapIndexed { index, source -> "${index + 1}. ${source.label}" })
        val firstSource = labels.size
        val listed = sources.filter { source -> ogFound.none { it.identity == source.identity } }
        labels.addAll(listed.mapIndexed { index, source -> "${index + 1}. ${source.label}" })
        // Native only. The site-server entries and the browser-engine switch were removed with the engine: on a
        // television that engine is what took the app down, so every entry here plays in this player.
        val request = title
        showDialog(AlertDialog.Builder(this).setTitle("Movie source / quality / language / server")
            .setItems(labels.toTypedArray()) { _, index ->
                if (title != request) return@setItems
                desiredPlay = true
                when {
                    index == 0 -> begin(FrostProvider.OG, fresh = true)
                    index == 1 -> begin(FrostProvider.FROST, fresh = true)
                    index == 2 -> begin(FrostProvider.OUT, fresh = true)
                    index >= firstSource -> listed.getOrNull(index - firstSource)?.let { begin(it.provider, it) }
                    else -> ogFound.getOrNull(index - firstFound)?.let { begin(it.provider, it) }
                }
            }.setNegativeButton("Close", null).create())
    }

    private fun showTracks() {
        val owner = player ?: run { showSources(); return }
        showDialog(AlertDialog.Builder(this).setTitle("Native track controls")
            .setItems(arrayOf("Video quality (adaptive stream)", "Audio tracks", "Subtitles", "Other source / language")) { _, index ->
                if (player !== owner) return@setItems
                if (index == 3) { showSources(); return@setItems }
                val type = when (index) { 0 -> C.TRACK_TYPE_VIDEO; 1 -> C.TRACK_TYPE_AUDIO; else -> C.TRACK_TYPE_TEXT }
                if (owner.currentTracks.groups.none { it.type == type }) {
                    Toast.makeText(this, "No selectable tracks of this type in this source.", Toast.LENGTH_LONG).show(); return@setItems
                }
                val label = when (index) { 0 -> "Video quality"; 1 -> "Audio"; else -> "Subtitles" }
                showDialog(TrackSelectionDialogBuilder(this, label, owner, type)
                    .setAllowAdaptiveSelections(type == C.TRACK_TYPE_VIDEO).setShowDisableOption(type == C.TRACK_TYPE_TEXT).build())
            }.setNegativeButton("Close", null).create())
    }
    private fun showDetails() {
        val text = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.MONOSPACE
            setPadding(px(18), px(12), px(18), px(12))
            text = "${AppBuildInfo.label(this@MoviePlayerActivity)}\nTG Frost · native Media3 VOD\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
                "${Build.MANUFACTURER.take(40)} ${Build.MODEL.take(48)} · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}\n" +
                "App process: ${if (android.os.Process.is64Bit()) "64-bit" else "32-bit"}\n" +
                "TMDB ${title.id} · ${title.type} · S${title.season} E${title.episode}\n" +
                "TV reported codec-limit fallback: $television\n" +
                "No Gecko, live offsets or Jio authentication is used.\n" +
                (if (OgNative.lastReport().isNotBlank()) "\nOG servers:\n${OgNative.lastReport()}\n" else "") +
                "\n${diagnostics.report()}"
        }
        showDialog(AlertDialog.Builder(this).setTitle("Movie playback details")
            .setView(ScrollView(this).apply { addView(text) }).setPositiveButton("Close", null).create())
    }
    private fun showDialog(value: Dialog) {
        dialog?.dismiss(); dialog = value
        value.setOnDismissListener { if (dialog === value) dialog = null }
        value.show()
        value.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), -2)
    }

    private fun fail(reason: String) {
        captureProgress(); generation++; lookup?.cancel(true); resolver.cancel(); releasePlayer()
        failed = true; startupAt = 0; attemptAt = 0; bufferingAt = 0
        message("$reason\n${diagnostics.summary()}\nChoose Change source to try another stream, or Retry to reload.")
        view.hideController()
        setOverlayVisible(true)
        sourcesButton.requestFocus()
    }
    private fun releasePlayer() {
        surfaceWait?.let { (holder, callback) -> holder.removeCallback(callback) }; surfaceWait = null
        val old = player; player = null
        if (::view.isInitialized) view.player = null
        old?.release()
        frame = false; frameAt = 0L; framePosition = 0L; sourceConfirmed = false; attemptAt = 0; bufferingAt = 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!initialized) return super.dispatchKeyEvent(event)
        // Dialogs own Back/DPAD while open; otherwise Back on a TV remote closes the whole player.
        if (dialog?.isShowing == true) return super.dispatchKeyEvent(event)
        if (VodPlayerRemoteKeyPolicy.focusedActionOwns(event.keyCode, actionHasFocus()))
            return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
                KeyEvent.KEYCODE_MENU -> { showSources(); return true }
                KeyEvent.KEYCODE_INFO -> { showDetails(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { desiredPlay = true; player?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { desiredPlay = false; player?.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                    player?.let { it.playWhenReady = !it.playWhenReady }; view.showController(); return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { if (title.isSeries) nextEpisode(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { player?.seekBack(); view.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player?.seekForward(); view.showController(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!view.isControllerFullyVisible) {
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) player?.seekBack() else player?.seekForward()
                    view.showController(); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!view.isControllerFullyVisible) {
                    player?.let { it.playWhenReady = !it.playWhenReady }; view.showController(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hideSystemUi() = ImmersiveUi.hide(window)
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUi() }
    override fun onSaveInstanceState(out: Bundle) {
        if (initialized) { captureProgress(); out.putInt("season", title.season); out.putInt("episode", title.episode)
            out.putLong("position", lastPosition); out.putBoolean("play", desiredPlay); out.putString("provider", provider.id) }
        super.onSaveInstanceState(out)
    }
    override fun finish() {
        // Detach the SurfaceView before revealing the detail screen, including on older TV firmware.
        if (initialized) {
            captureProgress(); foreground = false; generation++
            lookup?.cancel(true); resolver.cancel(); dialog?.dismiss(); releasePlayer()
            ui.removeCallbacksAndMessages(null)
        }
        super.finish()
        overridePendingTransition(0, 0)
    }
    override fun onStop() {
        foreground = false; generation++
        if (initialized) captureProgress()
        lookup?.cancel(true); episodeLookup?.cancel(true); episodesLoading = false; resolver.cancel()
        dialog?.dismiss(); releasePlayer(); ui.removeCallbacksAndMessages(null)
        super.onStop()
    }
    override fun onDestroy() { resolver.cancel(); io.shutdownNow(); ui.removeCallbacksAndMessages(null); super.onDestroy() }
}
