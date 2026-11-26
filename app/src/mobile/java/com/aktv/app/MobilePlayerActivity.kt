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
import android.widget.Button
import android.widget.FrameLayout
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
        private const val EXTRA_SUBS = "subs"

        fun playLive(context: Context, name: String, command: LiveCommand) {
            context.startActivity(Intent(context, MobilePlayerActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_STREAMS, encode(command.streams))
                .putExtra(EXTRA_LIVE, true))
        }

        fun playVod(context: Context, name: String, streams: List<StreamRef>, subs: List<Pair<String, String>> = emptyList()) {
            context.startActivity(Intent(context, MobilePlayerActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_STREAMS, encode(streams))
                .putExtra(EXTRA_LIVE, false)
                .putExtra(EXTRA_SUBS, subs.joinToString("\n") { "${it.first}\t${it.second}" }.take(8_192)))
        }

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
    private var controls: LinearLayout? = null
    private var seekBar: SeekBar? = null
    private var timeLabel: TextView? = null
    private var status: TextView? = null
    private var playPause: Button? = null
    private var streams = emptyList<StreamRef>()
    private var streamIndex = 0
    private var failedStreams = 0
    private var titleName = ""
    private var isLive = false
    private var isFile = false
    private var resumeMs = 0L
    private var seeking = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titleName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "STREAMR" }
        isLive = intent.getBooleanExtra(EXTRA_LIVE, false)
        val file = intent.getStringExtra(EXTRA_FILE).orEmpty()
        isFile = file.isNotBlank()
        streams = if (isFile) listOf(StreamRef(file, "mp4")) else decode(intent.getStringExtra(EXTRA_STREAMS).orEmpty())
        if (streams.isEmpty()) {
            Toast.makeText(this, "Nothing to play.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        buildUi()
        if (!isLive) {
            val key = "pos_" + abs(titleName.hashCode()).toString(16)
            resumeMs = getSharedPreferences("aktv_mobile", Context.MODE_PRIVATE).getLong(key, 0L)
        }
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
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)
            visibility = View.GONE
        }
        frame.addView(status)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(180, 10, 6, 16))
            setPadding(dp(12), dp(8), dp(12), dp(12))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        }
        controls = bar
        val name = TextView(this).apply {
            text = titleName
            setTextColor(Color.parseColor("#F5F0E6")); textSize = 14f
            maxLines = 1
        }
        bar.addView(name)
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
            gravity = Gravity.CENTER
        }
        playPause = ctrl("⏸") { togglePlay() }
        buttons.addView(ctrl("−10s") { player?.seekTo((player?.currentPosition ?: 0) - 10_000) })
        buttons.addView(playPause)
        buttons.addView(ctrl("+10s") { player?.seekTo((player?.currentPosition ?: 0) + 10_000) })
        buttons.addView(ctrl("⇄") { hopSource() })
        buttons.addView(ctrl("⛶") { toggleRotate() })
        buttons.addView(ctrl("⧉") { enterPip() })
        bar.addView(buttons)
        frame.addView(bar)
        setContentView(frame)
    }

    private fun ctrl(text: String, onTap: () -> Unit): Button =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#E8B4B8"))
            setOnClickListener { onTap() }
        }

    private fun flash(text: String) {
        val status = status ?: return
        status.text = text
        status.visibility = View.VISIBLE
        ui.removeCallbacks(hideFlash)
        ui.postDelayed(hideFlash, 700)
    }

    private val hideFlash = Runnable { status?.visibility = View.GONE }

    private fun toggleControls() {
        val bar = controls ?: return
        bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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

    private fun hopSource() {
        if (streams.size < 2) {
            Toast.makeText(this, "Only one source.", Toast.LENGTH_SHORT).show()
            return
        }
        streamIndex = (streamIndex + 1) % streams.size
        failedStreams = 0
        Toast.makeText(this, "Source ${streamIndex + 1} of ${streams.size}", Toast.LENGTH_SHORT).show()
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
        controls?.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        startPlayer()
        ui.post(progressTick)
    }

    override fun onStop() {
        ui.removeCallbacks(progressTick)
        ui.removeCallbacks(hideFlash)
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
                if (state == Player.STATE_READY) failedStreams = 0
                if (state == Player.STATE_ENDED && !isLive) {
                    clearPosition()
                    Toast.makeText(this@MobilePlayerActivity, "Finished.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                failedStreams++
                if (failedStreams <= streams.size && streams.size > 1) {
                    streamIndex = (streamIndex + 1) % streams.size
                    Toast.makeText(this@MobilePlayerActivity, "Trying source ${streamIndex + 1}…", Toast.LENGTH_SHORT).show()
                    bindSource(keepPosition = true)
                } else {
                    status?.text = "This couldn't play (error ${error.errorCode})."
                    status?.visibility = View.VISIBLE
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

    private fun bindSource(keepPosition: Boolean) {
        val player = player ?: return
        val ref = streams.getOrElse(streamIndex) { return }
        val position = if (keepPosition) player.currentPosition.coerceAtLeast(if (resumeMs > 0 && player.currentPosition == 0L) resumeMs else 0L) else 0L
        resumeMs = 0L
        try {
            player.setMediaSource(buildSource(ref))
            player.prepare()
            if (position > 0 && !isLive) player.seekTo(position)
        } catch (e: Exception) {
            status?.text = "This couldn't play (${e.message.orEmpty().take(80)})."
            status?.visibility = View.VISIBLE
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
        val subs = intent.getStringExtra(EXTRA_SUBS).orEmpty().lines().mapNotNull { line ->
            val parts = line.split("\t")
            val url = parts.getOrNull(0).orEmpty()
            if (url.isBlank()) null else url to parts.getOrNull(1).orEmpty().ifBlank { "en" }
        }.take(8)
        if (subs.isNotEmpty()) {
            item.setSubtitleConfigurations(subs.mapIndexed { i, (url, lang) ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                    .setMimeType(if (url.contains(".srt")) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                    .setLanguage(lang)
                    .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            })
        }
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

    private fun positionKey(): String = "pos_" + abs(titleName.hashCode()).toString(16)

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
