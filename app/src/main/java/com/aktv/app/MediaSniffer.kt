package com.aktv.app

import android.content.Context

/**
 * There is no page reader in this app.
 *
 * This used to run a page in a WebView and watch what it fetched, which is how a stream was recovered from a
 * player that only exists after its scripts run. On a television that engine is a stripped Chromium that takes
 * the process down when it is created, and it was the single most expensive thing in the app. Every stream the
 * app plays now comes from an HTTP endpoint, so there is nothing left to sniff: the entry point stays, always
 * answers "nothing found", and never constructs a browser.
 */
internal object MediaSniffer {

    /** Kept so the resolvers can pass a context in without caring that nothing uses it. */
    @Volatile var appContext: Context? = null

    @Volatile var lastSummary: String = "browser engine removed"

    fun find(context: Context, target: String, referer: String? = null, timeoutMs: Long = 12_000L): String? {
        lastSummary = SiteBrowserGate.summary()
        return null
    }

    /** Media addresses named in a response body, for the resolvers that read JSON themselves. */
    private val MEDIA_IN_TEXT = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mpd)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

    fun mediaIn(body: String): List<String> =
        MEDIA_IN_TEXT.findAll(body.replace("\\/", "/")).map { it.value }.take(4).toList()
}
