package com.aktv.app

import android.media.MediaCodec
import android.media.MediaCryptoException
import android.media.MediaDrm
import android.media.MediaDrmException
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.Tracks
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.UnsupportedDrmException
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException

/** Classifies typed exceptions; messages can contain signed URLs, so they are never inspected or printed. */
internal object PlaybackProblems {
    fun from(error: Throwable, playerCode: Int? = null): PlaybackDiagnostics.Problem {
        val chain = ArrayList<Throwable>()
        var cause: Throwable? = error
        repeat(8) {
            val item = cause ?: return@repeat
            if (chain.any { it === item }) return@repeat
            chain.add(item); cause = item.cause
        }
        val invalidHttp = chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val http = invalidHttp?.responseCode
            ?: chain.filterIsInstance<JjtvHttpException>().firstOrNull()?.status
            ?: chain.filterIsInstance<TgResolveException>().firstOrNull()?.status
            ?: chain.filterIsInstance<FrostResolveException>().firstOrNull()?.status
        val deniedLookup = chain.filterIsInstance<JjtvHttpException>().firstOrNull()
        val badLookup = chain.filterIsInstance<JjtvDataException>().firstOrNull()
        val badManifest = chain.filterIsInstance<ManifestReplyException>().firstOrNull()
        val reply = deniedLookup?.reply ?: badLookup?.reply ?: badManifest?.reply ?:
            chain.filterIsInstance<TgResolveException>().firstOrNull()?.reply ?: invalidHttp?.let { response ->
                fun header(name: String) = response.headerFields.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
                HttpReply.of(response.responseCode, header("Content-Type").orEmpty(), response.responseBody,
                    header("cf-mitigated").equals("challenge", true)).copy(redirects = -1,
                    retryAfterMs = HttpReply.retryAfter(header("Retry-After"), header("Date")))
            }
        val lookupStage = deniedLookup?.stage ?: badLookup?.stage
        val credentialIssue = chain.filterIsInstance<SourceCredentialsUnavailableException>().firstOrNull()?.issue
        val crypto = chain.filterIsInstance<MediaCodec.CryptoException>().firstOrNull()?.errorCode
        val family = when {
            http != null -> PlaybackDiagnostics.Family.HTTP
            credentialIssue != null -> PlaybackDiagnostics.Family.CREDENTIALS
            badLookup != null || badManifest != null || (playerCode != null && playerCode in 3000..3999) ||
                chain.any { it is ParserException } -> PlaybackDiagnostics.Family.FORMAT
            chain.any { it is SSLException || (it is FrostResolveException && it.kind == "tls") } -> PlaybackDiagnostics.Family.TLS
            chain.any { it is SocketTimeoutException || it is UnknownHostException || (it is FrostResolveException && it.kind in setOf("dns", "timeout")) } -> PlaybackDiagnostics.Family.NETWORK
            (playerCode != null && playerCode in 6000..6999) || chain.any { it is MediaDrmException || it is MediaDrm.MediaDrmStateException ||
                it is UnsupportedDrmException || it is MediaCryptoException || it is MediaCodec.CryptoException } -> PlaybackDiagnostics.Family.DRM
            (playerCode != null && playerCode in 4000..4999) || chain.any { it is MediaCodec.CodecException } -> PlaybackDiagnostics.Family.DECODER
            else -> PlaybackDiagnostics.Family.PLAYER
        }
        val names = chain.map { it.javaClass.simpleName }.distinct().take(4)
            .joinToString(" > ") { it.takeIf { name -> name.matches(Regex("[A-Za-z_$]{1,48}")) } ?: "Exception" }
        val native = chain.firstNotNullOfOrNull {
            val info = when (it) {
                is MediaDrm.MediaDrmStateException -> it.diagnosticInfo
                is MediaCodec.CodecException -> it.diagnosticInfo
                else -> null
            }
            info?.takeIf { code -> code.matches(Regex("android\\.media\\.[A-Za-z0-9_.-]{1,70}")) }
        }
        return PlaybackDiagnostics.Problem(family, playerCode, http, listOfNotNull(names, native).joinToString(" : ").take(180), crypto, reply, lookupStage, credentialIssue)
    }
}

internal class NativePlaybackDiagnostics(private val trace: PlaybackDiagnostics.Attempt) : AnalyticsListener {
    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        val type = when (mediaLoadData.dataType) {
            C.DATA_TYPE_MANIFEST -> PlaybackDiagnostics.Load.MANIFEST
            C.DATA_TYPE_MEDIA_INITIALIZATION -> PlaybackDiagnostics.Load.INITIALIZATION
            C.DATA_TYPE_MEDIA -> PlaybackDiagnostics.Load.MEDIA
            else -> PlaybackDiagnostics.Load.OTHER
        }
        val date = loadEventInfo.responseHeaders.entries.firstOrNull { it.key.equals("Date", true) }?.value?.firstOrNull()
        val skew = try { date?.let { (System.currentTimeMillis() - ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()) / 1000 } }
            catch (_: Exception) { null }
        trace.loaded(type, loadEventInfo.bytesLoaded, skew)
    }
    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
        if (!wasCanceled) trace.loadError(PlaybackProblems.from(error))
    }
    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        var video = 0; var audio = 0; var drm = 0; var codec = 0; var exceeds = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val isVideo = group.type == C.TRACK_TYPE_VIDEO
                if (group.isTrackSelected(i)) {
                    if (isVideo) video++ else audio++
                    recordFormat(isVideo, group.getTrackFormat(i))
                }
                when (group.getTrackSupport(i)) {
                    C.FORMAT_UNSUPPORTED_DRM -> drm++
                    C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_UNSUPPORTED_SUBTYPE -> codec++
                    C.FORMAT_EXCEEDS_CAPABILITIES -> exceeds++
                }
            }
        }
        trace.tracks(video, audio, drm, codec, exceeds)
    }
    private fun recordFormat(video: Boolean, value: Format) = trace.format(video, value.sampleMimeType,
        value.width, value.height, value.channelCount, value.drmInitData?.schemeType)
    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) { recordFormat(true, format) }
    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) { recordFormat(false, format) }
    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { trace.decoder(true, decoderName) }
    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { trace.decoder(false, decoderName) }
    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) { trace.audioStarted() }
    override fun onDrmSessionAcquired(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (state == DrmSession.STATE_OPENED || state == DrmSession.STATE_OPENED_WITH_KEYS)
            trace.drmStep(PlaybackDiagnostics.DrmStep.SESSION_OPEN)
    }
    override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime) { trace.drmStep(PlaybackDiagnostics.DrmStep.KEYS_LOADED) }
    // This event is observational, not a reason to retry: Media3 may recover by itself.
    override fun onDrmSessionManagerError(eventTime: AnalyticsListener.EventTime, error: Exception) { trace.drmError(PlaybackProblems.from(error)) }
}
