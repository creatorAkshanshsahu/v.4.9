package com.aktv.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** VOD source construction must never inherit the live player's target offset or channel DRM. */
internal object FrostMedia {
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
