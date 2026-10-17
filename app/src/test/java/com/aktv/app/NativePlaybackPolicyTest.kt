package com.aktv.app

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

    @Test fun `actual track parameters prefer supported AVC AAC rather than exceeding renderer capabilities`() {
        val profile = NativePlaybackPolicy.profile(true, false, false, 1080)
        val selector = NativePlaybackPolicy.tracks(RuntimeEnvironment.getApplication(), profile, hindi = true)
        val parameters = selector.parameters
        assertFalse(parameters.exceedRendererCapabilitiesIfNecessary)
        assertTrue(parameters.exceedVideoConstraintsIfNecessary)
        assertEquals(listOf(MimeTypes.VIDEO_H264), parameters.preferredVideoMimeTypes.toList())
        assertEquals(listOf(MimeTypes.AUDIO_AAC), parameters.preferredAudioMimeTypes.toList())
        assertTrue(parameters.preferredAudioLanguages.any { it == "hi" || it == "hin" })
        assertEquals(720, parameters.maxVideoHeight)
        selector.release()
    }

    @Test fun `compatibility selects low bitrate but does not invent unsupported video tracks`() {
        val selector = NativePlaybackPolicy.tracks(RuntimeEnvironment.getApplication(),
            NativePlaybackPolicy.profile(true, true, true, 720), hindi = false)
        assertTrue(selector.parameters.forceLowestBitrate)
        assertFalse(selector.parameters.exceedRendererCapabilitiesIfNecessary)
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
}
