package com.aktv.app

import androidx.media3.common.DrmInitData
import androidx.media3.exoplayer.drm.DummyExoMediaDrm
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm

/** Observes the real platform session. Delegates credentials, security requirements and ownership unchanged. */
internal class ObservedMediaDrm(
    private val delegate: ExoMediaDrm, private val trace: PlaybackDiagnostics.Attempt
) : ExoMediaDrm by delegate {
    override fun openSession(): ByteArray = observe(PlaybackDiagnostics.DrmStep.SESSION_OPEN) { delegate.openSession() }
    override fun getKeyRequest(scope: ByteArray, schemeDatas: MutableList<DrmInitData.SchemeData>?, keyType: Int,
                               optionalParameters: HashMap<String, String>?): ExoMediaDrm.KeyRequest =
        observe(PlaybackDiagnostics.DrmStep.KEY_REQUEST) { delegate.getKeyRequest(scope, schemeDatas, keyType, optionalParameters) }
    override fun provideKeyResponse(scope: ByteArray, response: ByteArray): ByteArray? =
        observe(PlaybackDiagnostics.DrmStep.KEY_RESPONSE) { delegate.provideKeyResponse(scope, response) }

    private fun <T> observe(step: PlaybackDiagnostics.DrmStep, action: () -> T): T = try {
        action().also { trace.drmStep(step) }
    } catch (error: Exception) {
        trace.drmError(PlaybackProblems.from(error)); throw error
    }

    companion object {
        fun provider(trace: PlaybackDiagnostics.Attempt) = ExoMediaDrm.Provider { uuid ->
            trace.drmStep(PlaybackDiagnostics.DrmStep.CREATING)
            try {
                trace.drmSupport(FrameworkMediaDrm.isCryptoSchemeSupported(uuid))
                val platform = FrameworkMediaDrm.newInstance(uuid)
                trace.drmStep(PlaybackDiagnostics.DrmStep.CREATED)
                ObservedMediaDrm(platform, trace)
            } catch (error: Exception) {
                trace.drmError(PlaybackProblems.from(error))
                // Same unsupported result as Media3's default provider, but retain the actual cause.
                DummyExoMediaDrm()
            }
        }
    }
}
