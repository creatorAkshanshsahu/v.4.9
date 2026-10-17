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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Native TG Frost VOD. No Gecko session, browser URL scraping or live-TV DRM/token policy. */
class MoviePlayerActivity : Activity() {
    companion object {
        fun open(context: Context, title: Movies.Title, season: Int, episode: Int, episodes: List<Movies.Episode>) {
            context.startActivity(playbackIntent(context, title, season, episode, episodes))
        }
        internal fun playbackIntent(context: Context, title: Movies.Title, season: Int, episode: Int, episodes: List<Movies.Episode>) =
            Intent(context, MoviePlayerActivity::class.java)
                .putExtra("tmdb", title.tmdb).putExtra("type", if (title.isSeries) "tv" else "movie")
                .putExtra("season", if (title.isSeries) season else 0).putExtra("episode", if (title.isSeries) episode else 0)
                .putExtra("name", title.name)
                .putExtra("episodes", (if (title.isSeries) episodes else emptyList()).take(2000)
                    .flatMap { listOf(it.season, it.episode) }.toIntArray())
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
    private var currentSource: FrostSource? = null
    private val recovery = VodRecovery()
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
    private var failed = false
    private var startupAt = 0L
    private var attemptAt = 0L
    private var bufferingAt = 0L
    private var savedAt = 0L
    private var lastFailure = ""

    private lateinit var view: PlayerView
    private lateinit var overlay: LinearLayout
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var nextButton: TextView
    private lateinit var episodesButton: TextView
    private lateinit var autoNextButton: TextView

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        try {
            title = FrostTitle(intent.getIntExtra("tmdb", 0), intent.getStringExtra("type").orEmpty(),
                saved?.getInt("season") ?: intent.getIntExtra("season", 0),
                saved?.getInt("episode") ?: intent.getIntExtra("episode", 0))
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, "This movie or episode has no valid TMDB identity.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        titleName = intent.getStringExtra("name").orEmpty().take(180)
        episodes = VodProgress.episodes(intent.getIntArrayExtra("episodes"))
        desiredPlay = saved?.getBoolean("play", true) ?: true
        restoreProgress()
        saved?.getLong("position")?.let { lastPosition = it.coerceAtLeast(0) }
        buildUi()
        initialized = true
    }

    private fun buildUi() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 5000
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowSubtitleButton(true)
            setShowNextButton(false); setShowPreviousButton(false)
            setKeepContentOnPlayerReset(false)
            setShutterBackgroundColor(Color.BLACK)
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(15), px(22), px(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xE6000000.toInt(), 0xA6000000.toInt(), Color.TRANSPARENT))
        }
        heading = TextView(this).apply { textSize = 21f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); maxLines = 2 }
        status = TextView(this).apply { textSize = 13f; setTextColor(0xFFDDDDDD.toInt()); maxLines = 4; setPadding(0, px(5), 0, px(8)) }
        overlay.addView(heading); overlay.addView(status)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(button("Back") { finish() })
        row.addView(button("Sources") { showSources() })
        row.addView(button("Audio / Subtitles") { showTracks() })
        episodesButton = button("Episodes") { showEpisodes() }.also(row::addView)
        nextButton = button("Next episode") { nextEpisode() }.also(row::addView)
        autoNextButton = button("") {
            prefs.edit().putBoolean("vod_auto_next", !prefs.getBoolean("vod_auto_next", true)).apply()
            updateTitle()
        }.also(row::addView)
        row.addView(button("Retry") { desiredPlay = true; begin(provider) })
        row.addView(button("Details") { showDetails() })
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        overlay.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        spinner = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Accent.SIGNAL) }
        root.addView(spinner, FrameLayout.LayoutParams(px(48), px(48), Gravity.CENTER))
        setContentView(root)
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            overlay.visibility = if (visibility == View.VISIBLE || failed || spinner.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        })
        updateTitle()
        view.requestFocus()
    }

    private fun px(value: Int) = (value * dp).toInt()
    private fun button(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        isFocusable = true; isClickable = true; setPadding(px(16), px(11), px(16), px(11))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(8) }
        fun style(focused: Boolean) {
            background = GradientDrawable().apply { cornerRadius = px(8).toFloat(); setColor(if (focused) Color.WHITE else 0x66333333) }
            setTextColor(if (focused) Color.BLACK else Color.WHITE)
        }
        style(false); setOnFocusChangeListener { _, focused -> style(focused) }; setOnClickListener { click() }
    }

    private fun updateTitle() {
        heading.text = titleName.ifBlank { "TMDB ${title.id}" } + if (title.isSeries) " · S${title.season} E${title.episode}" else ""
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
        overlay.visibility = View.VISIBLE
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

    private fun begin(nextProvider: FrostProvider, selected: FrostSource? = null) {
        captureProgress()
        recovery.reset(); softwareRetried.clear(); diagnostics = PlaybackDiagnostics()
        failed = false; lastFailure = ""; compatible = false; startupAt = SystemClock.elapsedRealtime()
        if (selected == null) resolve(nextProvider)
        else {
            generation++; lookup?.cancel(true); resolver.cancel()
            provider = nextProvider; recovery.visit(provider)
            sources = listOf(selected) + sources.filter { it !== selected }
            nextSource()
        }
    }

    private fun resolve(nextProvider: FrostProvider, preferred: String? = null) {
        captureProgress(); releasePlayer(); dialog?.dismiss()
        provider = nextProvider; recovery.visit(provider); currentSource = null
        val request = title
        val token = ++generation
        lookup?.cancel(true); resolver.cancel()
        diagnostics.resolving(if (provider == FrostProvider.FROST) PlaybackDiagnostics.Provider.FROST else PlaybackDiagnostics.Provider.OUT)
        message("Resolving ${provider.label} · native movie sources…", true)
        lookup = io.submit {
            val result = try { Result.success(resolver.resolve(request, nextProvider)) } catch (error: Exception) { Result.failure(error) }
            ui.post {
                if (!foreground || token != generation || request != title) return@post
                result.fold(onSuccess = { list ->
                    sources = list.sortedBy { if (preferred != null && it.identity == preferred) 0 else 1 }
                    nextSource()
                }, onFailure = { error ->
                    diagnostics.metadataFailed(PlaybackProblems.from(error))
                    val problem = error as? FrostResolveException
                    lastFailure = problem?.message ?: "Movie source lookup failed."
                    if (problem?.status in listOf(401, 403, 429)) fail(lastFailure)
                    else recovery.alternative()?.let { resolve(it) } ?: fail(lastFailure)
                })
            }
        }
    }

    private fun nextSource() {
        if (!foreground || failed) return
        captureProgress(); releasePlayer(); dialog?.dismiss()
        if (startupAt == 0L) startupAt = SystemClock.elapsedRealtime()
        if (SystemClock.elapsedRealtime() - startupAt > 120_000) { fail("No picture within two minutes. Try another source or retry."); return }
        val next = sources.firstOrNull { recovery.take(it) }
        if (next != null) startSource(next, softwareRetried.contains(next.provider))
        else recovery.alternative()?.let { resolve(it) } ?: fail(lastFailure.ifBlank { "No playable native movie source. Try again later or select another provider." })
    }

    private fun startSource(source: FrostSource, software: Boolean) {
        if (!foreground) return
        captureProgress(); releasePlayer(); dialog?.dismiss()
        currentSource = source; compatible = software; frame = false; failed = false
        val attempt = diagnostics.begin(if (source.provider == FrostProvider.FROST) PlaybackDiagnostics.Provider.FROST else PlaybackDiagnostics.Provider.OUT,
            when (source.format) { VodFormat.DASH -> PlaybackDiagnostics.Media.DASH; VodFormat.HLS -> PlaybackDiagnostics.Media.HLS; VodFormat.MP4 -> PlaybackDiagnostics.Media.FILE },
            PlaybackDiagnostics.Drm.NONE, false, software)
        trace = attempt
        message("${source.label}\nStarting native playback${if (software) " · compatible decoder" else ""}…", true)
        attemptAt = SystemClock.elapsedRealtime(); bufferingAt = 0
        try {
            val profile = NativePlaybackPolicy.profile(television, lowMemory, software, if (television) 720 else 1080)
            val selector = NativePlaybackPolicy.tracks(this, profile, source.language.contains("Hindi", true))
            val owner = ExoPlayer.Builder(this, NativePlaybackPolicy.renderers(this, profile))
                .setTrackSelector(selector).setLoadControl(NativePlaybackPolicy.buffers(profile))
                .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
            player = owner; view.player = owner
            owner.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            owner.setHandleAudioBecomingNoisy(true)
            owner.addAnalyticsListener(NativePlaybackDiagnostics(attempt))
            owner.addListener(listener(owner, attempt))
            owner.setMediaSource(FrostMedia.create(source), lastPosition)
            owner.playWhenReady = desiredPlay
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
            frame = true; failed = false; startupAt = 0; attemptAt = 0; bufferingAt = 0
            attempt.frameRendered(); spinner.visibility = View.GONE
            status.text = "Native Media3 · ${currentSource?.label.orEmpty()}"
            view.hideController()
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (player !== owner) return
            desiredPlay = playWhenReady
            if (!frame && playWhenReady) {
                if (attemptAt == 0L) attemptAt = SystemClock.elapsedRealtime()
                if (startupAt == 0L) startupAt = attemptAt
            } else if (!playWhenReady) { attemptAt = 0; bufferingAt = 0; if (!frame) startupAt = 0 }
        }
        override fun onTracksChanged(tracks: Tracks) {
            if (player !== owner) return
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (video.isNotEmpty() && video.none { it.isSelected } && video.all { group ->
                    (0 until group.length).all { group.getTrackSupport(it) < C.FORMAT_EXCEEDS_CAPABILITIES }
                }) {
                lastFailure = "This device cannot select this source's video codec or DRM. Trying another native source."
                ui.post { if (player === owner && foreground) nextSource() }
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            if (player !== owner) return
            val problem = PlaybackProblems.from(error, error.errorCode)
            attempt.failed(problem)
            captureProgress()
            lastFailure = "Native playback failed: ${problem.describe()}"
            val source = currentSource ?: return
            ui.post {
                if (player !== owner || !foreground) return@post
                if (problem.http == 429) { fail("The media source is rate limited. Please wait before retrying."); return@post }
                if (startupAt == 0L) startupAt = SystemClock.elapsedRealtime()
                when {
                    problem.family == PlaybackDiagnostics.Family.DECODER && retrySoftware(source) -> Unit
                    recovery.refresh(source.provider, problem.http) -> resolve(source.provider, source.identity)
                    else -> nextSource()
                }
            }
        }
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
                when {
                    startupAt > 0 && now - startupAt >= 120_000 -> fail("Native movie startup timed out. Retry or choose another provider.")
                    !frame && attemptAt > 0 && now - attemptAt >= 30_000 -> {
                        trace?.timeout()
                        lastFailure = "No picture from this movie source within 30 seconds."
                        val source = currentSource
                        if (source == null || trace?.canRetryDecoder() != true || !retrySoftware(source)) nextSource()
                    }
                    frame && bufferingAt > 0 && now - bufferingAt >= 40_000 -> {
                        lastFailure = "The movie stream stopped responding."
                        nextSource()
                    }
                }
            }
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
            val list = try { Movies.details("series", id).second.map { it.season to it.episode } } catch (_: Exception) { emptyList() }
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
        val labels = mutableListOf("Reload TG FROST (native)", "Try TG OUT (native)")
        labels.addAll(sources.mapIndexed { index, source -> "${index + 1}. ${source.label}" })
        val snapshot = sources
        val request = title
        showDialog(AlertDialog.Builder(this).setTitle("Movie source / quality / language")
            .setItems(labels.toTypedArray()) { _, index ->
                if (title != request) return@setItems
                desiredPlay = true
                when (index) { 0 -> begin(FrostProvider.FROST); 1 -> begin(FrostProvider.OUT)
                    else -> snapshot.getOrNull(index - 2)?.let { begin(it.provider, it) } }
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
            text = "TG Frost · native Media3 VOD\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
                "${Build.MANUFACTURER.take(40)} ${Build.MODEL.take(48)} · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}\n" +
                "TMDB ${title.id} · ${title.type} · S${title.season} E${title.episode}\n" +
                "TV reported codec-limit fallback: $television\n" +
                "No Gecko, live offsets or Jio authentication is used.\n\n${diagnostics.report()}"
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
        message("$reason\n${diagnostics.summary()}\nSources: choose an alternative · Details: device/codec/HTTP report")
    }
    private fun releasePlayer() {
        surfaceWait?.let { (holder, callback) -> holder.removeCallback(callback) }; surfaceWait = null
        val old = player; player = null
        if (::view.isInitialized) view.player = null
        old?.release()
        frame = false; attemptAt = 0; bufferingAt = 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!initialized) return super.dispatchKeyEvent(event)
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

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUi() }
    override fun onSaveInstanceState(out: Bundle) {
        if (initialized) { captureProgress(); out.putInt("season", title.season); out.putInt("episode", title.episode)
            out.putLong("position", lastPosition); out.putBoolean("play", desiredPlay) }
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
