package com.aktv.app

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/** Device playback choices, not network/DRM bypasses. Secure-decoder requirements are always retained. */
internal object NativePlaybackPolicy {
    data class Profile(
        val height: Int,
        val synchronous: Boolean,
        val softwareFirst: Boolean,
        val preferAvc: Boolean,
        val maxBitrate: Int,
        val maxFrameRate: Int,
        val bufferBytes: Int,
        val minBufferMs: Int,
        val maxBufferMs: Int
    )

    fun profile(tv: Boolean, lowMemory: Boolean, compatible: Boolean, requestedHeight: Int): Profile {
        val conservative = tv || lowMemory || compatible
        return Profile(
            height = minOf(requestedHeight.coerceAtLeast(240), if (compatible) 480 else if (conservative) 720 else 1080),
            synchronous = tv || compatible,
            softwareFirst = compatible,
            preferAvc = conservative,
            maxBitrate = if (compatible) 1_500_000 else if (conservative) 3_000_000 else Int.MAX_VALUE,
            maxFrameRate = if (conservative) 30 else Int.MAX_VALUE,
            bufferBytes = (if (compatible || lowMemory) 24 else if (tv) 32 else 96) * 1024 * 1024,
            minBufferMs = if (conservative) 6_000 else 15_000,
            maxBufferMs = if (conservative) 20_000 else 40_000
        )
    }

    fun isTelevision(context: Context): Boolean {
        val mode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
        val packages = context.packageManager
        return mode == Configuration.UI_MODE_TYPE_TELEVISION ||
            packages.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun softwarePreferred(delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT) = MediaCodecSelector { mime, secure, tunneling ->
        // The delegate filters secure/tunnelling requirements. Never query insecure alternatives
        // for protected content, invent a decoder, or discard hardware when software is unavailable.
        delegate.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
    }

    fun renderers(context: Context, profile: Profile): DefaultRenderersFactory =
        DefaultRenderersFactory(context).setEnableDecoderFallback(true).apply {
            if (profile.synchronous) forceDisableMediaCodecAsynchronousQueueing()
            if (profile.softwareFirst) setMediaCodecSelector(softwarePreferred())
            // Otherwise leave Media3's platform-specific adapter choice intact, particularly on phones.
        }

    fun tracks(context: Context, profile: Profile, hindi: Boolean): DefaultTrackSelector =
        DefaultTrackSelector(context, AdaptiveTrackSelection.Factory(15_000, 20_000, 20_000, 0.75f)).apply {
            setParameters(buildUponParameters()
                .setMaxVideoSize((profile.height * 16 + 8) / 9, profile.height)
                .setMaxVideoBitrate(profile.maxBitrate).setMaxVideoFrameRate(profile.maxFrameRate)
                .setForceLowestBitrate(profile.softwareFirst)
                .setPreferredVideoMimeTypes(*if (profile.preferAvc) arrayOf(MimeTypes.VIDEO_H264) else emptyArray())
                .setPreferredAudioMimeTypes(*if (profile.preferAvc) arrayOf(MimeTypes.AUDIO_AAC) else emptyArray())
                .setPreferredAudioLanguages(*if (hindi) arrayOf("hi", "hin") else emptyArray())
                .setViewportSizeToPhysicalDisplaySize(context, true)
                // A higher-resolution *supported* track is preferable to audio-only when it is the
                // only variant. A track outside the renderer's actual capabilities is not.
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(false))
        }

    fun buffers(profile: Profile) = DefaultLoadControl.Builder()
        .setBufferDurationsMs(profile.minBufferMs, profile.maxBufferMs, 750, 2_000)
        .setBackBuffer(0, false).setTargetBufferBytes(profile.bufferBytes)
        .setPrioritizeTimeOverSizeThresholds(true).build()
}
