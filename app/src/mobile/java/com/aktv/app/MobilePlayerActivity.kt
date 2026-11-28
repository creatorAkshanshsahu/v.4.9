package com.aktv.app

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.text.TextUtils
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * The mobile edition's touch player. Same stream builders and DRM rules as the TV shelf
 * ([Streams.factory], [XtreamClient.liveMediaSource], ClearKey/Widevine), but driven by fingers:
 * double-tap left/right seeks ∓10s, buttons cover source hop, rotate and picture-in-picture, and
 * VOD resumes where it stopped. Live, VOD and offline files all arrive as intent extras.
 */
class MobilePlayerActivity : AppCompatActivity() {
    data class StreamRef(val url: String, val type: String, val extra: String = "")
    data class LiveCommand(val name: String, val streams: List<StreamRef>)

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_STREAMS = "streams"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_PROVIDER = "provider"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_TMDB = "tmdb"
        private const val EXTRA_VTYPE = "vtype"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_EPS = "episodes"

        fun playLive(context: Context, name: String, command: LiveCommand, provider: String = "", category: String = "") {
            context.startActivity(Intent(context, MobilePlayerActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_STREAMS, encode(command.streams))
                .putExtra(EXTRA_LIVE, true)
                .putExtra(EXTRA_PROVIDER, provider.take(48))
                .putExtra(EXTRA_CATEGORY, category.take(48)))
        }

        internal fun playVod(context: Context, name: String, frost: List<FrostSource>, tmdb: Int = 0,
                             type: String = "movie", season: Int = 0, episode: Int = 0, episodes: String = "") {
            context.startActivity(Intent(context, MobilePlayerActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_STREAMS, encode(refsOf(frost)))
                .putExtra(EXTRA_LIVE, false)
                .putExtra(EXTRA_TMDB, tmdb)
                .putExtra(EXTRA_VTYPE, type.take(8))
                .putExtra(EXTRA_SEASON, season)
                .putExtra(EXTRA_EPISODE, episode)
                .putExtra(EXTRA_EPS, episodes.take(32_000)))
        }

        /** Frost sources flattened for the intent; [playVod] and episode switches share it. */
        internal fun refsOf(frost: List<FrostSource>): List<StreamRef> =
            frost.take(8).map { StreamRef(it.url, it.format.name.lowercase(), frostExtra(it)) }

        /** A [FrostSource] flattened for the intent, rebuilt on the other side for [FrostMedia]. */
        private fun frostExtra(source: FrostSource): String = JSONObject()
            .put("frost", true)
            .put("label", source.label.take(120))
            .put("headers", JSONObject(source.headers))
            .put("identity", source.identity.take(256))
            .put("cred", source.credentialOrigin.take(512))
            .put("format", source.format.name)
            .put("subs", org.json.JSONArray(source.subtitles.take(8).map { track ->
                JSONObject().put("u", track.url.take(2048)).put("m", track.mime.take(64))
                    .put("l", track.label.take(64)).put("g", (track.language ?: "").take(16))
                    .put("d", track.default)
            })).toString()

        fun playFile(context: Context, label: String, path: String) {
            context.startActivity(Intent(context, MobilePlayerActivity::class.java)
                .putExtra(EXTRA_NAME, label)
                .putExtra(EXTRA_FILE, path)
                .putExtra(EXTRA_LIVE, false))
        }

        private fun encode(streams: List<StreamRef>): String =
            streams.take(8).joinToString("\n") {
                "${it.url}\t${it.type}\t${it.extra.replace("\n", " ").take(8_192)}"
            }

        internal fun decode(raw: String): List<StreamRef> =
            raw.lines().mapNotNull { line ->
                val parts = line.split("\t", limit = 3)
                val url = parts.getOrNull(0).orEmpty()
                if (url.isBlank()) null else StreamRef(url, parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty())
            }

        /** Everything [TgStream] carries beyond its URL, flattened so an intent can hold it. */
        fun extraOf(stream: TgStream): String = JSONObject()
            .put("headers", JSONObject(stream.headers))
            .put("cookie", stream.cookie)
            .put("origin", stream.requestOrigin)
            .put("drm", stream.drm?.toString().orEmpty())
            .toString()

        fun guessType(url: String): String {
            val path = url.substringBefore('?').lowercase()
            return when {
                path.contains(".m3u8") -> "hls"
                path.contains(".mpd") -> "dash"
                else -> "mp4"
            }
        }
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var topBar: View? = null
    private var controls: View? = null
    private var seekBar: SeekBar? = null
    private var timeLabel: TextView? = null
    private var subLine: TextView? = null
    private var srcChip: Button? = null
    private var epsButton: Button? = null
    private var status: TextView? = null
    private var errorBox: View? = null
    private var errorMsg: TextView? = null
    private var playPause: Button? = null
    private var streams = emptyList<StreamRef>()
    private var streamIndex = 0
    private var failedStreams = 0
    private var titleName = ""
    private var isLive = false
    private var isFile = false
    private var provider = ""
    private var category = ""
    private var vodTmdb = 0
    private var vodType = "movie"
    private var vodSeason = 0
    private var vodEpisode = 0
    private var episodes = emptyList<MobileVod.Episode>()
    private var resumeMs = 0L
    private var seeking = false
    private var stickyNote = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Video starts full-stage: landscape with the system bars tucked away, like every
        // streaming app. The ⛶ button still drops back to portrait for one-handed browsing.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        runCatching {
            supportActionBar?.hide()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
        titleName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "STREAMR" }
        isLive = intent.getBooleanExtra(EXTRA_LIVE, false)
        provider = intent.getStringExtra(EXTRA_PROVIDER).orEmpty()
        category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        vodTmdb = intent.getIntExtra(EXTRA_TMDB, 0)
        vodType = intent.getStringExtra(EXTRA_VTYPE).orEmpty().ifBlank { "movie" }
        vodSeason = intent.getIntExtra(EXTRA_SEASON, 0)
        vodEpisode = intent.getIntExtra(EXTRA_EPISODE, 0)
        episodes = MobileVod.decodeEpisodes(intent.getStringExtra(EXTRA_EPS).orEmpty())
        val file = intent.getStringExtra(EXTRA_FILE).orEmpty()
        isFile = file.isNotBlank()
        streams = if (isFile) listOf(StreamRef(file, "mp4")) else decode(intent.getStringExtra(EXTRA_STREAMS).orEmpty())
        if (streams.isEmpty()) {
            Toast.makeText(this, "Nothing to play.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        buildUi()
        if (!isLive && !isFile) resumeMs = loadPosition()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply {
            useController = false
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        playerView = view
        frame.addView(view)
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val player = player ?: return true
                val half = (playerView?.width ?: 0) / 2
                if (e.x < half) player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                else player.seekTo(player.currentPosition + 10_000)
                flash(if (e.x < half) "−10s" else "+10s")
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }
        })
        view.setOnTouchListener { _, event -> detector.onTouchEvent(event) }

        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = Glass.card(20, 0x40)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            visibility = View.GONE
        }
        frame.addView(status)

        // The TV watch header, touched-sized: back, title + context line, source chip.
        Glass.density = resources.displayMetrics.density
        val topInner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val back = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "‹"; textSize = 26f
            setTextColor(Color.parseColor("#F5F0E6"))
            setOnClickListener { finish() }
        }
        topInner.addView(back, LinearLayout.LayoutParams(-2, -2))
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        titleCol.addView(TextView(this).apply {
            text = titleName
            setTextColor(Color.parseColor("#F5F0E6")); textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        subLine = TextView(this).apply {
            setTextColor(Color.parseColor("#B9B4C4")); textSize = 13f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(subLine)
        topInner.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        srcChip = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#E8B4B8"))
            background = Glass.card(16, 0x30)
            setOnClickListener { pickSource() }
        }
        topInner.addView(srcChip, LinearLayout.LayoutParams(-2, -2))
        topBar = Glass.frostPanel(topInner, 18, 0x30)
        frame.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = dp(10); rightMargin = dp(10); topMargin = dp(10)
        })

        // Fatal errors get actions, not a dead line of text: retry this source or pick another.
        val errInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        errorMsg = TextView(this).apply {
            setTextColor(Color.parseColor("#F5F0E6")); textSize = 15f
            gravity = Gravity.CENTER
        }
        errInner.addView(errorMsg)
        val errRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        errRow.addView(ctrl("↻ Retry") { failedStreams = 0; hideError(); bindSource(keepPosition = true) })
        errRow.addView(ctrl("⇄ Sources") { pickSource() })
        errInner.addView(errRow)
        errorBox = Glass.frostPanel(errInner, 18, 0x40)
        errorBox?.visibility = View.GONE
        frame.addView(errorBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
            leftMargin = dp(24); rightMargin = dp(24)
        })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        if (!isLive) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            seekBar = SeekBar(this).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            timeLabel = TextView(this).apply {
                setTextColor(Color.parseColor("#8E8A99")); textSize = 12f
                setPadding(dp(8), 0, 0, 0)
            }
            row.addView(seekBar); row.addView(timeLabel)
            bar.addView(row)
            seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false
                    player?.seekTo(bar.progress.toLong() * 1_000L)
                }
            })
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playPause = ctrl("⏸") { togglePlay() }
        buttons.addView(ctrl("−10s") { player?.seekTo((player?.currentPosition ?: 0) - 10_000) })
        buttons.addView(playPause)
        buttons.addView(ctrl("+10s") { player?.seekTo((player?.currentPosition ?: 0) + 10_000) })
        buttons.addView(ctrl("⇄") { pickSource() })
        if (episodes.isNotEmpty() && !isLive && !isFile) {
            epsButton = ctrl("☰ Eps") { pickEpisode() }
            buttons.addView(epsButton)
        }
        buttons.addView(ctrl("💬") { pickTracks() })
        buttons.addView(ctrl("⛶") { toggleRotate() })
        buttons.addView(ctrl("⧉") { enterPip() })
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }
        scroller.addView(buttons, ViewGroup.LayoutParams(-2, -2))
        bar.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        val panel = Glass.frostPanel(bar)
        controls = panel
        frame.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(10)
        })
        setContentView(frame)
        refreshOverlay()
    }

    private fun ctrl(text: String, onTap: () -> Unit): Button =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#E8B4B8"))
            setOnClickListener { onTap() }
        }

    /** A transient note; sticky ones (buffering, lookups) stay until cleared. */
    private fun note(text: String, sticky: Boolean = false, autoMs: Long = 2_200) {
        val status = status ?: return
        status.text = text
        status.visibility = View.VISIBLE
        stickyNote = sticky
        ui.removeCallbacks(hideNote)
        if (!sticky) ui.postDelayed(hideNote, autoMs)
    }

    private fun clearNote() {
        stickyNote = false
        ui.removeCallbacks(hideNote)
        status?.visibility = View.GONE
    }

    private val hideNote = Runnable { if (!stickyNote) status?.visibility = View.GONE }

    private fun flash(text: String) = note(text, autoMs = 700)

    private fun toggleControls() {
        val bar = controls ?: return
        val show = bar.visibility != View.VISIBLE
        bar.visibility = if (show) View.VISIBLE else View.GONE
        topBar?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(text: String) {
        clearNote()
        errorMsg?.text = text
        errorBox?.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorBox?.visibility = View.GONE
    }

    private fun labelOf(ref: StreamRef): String {
        val vod = runCatching { JSONObject(ref.extra).optString("label") }.getOrNull().orEmpty()
        if (vod.isNotBlank()) return vod
        return when (ref.type.lowercase()) {
            "hls" -> "HLS stream"
            "dash" -> "DASH stream"
            "mp4" -> "MP4 file"
            else -> "Stream"
        }
    }

    /** The header's context line mirrors the TV: live badge, S/E, or the running source label. */
    private fun refreshOverlay() {
        val ref = streams.getOrNull(streamIndex)
        subLine?.text = when {
            isFile -> "Offline file"
            isLive -> "🔴 LIVE · " + listOf(provider, category).filter { it.isNotBlank() }
                .joinToString(" · ").ifBlank { "Stream" }
            vodSeason > 0 -> {
                val name = episodes.firstOrNull { it.season == vodSeason && it.episode == vodEpisode }?.name.orEmpty()
                "S${vodSeason} E${vodEpisode}" + (if (name.isNotBlank()) " · $name" else "")
            }
            else -> ref?.let { labelOf(it) }.orEmpty()
        }
        srcChip?.text = "⇄ ${streamIndex + 1}/${streams.size}"
        srcChip?.visibility = if (isFile) View.GONE else View.VISIBLE
    }

    private fun pickEpisode() {
        if (episodes.isEmpty()) return
        val items = episodes.map { ep ->
            "S${ep.season} E${ep.episode} · ${ep.name.ifBlank { "Episode ${ep.episode}" }}" +
                (if (ep.season == vodSeason && ep.episode == vodEpisode) " ✓" else "")
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(titleName)
            .setItems(items) { _, which -> switchEpisode(episodes[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchEpisode(ep: MobileVod.Episode) {
        if (ep.season == vodSeason && ep.episode == vodEpisode) return
        savePosition()
        hideError()
        note("Finding S${ep.season} E${ep.episode}…", sticky = true)
        io.submit {
            val (sources, reason) = MobileVod.resolve(vodType, vodTmdb, ep.season, ep.episode)
            runOnUiThread {
                if (sources.isEmpty()) {
                    note("No playable source" + (if (reason != null) " ($reason)" else ""), autoMs = 4_000)
                    return@runOnUiThread
                }
                vodSeason = ep.season; vodEpisode = ep.episode
                streams = refsOf(sources)
                streamIndex = 0
                failedStreams = 0
                refreshOverlay()
                bindSource(keepPosition = false, startAt = loadPosition())
            }
        }
    }

    private fun nextEpisode(): MobileVod.Episode? {
        val idx = episodes.indexOfFirst { it.season == vodSeason && it.episode == vodEpisode }
        if (idx < 0) return null
        return episodes.drop(idx + 1).firstOrNull { it.season > vodSeason || (it.season == vodSeason && it.episode > vodEpisode) }
    }

    private fun togglePlay() {
        val player = player ?: return
        if (player.isPlaying) player.pause() else player.play()
        playPause?.text = if (player.isPlaying) "⏸" else "▶"
    }

    private fun toggleRotate() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun sourceLabels(): Array<String> = streams.mapIndexed { i, ref ->
        labelOf(ref) + if (i == streamIndex) " ✓" else ""
    }.toTypedArray()

    private fun pickSource() {
        if (streams.size < 2) {
            Toast.makeText(this, "Only one source.", Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Play from")
            .setItems(sourceLabels()) { _, which ->
                if (which != streamIndex) {
                    streamIndex = which
                    failedStreams = 0
                    hideError()
                    refreshOverlay()
                    bindSource(keepPosition = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The TV's track menu: video quality, audio languages, subtitles, or jump to another source. */
    private fun pickTracks() {
        val player = player ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("Tracks")
            .setItems(arrayOf("Video quality", "🔊 Audio tracks", "💬 Subtitles", "⇄ Other source")) { _, index ->
                if (index == 3) { pickSource(); return@setItems }
                val type = when (index) { 0 -> C.TRACK_TYPE_VIDEO; 1 -> C.TRACK_TYPE_AUDIO; else -> C.TRACK_TYPE_TEXT }
                if (player.currentTracks.groups.none { it.type == type }) {
                    Toast.makeText(this, "This source has no selectable tracks of this type.", Toast.LENGTH_LONG).show()
                    return@setItems
                }
                val label = when (index) { 0 -> "Video quality"; 1 -> "Audio"; else -> "Subtitles" }
                runCatching {
                    androidx.media3.ui.TrackSelectionDialogBuilder(this, label, player, type)
                        .setAllowAdaptiveSelections(type == C.TRACK_TYPE_VIDEO)
                        .setShowDisableOption(type == C.TRACK_TYPE_TEXT)
                        .build().show()
                }.onFailure {
                    Toast.makeText(this, "Track list didn't open.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hopSource() {
        if (streams.size < 2) return
        streamIndex = (streamIndex + 1) % streams.size
        failedStreams = 0
        refreshOverlay()
        bindSource(keepPosition = true)
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Picture-in-picture needs Android 8+.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val ratio = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) Rational(16, 9) else Rational(9, 16)
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build())
        } catch (_: Exception) {
            Toast.makeText(this, "Picture-in-picture isn't allowed.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && !isInPictureInPictureMode) runCatching { enterPip() }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        controls?.visibility = visibility
        topBar?.visibility = visibility
    }

    override fun onStart() {
        super.onStart()
        startPlayer()
        ui.post(progressTick)
    }

    override fun onStop() {
        ui.removeCallbacks(progressTick)
        ui.removeCallbacks(hideNote)
        savePosition()
        releasePlayer()
        super.onStop()
    }

    private fun startPlayer() {
        val player = ExoPlayer.Builder(this).build()
        this.player = player
        playerView?.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        failedStreams = 0
                        if (stickyNote) clearNote()
                    }
                    Player.STATE_BUFFERING -> note("Buffering…", sticky = true)
                    Player.STATE_ENDED -> {
                        if (isLive || isFile) return
                        val next = nextEpisode()
                        if (next != null) {
                            clearPosition()
                            note("Playing next episode…", sticky = true)
                            switchEpisode(next)
                        } else {
                            clearPosition()
                            note("Finished.")
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                failedStreams++
                if (failedStreams <= streams.size && streams.size > 1) {
                    streamIndex = (streamIndex + 1) % streams.size
                    refreshOverlay()
                    note("Trying source ${streamIndex + 1} of ${streams.size}…", sticky = true)
                    bindSource(keepPosition = true)
                } else {
                    showError("This couldn't play (error ${error.errorCode}).")
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                playPause?.text = if (playing) "⏸" else "▶"
            }
        })
        bindSource(keepPosition = resumeMs > 0)
        player.prepare()
        player.playWhenReady = true
    }

    private fun bindSource(keepPosition: Boolean, startAt: Long = -1L) {
        val player = player ?: return
        val ref = streams.getOrElse(streamIndex) { return }
        hideError()
        refreshOverlay()
        val position = when {
            startAt >= 0 -> startAt
            keepPosition -> player.currentPosition.coerceAtLeast(if (resumeMs > 0 && player.currentPosition == 0L) resumeMs else 0L)
            else -> 0L
        }
        resumeMs = 0L
        try {
            player.setMediaSource(buildSource(ref))
            player.prepare()
            if (position > 0 && !isLive) player.seekTo(position)
            if (!isFile) note("Source ${streamIndex + 1} of ${streams.size} · ${labelOf(ref)}")
        } catch (e: Exception) {
            showError("This couldn't play (${MobileErrors.why(e)}).")
        }
    }

    /**
     * Mirrors the TV shelf's source builder: per-source headers/cookies, DASH subtitles,
     * ClearKey (embedded or provider-hosted) and Widevine. The two must not drift: a stream that
     * plays on the TV must play here, byte for byte the same request.
     */
    private fun buildSource(ref: StreamRef): MediaSource {
        if (isFile) {
            val item = MediaItem.fromUri(Uri.fromFile(File(ref.url)))
            return ProgressiveMediaSource.Factory(FileDataSource.Factory()).createMediaSource(item)
        }
        val extra = runCatching { JSONObject(ref.extra) }.getOrElse { JSONObject() }
        if (extra.optBoolean("frost", false)) {
            // VOD goes through the TV shelf's own builder: per-source headers/cookies, container
            // mime and subtitle tracks. Rebuilding FrostSource here is what keeps the two players
            // from drifting — a source that plays on the TV plays here with identical requests.
            val headers = LinkedHashMap<String, String>()
            extra.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it] = h.optString(it) } }
            val subs = ArrayList<FrostSubtitle>()
            extra.optJSONArray("subs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val track = arr.optJSONObject(i) ?: continue
                    subs += FrostSubtitle(track.optString("u"), track.optString("l"),
                        track.optString("g").ifBlank { null }, track.optString("m"), track.optBoolean("d", false))
                }
            }
            val format = runCatching { VodFormat.valueOf(extra.optString("format")) }.getOrElse { VodFormat.HLS }
            val source = FrostSource(FrostProvider.FROST, extra.optString("identity").ifBlank { ref.url },
                ref.url, format, "", "", null, headers, subs, extra.optString("cred").ifBlank { ref.url }, "")
            if (source.credentialOrigin == XtreamClient.XTREAM_ORIGIN_TAG) {
                return XtreamClient.vodMediaSource(source.url, source.identity)
            }
            return FrostMedia.create(source)
        }
        val headers = LinkedHashMap<String, String>()
        extra.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it] = h.optString(it) } }
        val cookie = extra.optString("cookie")
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        val data = DefaultHttpDataSource.Factory()
            .setUserAgent(TgHttp.USER_AGENT)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)
        val factory: MediaSource.Factory = when (ref.type) {
            "hls" -> HlsMediaSource.Factory(data).setLoadErrorHandlingPolicy(NativeLoadErrorPolicy())
            "mp4" -> ProgressiveMediaSource.Factory(data).setLoadErrorHandlingPolicy(NativeLoadErrorPolicy())
            else -> DashMediaSource.Factory(data).setLoadErrorHandlingPolicy(NativeLoadErrorPolicy())
        }
        val item = MediaItem.Builder().setUri(ref.url)
            .setMimeType(when (ref.type) {
                "hls" -> MimeTypes.APPLICATION_M3U8
                "mp4" -> MimeTypes.VIDEO_MP4
                else -> MimeTypes.APPLICATION_MPD
            })
        val drmRaw = extra.optString("drm")
        if (drmRaw.isNotBlank()) {
            val drm = runCatching { JSONObject(drmRaw) }.getOrNull()
            val license = drm?.let { ClearKey.license(it) }
            if (license != null && drm != null) {
                (factory as? DashMediaSource.Factory)?.setManifestParser(ClearKeyDashManifestParser(drm))
                val manager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(true).setPlayClearSamplesWithoutKeys(true)
                    .build(LocalMediaDrmCallback(license))
                factory.setDrmSessionManagerProvider { manager }
            } else if (drm != null) {
                val type = drm.optString("type")
                val licenseUrl = drm.optString("licenseUrl", drm.optString("license_url"))
                when {
                    type.contains("clear", true) && licenseUrl.startsWith("https://") -> {
                        val manager = DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true).setPlayClearSamplesWithoutKeys(true)
                            .build(ClearKeyLicenseCallback(licenseUrl, keyHeaders(headers)))
                        factory.setDrmSessionManagerProvider { manager }
                    }
                    !type.equals("widevine", true) || !licenseUrl.startsWith("https://") ->
                        throw IOException("TG TV did not provide usable DRM for this source")
                    else -> {
                        val drmHeaders = HashMap<String, String>()
                        drm.optJSONObject("headers")?.let { h -> h.keys().forEach { drmHeaders[it] = h.optString(it) } }
                        item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(licenseUrl).setLicenseRequestHeaders(drmHeaders).build())
                    }
                }
            }
        }
        return factory.createMediaSource(item.build())
    }

    private fun keyHeaders(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        headers.forEach { (name, value) ->
            if (!name.equals("Cookie", true) && !name.equals("Origin", true) && !name.equals("Referer", true)) out[name] = value
        }
        if (out.keys.none { it.equals("User-Agent", true) }) out["User-Agent"] = TgHttp.USER_AGENT
        return out
    }

    private fun updateProgress() {
        val player = player ?: return
        if (isLive || seeking) return
        val duration = player.duration.coerceAtLeast(0L)
        val position = player.currentPosition.coerceAtLeast(0L)
        if (duration > 0) {
            seekBar?.max = (duration / 1_000).toInt()
            seekBar?.progress = (position / 1_000).toInt()
            timeLabel?.text = "${fmt(position)} / ${fmt(duration)}"
        }
    }

    private fun fmt(ms: Long): String {
        val total = ms / 1_000
        val hours = total / 3_600
        val minutes = (total % 3_600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    /** Resume is per title AND episode: every episode resuming at the same spot was wrong. */
    private fun positionKey(): String =
        if (vodTmdb > 0) "vod_${vodType}_${vodTmdb}_${vodSeason}_${vodEpisode}"
        else "pos_" + abs(titleName.hashCode()).toString(16)

    private fun loadPosition(): Long =
        getSharedPreferences("aktv_mobile", Context.MODE_PRIVATE).getLong(positionKey(), 0L)

    private fun savePosition() {
        if (isLive || isFile) return
        val player = player ?: return
        val position = player.currentPosition
        val duration = player.duration
        if (position > 10_000 && (duration <= 0 || position < duration - 30_000)) {
            getSharedPreferences("aktv_mobile", Context.MODE_PRIVATE).edit().putLong(positionKey(), position).apply()
        } else {
            clearPosition()
        }
    }

    private fun clearPosition() {
        getSharedPreferences("aktv_mobile", Context.MODE_PRIVATE).edit().remove(positionKey()).apply()
    }

    private fun releasePlayer() {
        playerView?.player = null
        runCatching { player?.release() }
        player = null
    }
}
