package com.aktv.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeLoadErrorPolicyTest {
    private val spec = DataSpec(Uri.parse("https://media.example/manifest.mpd"))
    private fun status(code: Int) = HttpDataSource.InvalidResponseCodeException(code, "fixture", null, emptyMap(), spec, ByteArray(0))
    private fun info(error: IOException) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(1, spec, 1), MediaLoadData(C.DATA_TYPE_MANIFEST), error, 3)

    @Test fun `401 and 403 reach bounded application recovery instead of repeating the rejected request`() {
        val policy = NativeLoadErrorPolicy()
        for (code in listOf(401, 403)) {
            assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(status(code))))
            assertNotEquals(C.TIME_UNSET, DefaultLoadErrorHandlingPolicy().getRetryDelayMsFor(info(status(code))))
        }
    }
    @Test fun `an expired credential does not blacklist a valid video or audio track`() {
        val options = LoadErrorHandlingPolicy.FallbackOptions(2, 0, 3, 0)
        val rejected = info(status(403))
        assertNotNull(DefaultLoadErrorHandlingPolicy().getFallbackSelectionFor(options, rejected))
        assertNull(NativeLoadErrorPolicy().getFallbackSelectionFor(options, rejected))
    }
    @Test fun `ordinary server and network errors retain the library policy`() {
        val policy = NativeLoadErrorPolicy(); val original = DefaultLoadErrorHandlingPolicy()
        for (error in listOf(status(404), status(451), status(500), SocketTimeoutException("fixture"))) {
            assertEquals(original.getRetryDelayMsFor(info(error)), policy.getRetryDelayMsFor(info(error)))
        }
    }
    @Test fun `wrapped HTTP authentication errors still fail promptly without logging their URL`() {
        assertEquals(C.TIME_UNSET, NativeLoadErrorPolicy().getRetryDelayMsFor(info(IOException("https://private/?token=secret", status(401)))))
    }
    @Test fun `a known HTML manifest is not repeatedly loaded or blamed on track availability`() {
        val error = ManifestReplyException(HttpReply.of(200, "text/html", "<html>error</html>".toByteArray()), "dash")
        val policy = NativeLoadErrorPolicy()
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(IOException("wrapper", error))))
        assertNull(policy.getFallbackSelectionFor(LoadErrorHandlingPolicy.FallbackOptions(2, 0, 3, 0), info(error)))
        val problem = PlaybackProblems.from(error, 2000)
        assertEquals(PlaybackDiagnostics.Family.FORMAT, problem.family)
        assertNull(problem.http) // Do not turn HTTP 200 into a fake 403.
        assertEquals(200, problem.reply!!.status)
    }
    @Test fun `parser code and a JJTV lookup refusal are not diagnosed as a decoder failure`() {
        val parser = androidx.media3.common.ParserException.createForMalformedManifest("unsafe response", null)
        assertEquals(PlaybackDiagnostics.Family.FORMAT, PlaybackProblems.from(parser, 3002).family)
        val refused = JjtvHttpException(403, JjtvLookupStage.STAR_COOKIE, HttpReply.of(403, "text/html", "<html/>".toByteArray()))
        val problem = PlaybackProblems.from(refused)
        assertEquals(PlaybackDiagnostics.Family.HTTP, problem.family)
        assertTrue(problem.describe().contains("Star/PL cookie"))
        assertTrue(problem.describe().contains("body HTML"))
    }

    @Test fun `rate limits go to the bounded application policy rather than immediate loader retries`() {
        assertEquals(C.TIME_UNSET, NativeLoadErrorPolicy().getRetryDelayMsFor(info(status(429))))
    }
    @Test fun `expired provider data is not mislabelled as HTTP or a codec failure`() {
        val problem = PlaybackProblems.from(SourceCredentialsUnavailableException(LiveProvider.JJTV, CredentialIssue.EXPIRED))
        assertEquals(PlaybackDiagnostics.Family.CREDENTIALS, problem.family)
        assertNull(problem.http)
        assertTrue(problem.describe().contains("expired"))
    }

}
