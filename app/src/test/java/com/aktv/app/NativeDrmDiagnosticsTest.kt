package com.aktv.app

import android.media.DeniedByServerException
import android.media.MediaCodec
import android.media.MediaCryptoException
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.drm.DummyExoMediaDrm
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkCryptoConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Base64
import javax.net.ssl.SSLHandshakeException

/** Tests contracts and redaction, not an emulated or physical device DRM plugin. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeDrmDiagnosticsTest {
    private class Platform : ExoMediaDrm by DummyExoMediaDrm() {
        val calls = arrayListOf<String>()
        val scope = byteArrayOf(1, 2, 3)
        val config = FrameworkCryptoConfig(C.CLEARKEY_UUID, scope, false)
        val request = ExoMediaDrm.KeyRequest(byteArrayOf(7), "")
        var responseError: Exception? = null
        var suppliedResponse: ByteArray? = null
        var suppliedScheme: MutableList<DrmInitData.SchemeData>? = null
        override fun openSession(): ByteArray { calls.add("open"); return scope }
        override fun getKeyRequest(scope: ByteArray, schemeDatas: MutableList<DrmInitData.SchemeData>?, keyType: Int,
                                   optionalParameters: HashMap<String, String>?): ExoMediaDrm.KeyRequest {
            assertSame(this.scope, scope); assertEquals(ExoMediaDrm.KEY_TYPE_STREAMING, keyType)
            calls.add("request"); suppliedScheme = schemeDatas; return request
        }
        override fun provideKeyResponse(scope: ByteArray, response: ByteArray): ByteArray? {
            assertSame(this.scope, scope); calls.add("response"); suppliedResponse = response
            responseError?.let { throw it }
            return null
        }
        override fun createCryptoConfig(sessionId: ByteArray): CryptoConfig {
            assertSame(scope, sessionId); calls.add("crypto"); return config
        }
        override fun requiresSecureDecoder(sessionId: ByteArray, mimeType: String): Boolean {
            assertSame(scope, sessionId); calls.add("secure"); return true
        }
        override fun closeSession(sessionId: ByteArray) { assertSame(scope, sessionId); calls.add("close") }
        override fun acquire() { calls.add("acquire") }
        override fun release() { calls.add("release") }
    }
    private fun attempt() = PlaybackDiagnostics().begin(PlaybackDiagnostics.Provider.JJTV,
        PlaybackDiagnostics.Media.DASH, PlaybackDiagnostics.Drm.CLEARKEY, true, false)

    @Test fun `observer passes exact session request and response bytes without downgrading security`() {
        val platform = Platform(); val trace = attempt(); val observer = ObservedMediaDrm(platform, trace)
        val session = observer.openSession()
        val schemes = mutableListOf<DrmInitData.SchemeData>()
        assertSame(platform.request, observer.getKeyRequest(session, schemes, ExoMediaDrm.KEY_TYPE_STREAMING, null))
        assertSame(schemes, platform.suppliedScheme)
        val response = byteArrayOf(8, 9)
        observer.provideKeyResponse(session, response)
        assertSame(response, platform.suppliedResponse)
        assertSame(platform.config, observer.createCryptoConfig(session))
        assertTrue(observer.requiresSecureDecoder(session, "video/avc"))
        observer.closeSession(session); observer.release()
        assertEquals(listOf("open", "request", "response", "crypto", "secure", "close", "release"), platform.calls)
        assertTrue(trace.report().contains("stage: KEY_RESPONSE"))
        assertFalse(trace.report().contains("stage: KEYS_LOADED"))
    }
    @Test fun `observer retains typed DRM failure and rethrows the same exception without its message`() {
        val platform = Platform(); val trace = attempt()
        val error = DeniedByServerException("https://secret.example/?cookie=token secret-key")
        platform.responseError = error
        val observer = ObservedMediaDrm(platform, trace)
        try { observer.provideKeyResponse(platform.scope, byteArrayOf(0)); fail("Expected platform failure") }
        catch (caught: Exception) { assertSame(error, caught) }
        assertTrue(trace.report().contains("DeniedByServerException"))
        assertFalse(trace.report().contains("secret"))
        assertFalse(trace.report().contains("KEY_RESPONSE"))
    }
    @Test fun `self-test uses the actual production PSSH and key response format then releases everything`() {
        val platform = Platform()
        val result = NativeClearKeyProbe.run({ true }, { platform }) { config ->
            assertSame(platform.config, config); false
        }
        assertTrue(result.passed)
        assertFalse(result.secureDecoderRequired!!)
        assertEquals(listOf("open", "request", "response", "crypto", "close", "release"), platform.calls)
        assertEquals(C.COMMON_PSSH_UUID, platform.suppliedScheme!!.single().uuid)
        val key = JSONObject(String(platform.suppliedResponse!!)).getJSONArray("keys").getJSONObject(0)
        assertEquals("oct", key.getString("kty"))
        assertEquals(16, Base64.getUrlDecoder().decode(key.getString("k")).size)
        assertTrue(key.getString("k").contains('-') || key.getString("k").contains('_'))
        assertTrue(result.describe().contains("does not test video decoding"))
    }
    @Test fun `unsupported DRM is a local device result not a failed website`() {
        val result = NativeClearKeyProbe.run({ false }, { error("Must not create unsupported plugin") }) { false }
        assertFalse(result.passed)
        assertEquals(NativeClearKeyProbe.Phase.SUPPORT, result.phase)
        assertTrue(result.describe().contains("No channel or network was involved"))
    }
    @Test fun `a local key response rejection identifies its phase and closes the session`() {
        val platform = Platform().apply { responseError = DeniedByServerException("private credentials") }
        val result = NativeClearKeyProbe.run({ true }, { platform }) { error("No crypto after key rejection") }
        assertFalse(result.passed)
        assertEquals(NativeClearKeyProbe.Phase.RESPONSE, result.phase)
        assertEquals(listOf("open", "request", "response", "close", "release"), platform.calls)
        assertFalse(result.describe().contains("private credentials"))
    }
    @Test fun `a MediaCrypto creation failure is distinguished from successful key acceptance`() {
        val platform = Platform()
        val result = NativeClearKeyProbe.run({ true }, { platform }) { throw MediaCryptoException("secret") }
        assertFalse(result.passed)
        assertEquals(NativeClearKeyProbe.Phase.CRYPTO, result.phase)
        assertEquals(listOf("close", "release"), platform.calls.takeLast(2))
        assertTrue(result.describe().contains("MediaCryptoException"))
    }
    @Test fun `failure during platform construction has no phantom session to close`() {
        val result = NativeClearKeyProbe.run({ true }, { throw IllegalStateException("sensitive-url") }) { false }
        assertEquals(NativeClearKeyProbe.Phase.CREATE, result.phase)
        assertFalse(result.describe().contains("sensitive-url"))
    }
    @Test fun `native crypto error remains DRM even inside a generic playback exception`() {
        val problem = PlaybackProblems.from(IOException("signed-url", MediaCodec.CryptoException(1, "key-material")), 1000)
        assertEquals(PlaybackDiagnostics.Family.DRM, problem.family)
        assertEquals(1, problem.cryptoCode)
        assertFalse(problem.describe().contains("key-material"))
        assertFalse(problem.describe().contains("signed-url"))
    }
    @Test fun `TLS exception is reported independently from decoder or DRM compatibility`() {
        val problem = PlaybackProblems.from(IOException("signed-url", SSLHandshakeException("certificate-secret")), 2001)
        assertEquals(PlaybackDiagnostics.Family.TLS, problem.family)
        assertTrue(problem.describe().contains("SSLHandshakeException"))
        assertFalse(problem.describe().contains("certificate-secret"))
    }
    @Test fun `observation preserves DRM reference counting`() {
        val platform = Platform(); val observer = ObservedMediaDrm(platform, attempt())
        observer.acquire(); observer.release(); observer.release()
        assertEquals(listOf("acquire", "release", "release"), platform.calls)
    }
}
