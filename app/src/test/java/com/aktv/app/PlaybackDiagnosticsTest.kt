package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticsTest {
    private fun begin(log: PlaybackDiagnostics = PlaybackDiagnostics(), drm: PlaybackDiagnostics.Drm = PlaybackDiagnostics.Drm.CLEARKEY) =
        log.begin(PlaybackDiagnostics.Provider.JJTV, PlaybackDiagnostics.Media.DASH, drm, true, false)

    @Test fun `no downloaded manifest is not blamed on a video codec`() {
        val attempt = begin()
        attempt.timeout()
        assertEquals("No manifest download has completed", attempt.summary())
    }
    @Test fun `a loaded manifest without accepted keys identifies the DRM wait`() {
        val attempt = begin()
        attempt.loaded(PlaybackDiagnostics.Load.MANIFEST, 2048)
        attempt.drmSupport(true); attempt.drmStep(PlaybackDiagnostics.DrmStep.SESSION_OPEN)
        assertTrue(attempt.summary().contains("Waiting for Android CLEARKEY keys"))
        assertTrue(attempt.summary().contains("SESSION_OPEN"))
    }
    @Test fun `key response acceptance is not yet Media3 keys loaded or a picture`() {
        val attempt = begin()
        attempt.loaded(PlaybackDiagnostics.Load.MANIFEST, 2048)
        attempt.drmStep(PlaybackDiagnostics.DrmStep.KEY_RESPONSE)
        assertTrue(attempt.summary().contains("Waiting for Android CLEARKEY keys"))
        assertTrue(attempt.report().contains("first video frame: false; audio position advanced: false"))
    }
    @Test fun `accepted keys isolate a later surface or codec problem`() {
        val attempt = begin()
        attempt.loaded(PlaybackDiagnostics.Load.MANIFEST, 2048)
        attempt.drmStep(PlaybackDiagnostics.DrmStep.KEYS_LOADED)
        assertEquals("The video surface is not ready", attempt.summary())
        attempt.surfaceReady()
        assertTrue(attempt.summary().contains("decoder has not initialized"))
        attempt.decoder(true, "OMX.vendor.avc.decoder")
        assertTrue(attempt.summary().contains("no first frame"))
    }
    @Test fun `unsupported DRM tracks are distinguished from unsupported codec formats`() {
        val attempt = begin()
        attempt.loaded(PlaybackDiagnostics.Load.MANIFEST, 2048)
        attempt.tracks(0, 0, 6, 0)
        assertEquals("No tracks selected: Android reports unsupported DRM", attempt.summary())
        attempt.drmStep(PlaybackDiagnostics.DrmStep.KEYS_LOADED)
        attempt.tracks(0, 1, 0, 6)
        assertEquals("No video selected: decoder format unsupported", attempt.summary())
    }
    @Test fun `platform unsupported status does not imply a network problem`() {
        val attempt = begin()
        attempt.drmSupport(false)
        assertEquals("Android reports no support for CLEARKEY", attempt.summary())
    }
    @Test fun `terminal DRM error survives later source HTTP errors in the history`() {
        val log = PlaybackDiagnostics()
        begin(log).failed(PlaybackDiagnostics.Problem(PlaybackDiagnostics.Family.DRM, 6006, detail = "MediaDrmStateException"))
        begin(log).failed(PlaybackDiagnostics.Problem(PlaybackDiagnostics.Family.HTTP, 2004, 403))
        val report = log.report()
        assertTrue(report.contains("Media3 6006")); assertTrue(report.contains("HTTP 403"))
    }
    @Test fun `history is bounded instead of accumulating long channel sessions`() {
        val log = PlaybackDiagnostics()
        repeat(20) { begin(log).timeout() }
        val report = log.report()
        assertFalse(report.contains("#1 "))
        assertTrue(report.contains("#20 "))
        assertEquals(5, report.lineSequence().count { it.startsWith("#") })
    }
    @Test fun `recoverable DRM event is not treated as a terminal playback error`() {
        val attempt = begin()
        attempt.drmError(PlaybackDiagnostics.Problem(PlaybackDiagnostics.Family.DRM, detail = "NotProvisionedException"))
        assertNull(attempt.failureLine())
        attempt.drmStep(PlaybackDiagnostics.DrmStep.KEYS_LOADED)
        attempt.frameRendered()
        assertTrue(attempt.summary().contains("Media3 reported a video frame"))
        assertTrue(attempt.summary().contains("requires device verification"))
    }
    @Test fun `later key renewal does not erase previously loaded keys`() {
        val attempt = begin()
        attempt.drmStep(PlaybackDiagnostics.DrmStep.KEYS_LOADED)
        attempt.drmStep(PlaybackDiagnostics.DrmStep.KEY_REQUEST)
        assertTrue(attempt.report().contains("stage: KEYS_LOADED"))
    }
    @Test fun `media load failures are distinguished and successful loads clear the current warning`() {
        val attempt = begin()
        attempt.loadError(PlaybackDiagnostics.Problem(PlaybackDiagnostics.Family.TLS, detail = "SSLHandshakeException"))
        assertTrue(attempt.summary().contains("TLS"))
        attempt.loaded(PlaybackDiagnostics.Load.MANIFEST, 1000, 900)
        assertFalse(attempt.summary().contains("TLS"))
        assertTrue(attempt.report().contains("Device clock minus HTTP Date: 900s"))
    }
    @Test fun `only codec and encryption labels are admitted from manifest strings`() {
        val attempt = begin()
        val secret = "https://media.example/video?token=secret"
        attempt.format(true, secret, 1280, 720, scheme = secret)
        attempt.decoder(true, secret)
        assertFalse(attempt.report().contains(secret))
        assertTrue(attempt.report().contains("other codec 1280x720"))
        assertTrue(attempt.report().contains("Encryption scheme: unknown"))
    }
    @Test fun `metadata errors are retained even before a player exists`() {
        val log = PlaybackDiagnostics()
        log.metadataFailed(PlaybackDiagnostics.Problem(PlaybackDiagnostics.Family.NETWORK, detail = "UnknownHostException"))
        assertTrue(log.summary().contains("Stream lookup: NETWORK"))
        assertFalse(log.hasClearKey())
    }
    @Test fun `audio advancement is recorded independently from a first video frame`() {
        val attempt = begin(drm = PlaybackDiagnostics.Drm.NONE)
        attempt.audioStarted()
        assertTrue(attempt.report().contains("first video frame: false; audio position advanced: true"))
    }
    @Test fun `a missing manifest or waiting DRM session must not trigger a decoder retry`() {
        val attempt = begin()
        assertFalse(attempt.canRetryDecoder())
        attempt.loaded(PlaybackDiagnostics.Load.MANIFEST, 1024)
        attempt.tracks(1, 1, 0, 0)
        attempt.drmStep(PlaybackDiagnostics.DrmStep.SESSION_OPEN)
        assertFalse(attempt.canRetryDecoder())
        attempt.drmStep(PlaybackDiagnostics.DrmStep.KEY_RESPONSE)
        assertFalse(attempt.canRetryDecoder())
        attempt.drmStep(PlaybackDiagnostics.DrmStep.KEYS_LOADED)
        assertTrue(attempt.canRetryDecoder())
    }

    @Test fun `unencrypted selected video can retry a decoder but encrypted video without configured DRM cannot`() {
        val attempt = begin(drm = PlaybackDiagnostics.Drm.NONE)
        attempt.tracks(1, 1, 0, 0)
        assertTrue(attempt.canRetryDecoder())
        attempt.format(true, "video/avc", 1280, 720, scheme = "cenc")
        assertFalse(attempt.canRetryDecoder())
    }

    @Test fun `an initialized decoder that produces no picture can retry regardless of callback order`() {
        val attempt = begin()
        attempt.decoder(true, "OMX.vendor.avc.decoder")
        assertTrue(attempt.canRetryDecoder())
    }

    @Test fun `a pending JJTV lookup must not be reported as the previous TG player attempt`() {
        val log = PlaybackDiagnostics()
        log.begin(PlaybackDiagnostics.Provider.TG, PlaybackDiagnostics.Media.DASH, PlaybackDiagnostics.Drm.NONE, false, false).timeout()
        log.resolving(PlaybackDiagnostics.Provider.JJTV)
        assertTrue(log.summary().startsWith("Resolving JJTV"))
        log.metadataFailed(PlaybackDiagnostics.Problem(PlaybackDiagnostics.Family.HTTP, http = 403))
        assertTrue(log.summary().contains("JJTV lookup: HTTP 403"))
        begin(log)
        assertFalse(log.summary().contains("lookup"))
    }

    @Test fun `reported codec limit is distinct from missing codecs or unsupported DRM`() {
        val attempt = begin(drm = PlaybackDiagnostics.Drm.NONE)
        attempt.loaded(PlaybackDiagnostics.Load.MANIFEST, 2048)
        attempt.tracks(0, 1, 0, 0, 3)
        assertEquals("No video selected: codec exceeds reported device limits", attempt.summary())
        assertTrue(attempt.report().contains("unsupported DRM 0, codec 0; above reported limits 3"))
        attempt.tracks(1, 1, 0, 0, 3)
        attempt.surfaceReady(); attempt.frameRendered()
        assertTrue(attempt.summary().contains("Media3 reported a video frame"))
    }

    @Test fun `a manifest response can be identified before any parsed load has completed`() {
        val attempt = begin(drm = PlaybackDiagnostics.Drm.NONE)
        attempt.httpResponse(HttpReply.of(200, "text/html", "<html>token=secret</html>".toByteArray()))
        val report = attempt.report()
        assertTrue(report.contains("manifest 0, init 0, media 0; 0 bytes"))
        assertTrue(report.contains("Initial manifest response: HTTP 200; MIME HTML; body HTML"))
        assertFalse(report.contains("secret"))
    }

}
