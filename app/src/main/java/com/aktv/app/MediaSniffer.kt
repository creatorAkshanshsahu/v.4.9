package com.aktv.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import java.util.concurrent.CountDownLatch
import okhttp3.OkHttpClient
import okhttp3.Request
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

    /** Set once at start-up: the browser engine needs a context, and the resolvers run off the UI thread. */
    @Volatile var appContext: Context? = null

    /** What the last attempt saw, for the player's own report: "41 requests, 7 script(s), no manifest". */
    @Volatile var lastSummary: String = "not attempted"

    private const val KEEP_AFTER_FIRST_MS = 6_000L
    private val AUDIO_ONLY = Regex("""(?i)(audio|/a[0-9]+/|_a\.m3u8|aud_)""")

    fun find(context: Context, target: String, referer: String? = null, timeoutMs: Long = STEP_MS * MAX_STEPS): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        if (!SiteBrowserGate.allowed) {
            lastSummary = SiteBrowserGate.summary()
            return null
        }
        val latch = CountDownLatch(1)
        val steps = (timeoutMs / STEP_MS).toInt().coerceIn(6, MAX_STEPS)
        val reader = Reader(context.applicationContext, target, referer, steps, latch)
        Handler(Looper.getMainLooper()).post { reader.start() }
        val ok = try {
            latch.await(STEP_MS * steps + 8_000, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
        val found = if (ok) reader.hits.toList() else emptyList<String>()
        // The page may fetch an audio rendition before the real one, so a manifest is not the answer until it
        // has been read: a master with video variants is the one the native player needs, or the player would
        // play sound over a black screen and never finish starting.
        val chosen = choose(found)
        lastSummary = when {
            chosen != null -> if (found.size > 1) "manifest found (${found.size} candidates)" else "manifest found"
            found.isNotEmpty() -> "${found.size} manifest(s), none carries video"
            else -> reader.summary()
        }
        return chosen
    }

    /** The master playlist that lists video variants, else the first manifest that is not an audio track. */
    private fun choose(found: List<String>): String? {
        if (found.isEmpty()) return null
        for (url in found.take(6)) {
            val body = peek(url) ?: continue
            if (body.contains("#EXT-X-STREAM-INF", true) && body.contains("RESOLUTION", true)) return url
        }
        for (url in found.take(6)) {
            if (AUDIO_ONLY.containsMatchIn(url)) continue
            val body = peek(url) ?: return url
            if (body.contains("#EXT-X-STREAM-INF", true) || body.contains("#EXTINF", true)) return url
        }
        return found.firstOrNull { !AUDIO_ONLY.containsMatchIn(it) }
    }

    /** Reads a manifest directly, so the choice is made on what it contains rather than on its name. */
    private fun peek(url: String): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", TgHttp.USER_AGENT)
            .header("Referer", "https://theogpiratebot.online/").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.byteStream()?.let { input ->
                val buffer = ByteArray(64 * 1024)
                var read = 0
                while (read < buffer.size) {
                    val count = input.read(buffer, read, buffer.size - read)
                    if (count < 0) break
                    read += count
                }
                String(buffer, 0, read, Charsets.UTF_8)
            }
        }
    } catch (_: Exception) {
        null
    }

    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()

    private val MEDIA_IN_TEXT = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

    /** Media addresses named inside a response body (a player's JSON often names its own manifest). */
    fun mediaIn(body: String): List<String> =
        MEDIA_IN_TEXT.findAll(body.replace("\\/", "/")).mapNotNull { mediaOf(it.value) }.take(4).toList()

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
        private val maxSteps: Int,
        private val latch: CountDownLatch
    ) {
        private val app = context
        val hits = LinkedHashSet<String>()
        private var web: WebView? = null
        private var done = false
        private var requests = 0
        private var scripts = 0
        private var firstAt = 0L

        fun summary(): String = when {
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
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        install(view)
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        // Every request the page makes passes here, including the ones inside a cross-site
                        // iframe, which no script on the page could see.
                        requests++
                        val url = request.url?.toString().orEmpty()
                        if (url.contains(".js", true)) scripts++
                        mediaOf(url)?.let { media ->
                            // Keep watching for a moment: a master playlist usually follows an audio
                            // rendition, and the master is the one that carries the picture.
                            if (hits.isEmpty()) firstAt = System.currentTimeMillis()
                            hits += media
                        }
                        return null
                    }


                    override fun onPageFinished(view: WebView, url: String?) {
                        // A page callback must never take the app down with it.
                        try { install(view); nudge(view, 4) } catch (_: Throwable) { }
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
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, STEP_MS * maxSteps)
                Handler(Looper.getMainLooper()).post { poll(0) }
            } catch (_: Throwable) {
                web = null
                finish()
            }
        }

        /**
         * A player that waits for a click cannot call a manifest; ask the page to start the way a viewer does.
         *
         * TGFLIX opens with "Who's Watching?", so a profile is chosen first - exactly the click a viewer makes
         * - and the title is then opened again by address, because that screen (or the home screen it leads to)
         * replaced the page that was asked for. The reopen happens at most once, so it cannot loop.
         */
        private fun nudge(view: WebView, step: Int) {
            // The address is quoted for the script; the route must be read from the real one. Shadowing the
            // field with the quoted form threw URISyntaxException on every page load (4.9.73 crash).
            val route = org.json.JSONObject.quote(
                runCatching { URI(this.target).path.orEmpty() }.getOrDefault("")
            )
            val targetJs = org.json.JSONObject.quote(this.target)
            val late = if (step >= 9) 1 else 0
            val js = """
                (function(){
                  var gate = false;
                  try{
                    var t=((document.body&&document.body.innerText)||'').toLowerCase();
                    gate = t.indexOf("who's watching") >= 0 || t.indexOf('add profile') >= 0;
                  }catch(e){}
                  if (gate) {
                    try{
                      var cands=document.querySelectorAll('button,a,[role=button],img,div');
                      for(var i=0;i<cands.length;i++){
                        var e=cands[i];
                        var s=((e.className||'')+' '+(e.alt||'')+' '+(e.textContent||'')).toString().toLowerCase();
                        if(s.indexOf('avatar')>=0||s.indexOf('profile')>=0||s.indexOf('continue')>=0||s.indexOf('guest')>=0){
                          e.click(); break;
                        }
                      }
                    }catch(e){}
                  }
                  try{
                    var v=document.querySelector('video'); if(v){ v.muted=true; v.play&&v.play().catch(function(){}); }
                    var nodes=document.querySelectorAll('button,[role=button],a,div[class*=play],div[class*=server]');
                    for(var i=0;i<nodes.length && i<12;i++){
                      var tt=(nodes[i].textContent||'').toLowerCase();
                      if(/play|server|watch|start/.test(tt)){ try{ nodes[i].click(); }catch(e){} break; }
                    }
                  }catch(e){}
                  try{
                    if(!window.__akReopen){
                      var here = location.pathname + location.search;
                      var lost = here.indexOf($route) < 0;
                      var noVideo = !document.querySelector('video');
                      if(gate || ($late===1 && lost && noVideo)){
                        window.__akReopen = 1;
                        setTimeout(function(){ location.href = $targetJs; }, 2500);
                      }
                    }
                  }catch(e){}
                })();
            """.trimIndent()
            try { view.evaluateJavascript(js, null) } catch (_: Throwable) { }
        }

        /** Records the bodies of the page's own fetch/XHR answers; a manifest is often only named inside one. */
        private fun install(view: WebView) {
            val js = """
                (function(){
                  if (window.__akBodies) return; window.__akBodies = [];
                  var keep=function(t){ try{ if(t&&t.length<400000){ window.__akBodies.push(t); if(window.__akBodies.length>8) window.__akBodies.shift(); } }catch(e){} };
                  var of=window.fetch;
                  if(of) window.fetch=function(){ var p=of.apply(this,arguments);
                    try{ p.then(function(r){ try{ r.clone().text().then(keep); }catch(e){} }); }catch(e){} return p; };
                  var oo=XMLHttpRequest.prototype.open;
                  XMLHttpRequest.prototype.open=function(){ try{ this.addEventListener('load', function(){ try{ keep(this.responseText); }catch(e){} }); }catch(e){}
                    return oo.apply(this,arguments); };
                })();
            """.trimIndent()
            try { view.evaluateJavascript(js, null) } catch (_: Throwable) { }
        }

        private fun poll(step: Int) {
            val view = web ?: return
            if (done) return
            if (step % 3 == 0) readBodies(view)
            val keepUntil = firstAt + KEEP_AFTER_FIRST_MS
            if (hits.isNotEmpty() && System.currentTimeMillis() >= keepUntil) return finish()
            if (step >= maxSteps) return finish()
            // A second nudge later on: some players attach their handler after the first paint.
            if (step == 4 || step == 9 || step == 15) try { nudge(view, step) } catch (_: Throwable) { }
            Handler(Looper.getMainLooper()).postDelayed({ poll(step + 1) }, STEP_MS)
        }

        private fun readBodies(view: WebView) {
            val js = "JSON.stringify(window.__akBodies||[])"
            try {
                view.evaluateJavascript(js) { raw ->
                    val list = decode(raw) ?: return@evaluateJavascript
                    for (body in list) {
                        MediaSniffer.mediaIn(body).forEach { hit ->
                            if (hits.isEmpty()) firstAt = System.currentTimeMillis()
                            hits += hit
                        }
                    }
                }
            } catch (_: Throwable) { }
        }

        private fun finish() {
            if (done) return
            done = true
            val view = web
            web = null
            try { view?.stopLoading(); view?.destroy() } catch (_: Throwable) { }
            latch.countDown()
        }
    }
}
