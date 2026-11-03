package com.aktv.app

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/** Actual advertised renditions, not a toast pretending that an unavailable 540p track exists. */
internal data class VideoRendition(val key: String, val width: Int, val height: Int, val fps: Float, val bitrate: Int, val mime: String) {
    private val rate get() = if (fps > 0 && fps.isFinite()) fps.toDouble() else 30.0
    val pixelRate get() = width.coerceAtLeast(1).toDouble() * height.coerceAtLeast(1) * rate
    val label get() = "${height}p" + (if (fps > 0) " · ${kotlin.math.round(fps).toInt()} fps" else "") +
        (if (bitrate > 0) " · ${String.format(java.util.Locale.US, "%.1f", bitrate / 1_000_000f)} Mbps" else "") +
        when (mime) { "video/avc" -> " · AVC"; "video/hevc" -> " · HEVC"; else -> "" }
}

internal object StableRenditions {
    /** Start near the device ceiling, using a lower-rate option at that size where one exists. */
    fun initial(tracks: List<VideoRendition>, ceiling: Int): VideoRendition? {
        if (tracks.isEmpty()) return null
        val within = tracks.filter { it.height in 1..ceiling }
        val height = within.maxOfOrNull { it.height } ?: tracks.minOf { it.height }
        val sameSize = (if (within.isNotEmpty()) within else tracks).filter { it.height == height }
        return sameSize.minWithOrNull(compareBy<VideoRendition> { if (it.fps > 0) it.fps else 30f }
            .thenBy { if (it.bitrate > 0) it.bitrate else Int.MAX_VALUE }.thenBy { it.pixelRate })
    }
    /** Let normal bandwidth selection start playback, then hold that size or a cheaper option. */
    fun started(current: VideoRendition?, tracks: List<VideoRendition>, ceiling: Int): VideoRendition? {
        if (current == null) return null
        return initial(tracks.filter { it.mime == current.mime }.ifEmpty { tracks }, minOf(ceiling, current.height))
    }
    fun lower(current: VideoRendition, tracks: List<VideoRendition>): VideoRendition? {
        val cheaper = tracks.filter { it.key != current.key &&
            !(it.width == current.width && it.height == current.height && it.mime == current.mime && it.bitrate == current.bitrate &&
                (it.fps <= 0 || current.fps <= 0 || kotlin.math.abs(it.fps - current.fps) < 1f)) &&
            it.height <= current.height && (it.pixelRate < current.pixelRate * 0.92 ||
            (it.pixelRate <= current.pixelRate && it.bitrate > 0 && current.bitrate > 0 && it.bitrate < current.bitrate * 0.8)) }
        val sameCodec = cheaper.filter { it.mime == current.mime }
        // A lower HEVC bitrate does not prove lower decode load than an already-working AVC track.
        val candidates = sameCodec.ifEmpty { cheaper.filter { current.mime == "video/hevc" && it.mime == "video/avc" && it.pixelRate < current.pixelRate } }
        return candidates.maxWithOrNull(compareBy<VideoRendition> { it.pixelRate }.thenBy { it.bitrate })
    }

    /** Keep the chosen ceiling on refresh; if the provider removes it, return its actual minimum. */
    fun keep(lock: VideoRendition, tracks: List<VideoRendition>): VideoRendition? {
        tracks.firstOrNull { it.key == lock.key }?.let { return it }
        val sameCodec = tracks.filter { it.mime == lock.mime }.ifEmpty { tracks }
        val eligible = sameCodec.filter { it.height <= lock.height && it.pixelRate <= lock.pixelRate * 1.05 &&
            (lock.bitrate <= 0 || it.bitrate <= 0 || it.bitrate <= lock.bitrate * 1.15) }
        return eligible.maxWithOrNull(compareBy<VideoRendition> { it.height }.thenBy { it.pixelRate })
            ?: sameCodec.minByOrNull { it.pixelRate }
    }
}

/** Dropped-frame callbacks have variable lengths. Require sustained load, not three arbitrary callbacks. */
internal class FrameDropWindow(private val settleMs: Long = 6000, private val cooldownMs: Long = 10_000) {
    private var ignoreUntil = 0L
    private var badMs = 0L
    private var drops = 0
    fun reset(now: Long) { ignoreUntil = now + settleMs; badMs = 0; drops = 0 }
    fun settledChange(now: Long) { ignoreUntil = now + cooldownMs; badMs = 0; drops = 0 }
    fun observe(now: Long, dropped: Int, elapsedMs: Long, playingReady: Boolean): Boolean {
        if (!playingReady || now < ignoreUntil || elapsedMs <= 0 || dropped <= 0) return false
        if (dropped * 1000.0 / elapsedMs >= 4.0) { badMs += elapsedMs.coerceAtMost(4000); drops += dropped }
        else { badMs = (badMs - elapsedMs).coerceAtLeast(0); drops = (drops - dropped).coerceAtLeast(0) }
        if (badMs < 5000 || drops < 20) return false
        settledChange(now)
        return true
    }
}

/** One controller per player instance: old codec callbacks cannot lower a newly selected channel. */
internal class NativePictureController(
    private val owner: ExoPlayer,
    private val selector: DefaultTrackSelector,
    private val isCurrent: () -> Boolean,
    private val allowReportedExceedance: Boolean,
    initialCeiling: Int,
    stable: Boolean,
    private val remember: (VideoRendition) -> Unit,
    private val notice: (String) -> Unit
) : Player.Listener, AnalyticsListener {
    private data class Track(val group: Tracks.Group, val index: Int, val rendition: VideoRendition)
    private val drops = FrameDropWindow().apply { reset(SystemClock.elapsedRealtime()) }
    private var ceiling = initialCeiling
    private var fixed = stable
    private var lock: VideoRendition? = null
    private var exhaustedNotice = false
    private var bufferedAt = 0L
    private var hadFrame = false
    private var nextBufferStep = 0L
    var bufferingCount = 0
        private set
    var droppedTotal = 0
        private set

    private fun tracks(): List<Track> = owner.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
        (0 until group.length).mapNotNull { index ->
            val support = group.getTrackSupport(index)
            if (support != C.FORMAT_HANDLED && !(allowReportedExceedance && support == C.FORMAT_EXCEEDS_CAPABILITIES)) return@mapNotNull null
            val f = group.getTrackFormat(index)
            if (f.height <= 0 || f.width <= 0) return@mapNotNull null
            Track(group, index, rendition(f))
        }
    }
    private fun rendition(f: Format) = VideoRendition(
        "${f.id}|${f.sampleMimeType}|${f.width}|${f.height}|${f.frameRate}|${f.bitrate}",
        f.width, f.height, f.frameRate, f.bitrate, f.sampleMimeType.orEmpty())

    fun choices(): List<VideoRendition> = tracks().map { it.rendition }.distinctBy { it.key }
        .sortedWith(compareByDescending<VideoRendition> { it.height }.thenByDescending { it.fps }.thenByDescending { it.bitrate })
    fun modeLabel(): String = lock?.let { "Stable · ${it.height}p" } ?: if (fixed) "Stable · waiting for tracks" else "Auto quality"
    fun details(): String = "Picture policy: ${modeLabel()}\nDropped frames observed: $droppedTotal; buffering events: $bufferingCount\n" +
        "Actual rendition: ${owner.videoFormat?.let { rendition(it).label } ?: "not selected"}"

    fun automatic(newCeiling: Int = ceiling) {
        if (!isCurrent()) return
        fixed = false; lock = null; exhaustedNotice = false; ceiling = newCeiling
        selector.setParameters(selector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_VIDEO))
        drops.reset(SystemClock.elapsedRealtime())
    }
    fun stable() {
        if (!isCurrent()) return
        fixed = true; lock = owner.videoFormat?.let(::rendition)
        applyLock()
    }
    fun select(choice: VideoRendition) {
        if (!isCurrent()) return
        fixed = true; lock = choice; ceiling = choice.height; exhaustedNotice = false
        applyLock(); remember(choice); drops.settledChange(SystemClock.elapsedRealtime())
    }
    private fun applyLock() {
        if (!isCurrent() || !fixed) return
        val available = tracks()
        val selectedGroup = available.filter { it.group.isTrackSelected(it.index) }.ifEmpty { available }
        val choice = lock?.let { StableRenditions.keep(it, available.map { item -> item.rendition }) }
            ?: StableRenditions.started(owner.videoFormat?.let(::rendition), selectedGroup.map { it.rendition }, ceiling) ?: return
        val target = available.firstOrNull { it.rendition == choice } ?: return
        val prior = lock
        if (prior != null && choice.pixelRate > prior.pixelRate * 1.1)
            notice("The source changed its offered tracks. Using its lowest available rendition: ${choice.label}.")
        lock = choice
        val current = selector.parameters.overrides[target.group.mediaTrackGroup]
        if (current?.trackIndices?.toList() == listOf(target.index)) return
        selector.setParameters(selector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setOverrideForType(TrackSelectionOverride(target.group.mediaTrackGroup, listOf(target.index))))
    }
    override fun onTracksChanged(tracks: Tracks) { if (isCurrent()) applyLock() }
    override fun onRenderedFirstFrame() { if (isCurrent()) { hadFrame = true; applyLock() } }
    fun checkBuffering(now: Long) {
        if (!isCurrent() || !fixed || !hadFrame || !owner.playWhenReady || bufferedAt == 0L ||
            now - bufferedAt < 5000 || now < nextBufferStep) return
        nextBufferStep = now + 15_000
        stepDown("buffering")
    }
    override fun onPlaybackStateChanged(state: Int) {
        if (!isCurrent()) return
        if (state == Player.STATE_BUFFERING) { if (bufferedAt == 0L) { bufferingCount++; bufferedAt = SystemClock.elapsedRealtime() } }
        else bufferedAt = 0L
    }
    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        if (!isCurrent()) return
        droppedTotal += droppedFrames
        if (!drops.observe(SystemClock.elapsedRealtime(), droppedFrames, elapsedMs, owner.playbackState == Player.STATE_READY && owner.isPlaying)) return
        stepDown("dropped frames")
    }
    private fun stepDown(reason: String) {
        if (!isCurrent()) return
        drops.settledChange(SystemClock.elapsedRealtime())
        val available = tracks().map { it.rendition }
        val current = owner.videoFormat?.let(::rendition) ?: return
        val lower = StableRenditions.lower(current, available)
        fixed = true
        if (lower != null) {
            lock = lower; ceiling = lower.height; exhaustedNotice = false
            applyLock(); remember(lower)
            nextBufferStep = SystemClock.elapsedRealtime() + 15_000
            notice("Stable picture · ${lower.label} after $reason. Quality will stay here instead of climbing back up.")
        } else {
            lock = available.filter { it.mime == current.mime }.ifEmpty { available }.minByOrNull { it.pixelRate } ?: return
            applyLock(); lock?.let(remember)
            if (!exhaustedNotice) {
                exhaustedNotice = true
                notice("Holding the lowest available rendition (${lock!!.height}p). If stutter continues, try another source; no lower track is supplied here.")
            }
        }
    }
}
