package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class StablePlaybackTest {
    private fun track(key: String, height: Int, rate: Float = 50f, bitrate: Int = 3_000_000) =
        VideoRendition(key, height * 16 / 9, height, rate, bitrate, "video/avc")

    @Test fun `stable startup holds a real rendition at the requested ceiling`() {
        val tracks = listOf(track("1080", 1080), track("720-high", 720, bitrate = 4_000_000),
            track("720-low", 720, bitrate = 2_500_000), track("576", 576))
        assertEquals("720-low", StableRenditions.initial(tracks, 720)!!.key)
    }
    @Test fun `a missing 540p variant is not invented when the smallest offered track is 720p`() {
        val choice = StableRenditions.initial(listOf(track("1080", 1080), track("720", 720)), 540)
        assertEquals(720, choice!!.height)
        assertTrue(choice.label.startsWith("720p"))
    }
    @Test fun `sustained drops step down within the same offered track set`() {
        val current = track("1080", 1080, bitrate = 5_000_000)
        val next = StableRenditions.lower(current, listOf(current, track("720", 720), track("576", 576)))
        assertEquals(720, next!!.height)
    }
    @Test fun `a lower frame rate can help without pretending the stream resolution changed`() {
        val current = track("50fps", 720)
        val next = track("25fps", 720, 25f)
        assertEquals(next, StableRenditions.lower(current, listOf(current, next)))
    }
    @Test fun `a lower bitrate at the same dimensions is a genuine alternative`() {
        val current = track("high", 720, bitrate = 5_000_000)
        val low = track("low", 720, bitrate = 2_500_000)
        assertEquals(low, StableRenditions.lower(current, listOf(current, low)))
    }
    @Test fun `the currently selected rendition cannot be presented as a lower alternative`() {
        val current = track("same", 720)
        assertNull(StableRenditions.lower(current, listOf(current)))
        assertNull(StableRenditions.lower(current, listOf(current.copy(key = "different-id", fps = -1f))))
    }
    @Test fun `a locked representation survives new higher quality choices without upshifting`() {
        val lock = track("720", 720)
        assertEquals(lock, StableRenditions.keep(lock, listOf(track("4k", 2160), track("1080", 1080), lock)))
    }
    @Test fun `renamed live track groups choose an equivalent or lower rendition`() {
        val lock = track("old-720", 720)
        val newer = track("new-720", 720)
        assertEquals(newer, StableRenditions.keep(lock, listOf(track("1080", 1080), newer, track("576", 576))))
    }
    @Test fun `removed renditions fall back to an actual supplied minimum`() {
        val only = track("only720", 720)
        assertEquals(only, StableRenditions.keep(track("old540", 540), listOf(only)))
        assertNull(StableRenditions.initial(emptyList(), 720))
    }
    @Test fun `startup and buffering are not classified as sustained decoding drops`() {
        val window = FrameDropWindow()
        window.reset(0)
        assertFalse(window.observe(2000, 50, 1000, true))
        assertFalse(window.observe(8000, 50, 1000, false))
    }
    @Test fun `a single spike does not repeatedly degrade the picture`() {
        val window = FrameDropWindow(); window.reset(0)
        assertFalse(window.observe(8000, 12, 2000, true))
        assertFalse(window.observe(10_000, 12, 2000, true))
        assertTrue(window.observe(12_000, 12, 2000, true))
        assertFalse(window.observe(15_000, 50, 1000, true))
    }
    @Test fun `healthy windows decay accumulated pressure`() {
        val window = FrameDropWindow(); window.reset(0)
        assertFalse(window.observe(8000, 10, 2000, true))
        assertFalse(window.observe(10_000, 1, 2000, true))
        assertFalse(window.observe(12_000, 10, 2000, true))
    }
    @Test fun `labels describe only offered dimensions frame rate and bitrate`() {
        assertEquals("720p · 50 fps · 3.0 Mbps · AVC", track("id", 720).label)
        assertEquals("480p · AVC", track("id", 480, -1f, -1).label)
    }
    @Test fun `smoothing cannot replace working AVC with HEVC just because its bitrate is smaller`() {
        val avc = track("avc", 720, bitrate = 4_000_000)
        val hevc = track("hevc", 720, bitrate = 1_000_000).copy(mime = "video/hevc")
        assertNull(StableRenditions.lower(avc, listOf(avc, hevc)))
        assertEquals(avc.copy(key = "new-avc"), StableRenditions.keep(avc, listOf(hevc, avc.copy(key = "new-avc"))))
    }

    @Test fun `stable startup does not push a bandwidth-selected low rendition back up before playback`() {
        val low = track("360", 360)
        assertEquals(low, StableRenditions.started(low, listOf(track("720", 720), low), 720))
        assertNull(StableRenditions.started(null, listOf(track("720", 720), low), 720))
    }

}
