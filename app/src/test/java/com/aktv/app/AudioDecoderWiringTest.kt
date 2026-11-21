package com.aktv.app

import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Silent picture on phones without Dolby/DTS licenses is fixed by the bundled FFmpeg audio
 * decoders — but only while the decoder stays on the classpath and the renderers factory asks for
 * extension renderers. Both halves are pinned here without touching native code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioDecoderWiringTest {

    @Test
    fun `the ffmpeg audio renderer ships with the app`() {
        val loaded = Class.forName(
            "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer",
            false,
            javaClass.classLoader
        )
        assertNotNull(loaded)
    }

    @Test
    fun `the player asks for extension renderers as a platform fallback`() {
        val context = RuntimeEnvironment.getApplication()
        val profile = NativePlaybackPolicy.profile(tv = false, lowMemory = false, compatible = false, requestedHeight = 1080)
        val factory = NativePlaybackPolicy.renderers(context, profile)
        val field = DefaultRenderersFactory::class.java.getDeclaredField("extensionRendererMode")
        field.isAccessible = true
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, field.getInt(factory))
        val player = ExoPlayer.Builder(context, factory).build()
        try {
            assertNotNull(player)
        } finally {
            player.release()
        }
    }
}
