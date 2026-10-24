package com.aktv.app

import androidx.media3.common.C
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativePlaybackPolicyTest {
    private fun codec(name: String, software: Boolean, secure: Boolean = false) = MediaCodecInfo.newInstance(
        name, MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H264, null, !software, software, !software, false, secure)

    @Test fun `TV defaults do not force asynchronous codecs or a 96 MB buffer`() {
        val profile = NativePlaybackPolicy.profile(tv = true, lowMemory = false, compatible = false, requestedHeight = 1080)
        assertTrue(profile.synchronous)
        assertFalse(profile.softwareFirst)
        assertEquals(720, profile.height)
        assertTrue(profile.bufferBytes <= 32 * 1024 * 1024)
        assertEquals(30, profile.maxFrameRate)
    }

    @Test fun `working phone defaults retain platform codec selection and the normal quality ceiling`() {
        val profile = NativePlaybackPolicy.profile(tv = false, lowMemory = false, compatible = false, requestedHeight = 1080)
        assertFalse(profile.synchronous)
        assertFalse(profile.softwareFirst)
        assertEquals(1080, profile.height)
        assertEquals(96 * 1024 * 1024, profile.bufferBytes)
    }

    @Test fun `compatibility mode reduces decoder and memory load without another player`() {
        val profile = NativePlaybackPolicy.profile(tv = true, lowMemory = true, compatible = true, requestedHeight = 1080)
        assertTrue(profile.synchronous)
        assertTrue(profile.softwareFirst)
        assertEquals(480, profile.height)
        assertTrue(profile.maxBitrate <= 1_500_000)
        assertEquals(24 * 1024 * 1024, profile.bufferBytes)
    }

    @Test fun `TV track parameters retain the legacy reported-limit fallback while preferring AVC AAC`() {
        val profile = NativePlaybackPolicy.profile(true, false, false, 1080)
        val selector = NativePlaybackPolicy.tracks(RuntimeEnvironment.getApplication(), profile, hindi = true)
        val parameters = selector.parameters
        assertTrue(parameters.exceedRendererCapabilitiesIfNecessary)
        assertTrue(parameters.exceedVideoConstraintsIfNecessary)
        assertEquals(listOf(MimeTypes.VIDEO_H264), parameters.preferredVideoMimeTypes.toList())
        assertEquals(listOf(MimeTypes.AUDIO_AAC), parameters.preferredAudioMimeTypes.toList())
        assertTrue(parameters.preferredAudioLanguages.any { it == "hi" || it == "hin" })
        assertEquals(720, parameters.maxVideoHeight)
        selector.release()
    }

    @Test fun `TV compatibility selects low bitrate and retains the reported-limit fallback`() {
        val selector = NativePlaybackPolicy.tracks(RuntimeEnvironment.getApplication(),
            NativePlaybackPolicy.profile(true, true, true, 720), hindi = false)
        assertTrue(selector.parameters.forceLowestBitrate)
        assertTrue(selector.parameters.exceedRendererCapabilitiesIfNecessary)
        assertEquals(480, selector.parameters.maxVideoHeight)
        selector.release()
    }

    @Test fun `software preference reorders only the decoders actually returned by the platform`() {
        val hardware = codec("OMX.vendor.avc.decoder", false)
        val software = codec("OMX.google.h264.decoder", true)
        val selector = NativePlaybackPolicy.softwarePreferred(MediaCodecSelector { mime, secure, tunneling ->
            assertEquals(MimeTypes.VIDEO_H264, mime)
            assertFalse(secure); assertFalse(tunneling)
            listOf(hardware, software)
        })
        assertEquals(listOf(software, hardware), selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false))
    }

    @Test fun `secure and tunnelling requirements are never downgraded for software fallback`() {
        val secureHardware = codec("OMX.vendor.avc.decoder.secure", false, secure = true)
        var calls = 0
        val selector = NativePlaybackPolicy.softwarePreferred(MediaCodecSelector { _, secure, tunneling ->
            calls++
            assertTrue(secure); assertTrue(tunneling)
            listOf(secureHardware)
        })
        assertEquals(listOf(secureHardware), selector.getDecoderInfos(MimeTypes.VIDEO_H264, true, true))
        assertEquals(1, calls)
    }
    @Test fun `phone track capability policy is unchanged including low memory compatibility`() {
        for (compatible in listOf(false, true)) {
            val selector = NativePlaybackPolicy.tracks(RuntimeEnvironment.getApplication(),
                NativePlaybackPolicy.profile(false, true, compatible, 1080), false)
            assertFalse(selector.parameters.exceedRendererCapabilitiesIfNecessary)
            selector.release()
        }
    }

    private class SupportProbe : DefaultTrackSelector(RuntimeEnvironment.getApplication()) {
        fun allows(support: Int, exceed: Boolean) = isSupported(RendererCapabilities.create(support), exceed)
    }

    @Test fun `Media3 legacy limit fallback never admits unsupported DRM or nonexistent codecs`() {
        val selector = SupportProbe()
        assertTrue(selector.allows(C.FORMAT_HANDLED, false))
        assertFalse(selector.allows(C.FORMAT_EXCEEDS_CAPABILITIES, false))
        assertTrue(selector.allows(C.FORMAT_EXCEEDS_CAPABILITIES, true))
        for (support in listOf(C.FORMAT_UNSUPPORTED_DRM, C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_UNSUPPORTED_SUBTYPE)) {
            assertFalse(selector.allows(support, true))
        }
        selector.release()
    }

    @Test fun `generic no-touch D-pad box uses TV policy without relying on fake release or model`() {
        assertTrue(NativePlaybackPolicy.televisionDevice(android.content.res.Configuration.UI_MODE_TYPE_NORMAL, false, false,
            android.content.res.Configuration.TOUCHSCREEN_NOTOUCH, android.content.res.Configuration.NAVIGATION_DPAD))
        assertFalse(NativePlaybackPolicy.televisionDevice(android.content.res.Configuration.UI_MODE_TYPE_NORMAL, false, false,
            android.content.res.Configuration.TOUCHSCREEN_FINGER, android.content.res.Configuration.NAVIGATION_DPAD))
        assertFalse(NativePlaybackPolicy.televisionDevice(android.content.res.Configuration.UI_MODE_TYPE_NORMAL, false, false,
            android.content.res.Configuration.TOUCHSCREEN_NOTOUCH, android.content.res.Configuration.NAVIGATION_UNDEFINED))
    }
    @Test fun `explicit generic box override enables the TV policy`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("aktv", android.content.Context.MODE_PRIVATE)
        try {
            prefs.edit().putBoolean("native_force_tv", true).commit()
            assertTrue(NativePlaybackPolicy.isTelevision(context))
        } finally { prefs.edit().remove("native_force_tv").commit() }
    }

}
