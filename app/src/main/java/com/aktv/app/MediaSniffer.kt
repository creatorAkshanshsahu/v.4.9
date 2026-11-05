package com.aktv.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finds the media address a site's own player uses, by running that page the way a viewer's browser runs it.
 *
 * The OG player and its servers name no address in the HTML they serve: the page runs a script, the script
 * asks for a manifest, and only that request knows where the video is. Scraping therefore found "no address"
 * on all three servers. This watches the requests the page makes - at the network layer, so a cross-site
 * iframe counts too - and reports the first `.m3u8`/`.mp4` it asks for. The app then plays that same address
 * natively, with the site as the referrer.
 *
 * The page is loaded inside a one-page wrapper whose base URL is the site, because several of these players
 * answer only when the request carries the site's `Referer`.
 */
internal object MediaSniffer {

    private const val STEP_MS = 700L
    private const val MAX_STEPS = 30          // ~21 s
    private val MEDIA = Regex("""^https?://[^\s"'<>]+?\.(m3u8|mp4)(?:\?.*)?$""", RegexOption.IGNORE_CASE)

    /** What the last attempt saw, for the player's own report: "loads 41 requests, no manifest". */
    @Volatile var lastSummary: String = "not attempted"

    fun find(context: Context, target: String, referer: String? = null): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val latch = CountDownLatch(1)
        val reader = Reader(context.applicationContext, target, referer, latch)
        Handler(Looper.getMainLooper()).post { reader.start() }
        val ok = try {
            latch.await(STEP_MS * MAX_STEPS + 6_000, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
        lastSummary = reader.summary()
        return if (ok) reader.found else null
    }

    private fun mediaOf(url: String?): String? {
        if (url == null || url.length > 2048) return null
        val clean = url.replace("\\/", "/")
        if (!MEDIA.matches(clean)) return null
        if (clean.contains(".mpd", true)) return null                 // Widevine: never offered
        if (clean.contains("/ad", true) || clean.contains("ads.", true)) return null
        return clean
    }

    private class Reader(
        context: Context,
        private val target: String,
        private val referer: String?,
        private val latch: CountDownLatch
    ) {
        private val app = context
        var found: String? = null
        private var web: WebView? = null
        private var done = false
        private var requests = 0
        private var scripts = 0

        fun summary(): String = when {
            found != null -> "manifest found"
            web == null && requests == 0 -> "no browser engine on this device"
            requests == 0 -> "page never loaded (no request reached the network)"
            else -> "$requests requests, $scripts script(s), no manifest"
        }

        fun start() {
            try {
                val view = WebView(app)
                web = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.mediaPlaybackRequiresUserGesture = false
                view.settings.loadsImagesAutomatically = false
                view.settings.blockNetworkImage = true
                view.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        // Every request the page makes passes here, including the ones inside a cross-site
                        // iframe, which no script on the page could see.
                        requests++
                        val url = request.url?.toString().orEmpty()
                        if (url.contains(".js", true)) scripts++
                        val media = mediaOf(url)
                        if (media != null) {
                            finish(media)
                            return null
                        }
                        return null
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = Unit

                    override fun onPageFinished(view: WebView, url: String?) {
                        nudge(view)
                    }
                }
                if (referer != null) {
                    val html = """
                        <!doctype html><html><body style="margin:0;background:#000">
                        <iframe src="$target" allow="autoplay; fullscreen; encrypted-media"
                          style="border:0;width:100%;height:100%" referrerpolicy="unsafe-url"></iframe>
                        </body></html>
                    """.trimIndent()
                    view.loadDataWithBaseURL(referer, html, "text/html", "utf-8", null)
                } else {
                    view.loadUrl(target)
                }
                Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, STEP_MS * MAX_STEPS)
                Handler(Looper.getMainLooper()).post { poll(0) }
            } catch (_: Throwable) {
                web = null
                finish(null)
            }
        }

        /** A player that waits for a click cannot call a manifest; ask the page to start the way a viewer does. */
        private fun nudge(view: WebView) {
            val js = """
                (function(){
                  try{
                    var v=document.querySelector('video'); if(v){ v.muted=true; v.play&&v.play().catch(function(){}); }
                    var nodes=document.querySelectorAll('button,[role=button],a,div[class*=play],div[class*=server]');
                    for(var i=0;i<nodes.length && i<12;i++){
                      var t=(nodes[i].textContent||'').toLowerCase();
                      if(/play|server|watch|start/.test(t)){ try{ nodes[i].click(); }catch(e){} break; }
                    }
                  }catch(e){}
                })();
            """.trimIndent()
            try { view.evaluateJavascript(js, null) } catch (_: Throwable) { }
        }

        private fun poll(step: Int) {
            val view = web ?: return
            if (done) return
            if (step >= MAX_STEPS) return finish(null)
            // A second nudge later on: some players attach their handler after the first paint.
            if (step == 6 || step == 14) nudge(view)
            Handler(Looper.getMainLooper()).postDelayed({ poll(step + 1) }, STEP_MS)
        }

        private fun finish(value: String?) {
            if (done) return
            done = true
            found = value
            val view = web
            web = null
            try { view?.stopLoading(); view?.destroy() } catch (_: Throwable) { }
            latch.countDown()
        }
    }
}
