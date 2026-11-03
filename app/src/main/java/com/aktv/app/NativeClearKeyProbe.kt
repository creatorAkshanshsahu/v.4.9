package com.aktv.app

import android.media.MediaCrypto
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkCryptoConfig
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import org.json.JSONObject

/** Local API/service test, NOT a stream/decoder test. Uses public synthetic bytes and makes no network request. */
internal object NativeClearKeyProbe {
    enum class Phase { SUPPORT, CREATE, SESSION, REQUEST, RESPONSE, CRYPTO, COMPLETE }
    data class Result(val passed: Boolean, val phase: Phase, val problem: PlaybackDiagnostics.Problem? = null,
                      val secureDecoderRequired: Boolean? = null) {
        fun describe() = if (passed)
            "ClearKey self-test PASS: session, local keys and MediaCrypto opened; secure AVC required: $secureDecoderRequired. This does not test video decoding."
        else "ClearKey self-test FAILED at ${phase.name}${problem?.let { ": ${it.describe()}" } ?: ": scheme not supported"}. No channel or network was involved."
    }

    fun run(
        supported: () -> Boolean = { FrameworkMediaDrm.isCryptoSchemeSupported(C.CLEARKEY_UUID) },
        create: () -> ExoMediaDrm = { FrameworkMediaDrm.newInstance(C.CLEARKEY_UUID) },
        cryptoCheck: (CryptoConfig) -> Boolean = { config ->
            val framework = config as FrameworkCryptoConfig
            val crypto = MediaCrypto(framework.uuid, framework.sessionId)
            try { crypto.requiresSecureDecoderComponent(MimeTypes.VIDEO_H264) } finally { crypto.release() }
        }
    ): Result {
        var phase = Phase.SUPPORT
        var platform: ExoMediaDrm? = null
        var session: ByteArray? = null
        try {
            if (!supported()) return Result(false, phase)
            phase = Phase.CREATE
            val drm = create(); platform = drm
            phase = Phase.SESSION
            val scope = drm.openSession(); session = scope
            // Synthetic test vector only, deliberately containing Base64URL '-'/'_' characters.
            val configuration = JSONObject().put("type", "clearkey")
                .put("keyId", "00112233445566778899aabbccddeeff")
                .put("key", "fbeffbeffbeffbeffbeffbeffbeffbef")
            phase = Phase.REQUEST
            val request = drm.getKeyRequest(scope, mutableListOf(ClearKeyDashManifestParser.schemeData(configuration)),
                ExoMediaDrm.KEY_TYPE_STREAMING, null)
            val callback = LocalMediaDrmCallback(requireNotNull(ClearKey.license(configuration)))
            phase = Phase.RESPONSE
            drm.provideKeyResponse(scope, callback.executeKeyRequest(C.CLEARKEY_UUID, request))
            phase = Phase.CRYPTO
            val required = cryptoCheck(drm.createCryptoConfig(scope))
            return Result(true, Phase.COMPLETE, secureDecoderRequired = required)
        } catch (error: Exception) {
            return Result(false, phase, PlaybackProblems.from(error))
        } finally {
            // Never leave an extra DRM session competing with the actual player on an old TV.
            session?.let { scope -> runCatching { platform?.closeSession(scope) } }
            runCatching { platform?.release() }
        }
    }
}
