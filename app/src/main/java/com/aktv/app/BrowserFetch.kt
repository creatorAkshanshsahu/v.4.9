package com.aktv.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Reads a URL through the system browser engine.
 *
 * allinonereborn2.online put a script gate in front of its playlists: a plain HTTP client is answered with
 * `HTTP 415` and the section cannot load at all, while a browser that runs the gate gets the file. This is
 * the same request a browser makes, one step removed - a hidden `WebView` loads the address, waits until the
 * document body really contains a playlist, and hands the text back to the resolver. It is only used after
 * every scriptless request has been refused, and it is capped by a fixed deadline so a gate that never
 * passes costs a few seconds and nothing more.
 */
internal object BrowserFetch {

    private const val DEADLINE_MS = 15_000L
    private const val STEP_MS = 500L
    private const val MAX_STEPS = 26

    /** The playlist text, or null when the browser is missing, still gated, or slow. Never on the UI thread. */
    fun playlist(context: Context, url: String): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        // Same rule as the site-player search: a TV whose browser engine takes the process down never runs it.
        if (!SiteBrowserGate.allowed) return null
        val latch = CountDownLatch(1)
        val reader = Reader(context.applicationContext, url, latch)
        Handler(Looper.getMainLooper()).post { reader.start() }
        return try {
            if (!latch.await(DEADLINE_MS + 2_000, TimeUnit.MILLISECONDS)) null else reader.text
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); null
        }
    }

    private class Reader(context: Context, private val url: String, private val latch: CountDownLatch) {
        private val app = context
        var text: String? = null
        private var web: WebView? = null
        private var done = false

        fun start() {
            try {
                val view = WebView(app)
                web = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, address: String?) { poll(0) }
                }
                view.loadUrl(url)
                Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, DEADLINE_MS)
            } catch (_: Throwable) {
                // No WebView on this box: the caller keeps its refusal and the section says why.
                finish(null)
            }
        }

        private fun poll(step: Int) {
            val view = web ?: return finish(null)
            if (done) return
            try {
                view.evaluateJavascript("(document.body && document.body.innerText) || ''") { raw ->
                    val body = unquote(raw)
                    if (body != null && body.contains("#EXTM3U")) finish(body)
                    else if (step < MAX_STEPS) Handler(Looper.getMainLooper()).postDelayed({ poll(step + 1) }, STEP_MS)
                    else finish(null)
                }
            } catch (_: Throwable) {
                finish(null)
            }
        }

        private fun finish(value: String?) {
            if (done) return
            done = true
            text = value
            val view = web
            web = null
            try { view?.stopLoading(); view?.destroy() } catch (_: Throwable) { }
            latch.countDown()
        }

        /** `evaluateJavascript` answers with a JSON string literal; the playlist's newlines matter. */
        private fun unquote(raw: String?): String? = try {
            if (raw.isNullOrBlank() || raw == "null") null else JSONObject("{\"v\":$raw}").getString("v")
        } catch (_: Throwable) {
            null
        }
    }
}
