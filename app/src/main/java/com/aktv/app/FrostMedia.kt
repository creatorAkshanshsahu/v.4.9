package com.aktv.app

import android.net.Uri
import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** VOD source construction must never inherit the live player's target offset or channel DRM. */
internal object FrostMedia {
    fun tracks(context: Context, profile: NativePlaybackPolicy.Profile, hindi: Boolean) =
        NativePlaybackPolicy.tracks(context, profile, hindi).apply {
            // Movie aspect ratios are not necessarily 16:9 (the sampled 1080-line film is 2580px wide).
            // Limit decoded height/bitrate and use the real viewport, not a live-TV width assumption.
            setParameters(buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, profile.height))
        }

    fun buffers(profile: NativePlaybackPolicy.Profile): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(profile.minBufferMs, profile.maxBufferMs, 1000, 2500)
        .setBackBuffer(10_000, false).setTargetBufferBytes(profile.bufferBytes)
        .setPrioritizeTimeOverSizeThresholds(true).build()

    fun item(source: FrostSource): MediaItem = MediaItem.Builder().setMediaId(source.identity)
        .setUri(source.url).setMimeType(source.format.mime)
        .setSubtitleConfigurations(source.subtitles.map { track ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url)).setMimeType(track.mime)
                .setLabel(track.label).setLanguage(track.language)
                .setSelectionFlags(if (track.default) C.SELECTION_FLAG_DEFAULT else 0).build()
        }).build()

    fun create(source: FrostSource): MediaSource = DefaultMediaSourceFactory(OkHttpDataSource.Factory(FrostHttp.client(source)))
        .setLoadErrorHandlingPolicy(VodLoadErrorPolicy()).createMediaSource(item(source))
}

internal class VodLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    private fun stop(info: LoadErrorHandlingPolicy.LoadErrorInfo) = PlaybackProblems.from(info.exception).http in listOf(401, 403, 404, 429)
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (stop(loadErrorInfo)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
    override fun getFallbackSelectionFor(fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
                                         loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): LoadErrorHandlingPolicy.FallbackSelection? =
        if (stop(loadErrorInfo)) null else super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
}
