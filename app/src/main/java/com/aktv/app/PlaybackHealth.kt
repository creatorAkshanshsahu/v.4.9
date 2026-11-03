package com.aktv.app

import java.util.concurrent.atomic.AtomicLong

/** Monotonic, per-player health. READY/audio alone is not proof that a TV has displayed a picture. */
internal class PlaybackHealth(
    private val startedAt: Long,
    private val expectsVideo: Boolean = true,
    private val firstFrameTimeoutMs: Long = 15_000,
    private val stallTimeoutMs: Long = 12_000
) {
    enum class State { IDLE, BUFFERING, READY, ENDED }
    enum class Failure { NO_PICTURE, STARTUP, BUFFERING, VIDEO_STOPPED, ENDED }
    data class Result(val failure: Failure? = null, val stable: Boolean = false)

    var hasVideoFrame: Boolean = false
        private set
    private val lastVideoProgress = AtomicLong(Long.MIN_VALUE)
    private val videoFrames = AtomicLong(0)
    private var stableFrames = 0L
    private var bufferSince: Long? = null
    private var stableSince: Long? = null
    private var stablePosition = 0L
    private var reportedStable = false
    private var playbackStarted = false

    fun videoProgress(now: Long) { lastVideoProgress.set(now); videoFrames.incrementAndGet() }
    fun firstFrame(now: Long) { hasVideoFrame = true; videoProgress(now) }

    fun sample(now: Long, state: State, playing: Boolean, positionMs: Long, wantsPlayback: Boolean = true): Result {
        if (!wantsPlayback) { bufferSince = null; stableSince = null; return Result() }
        if (state == State.ENDED) return Result(Failure.ENDED)
        if (hasVideoFrame || (!expectsVideo && state == State.READY)) playbackStarted = true
        if (!playbackStarted) {
            stableSince = null
            // This deadline never moves when BUFFERING and READY alternate.
            return if (now - startedAt >= firstFrameTimeoutMs)
                Result(if (expectsVideo) Failure.NO_PICTURE else Failure.STARTUP) else Result()
        }
        if (state == State.BUFFERING) {
            stableSince = null
            val since = bufferSince ?: now.also { bufferSince = it }
            return if (now - since >= stallTimeoutMs) Result(Failure.BUFFERING) else Result()
        }
        bufferSince = null
        val frameAt = lastVideoProgress.get()
        val recentPicture = !expectsVideo || (hasVideoFrame && frameAt != Long.MIN_VALUE && now - frameAt <= 3_000)
        if (expectsVideo && frameAt != Long.MIN_VALUE && now - frameAt >= stallTimeoutMs)
            return Result(Failure.VIDEO_STOPPED)
        if (!playing || state != State.READY || !recentPicture) {
            stableSince = null
            return Result()
        }
        val since = stableSince ?: now.also { stableSince = it; stablePosition = positionMs; stableFrames = videoFrames.get() }
        val progressed = if (expectsVideo) videoFrames.get() > stableFrames + 1 else positionMs - stablePosition >= 1_000
        if (!reportedStable && now - since >= 30_000 && progressed) {
            reportedStable = true
            return Result(stable = true)
        }
        return Result()
    }
}

/** A whole tuning/recovery cycle has one deadline, including metadata, source and decoder retries. */
internal class PlaybackStartupBudget(private val timeoutMs: Long = 60_000) {
    private var deadline: Long? = null
    fun start(now: Long) { deadline = now + timeoutMs }
    fun recover(now: Long) { if (deadline == null) start(now) }
    fun rendered() { deadline = null }
    fun clear() { deadline = null }
    fun expired(now: Long) = deadline?.let { now >= it } ?: false
}
