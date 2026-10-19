package com.aktv.app

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Let the app renew rejected credentials instead of retrying or blacklisting tracks with the same
 * invalid token. Ordinary network/server errors keep Media3's existing retry/fallback behavior.
 */
internal class NativeLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    private fun rejected(info: LoadErrorHandlingPolicy.LoadErrorInfo) = PlaybackProblems.from(info.exception).http.let { it == 401 || it == 403 }
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (rejected(loadErrorInfo)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
    override fun getFallbackSelectionFor(fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
                                         loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): LoadErrorHandlingPolicy.FallbackSelection? =
        if (rejected(loadErrorInfo)) null else super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
}
