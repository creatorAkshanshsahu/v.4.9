package com.aktv.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the media address a site's own player uses, by running that page in a browser and watching it work.
 *
 * The OG player does not name its stream in the page it serves: the page loads an embed, the embed runs a
 * script, and only then is the manifest requested. Nothing in the HTML says where the video is, which is why
 * scraping it found no address on any of the three servers. This runs the page exactly as the site does, then
 * reports the manifest/video request the page itself made, so the app can play that same stream natively.
 */
internal object MediaSniffer {

    private const val STEP_MS = 600L
    private const val MAX_STEPS = 34          // ~20 s
    private val MEDIA = Regex("""https?://[^\s"'\\<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'\\<>]*)?""", RegexOption.IGNORE_CASE)

    /** The media URL the page itself asked for, or null when it never asked for one. */
    fun find(context: Context, url: String): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val latch = CountDownLatch(1)
        val reader = Reader(context.applicationContext, url, latch)
        Handler(Looper.getMainLooper()).post { reader.start() }
        return try {
            if (!latch.await(STEP_MS * MAX_STEPS + 5_000, TimeUnit.MILLISECONDS)) null else reader.found
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); null
        }
    }

    private class Reader(context: Context, private val url: String, private val latch: CountDownLatch) {
        private val app = context
        var found: String? = null
        private var web: WebView? = null
        private var done = false

        fun start() {
            try {
                val view = WebView(app)
                web = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.mediaPlaybackRequiresUserGesture = false
                view.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, address: String?, favicon: android.graphics.Bitmap?) {
                        install(view)
                    }
                }
                view.loadUrl(url)
                Handler(Looper.getMainLooper()).post { poll(0) }
            } catch (_: Throwable) {
                finish(null)
            }
        }

        /** Records every address the page's own scripts ask for, and the bodies of their JSON answers. */
        private fun install(view: WebView) {
            val js = """
                (function(){
                  if (window.__ak) return; window.__ak = [];
                  var add=function(u){try{ if(!u)return; u=String(u); if(u.length<4000) window.__ak.push(u); }catch(e){}};
                  var of=window.fetch;
                  if(of) window.fetch=function(a,b){ try{ add((a&&a.url)||a); }catch(e){}
                    var p=of.apply(this,arguments);
                    try{ p.then(function(r){ try{ r.clone().text().then(function(t){ if(t&&t.length<400000) window.__ak.push('BODY:'+t); }); }catch(e){} }); }catch(e){}
                    return p; };
                  var oo=XMLHttpRequest.prototype.open;
                  XMLHttpRequest.prototype.open=function(m,u){ try{ add(u); }catch(e){}
                    try{ this.addEventListener('load', function(){ try{ var t=this.responseText; if(t&&t.length<400000) window.__ak.push('BODY:'+t); }catch(e){} }); }catch(e){}
                    return oo.apply(this,arguments); };
                  try{ var d=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');
                    Object.defineProperty(HTMLMediaElement.prototype,'src',{ configurable:true,
                      get:d.get, set:function(v){ try{ add(v); }catch(e){}; return d.set.call(this,v); } }); }catch(e){}
                })();
            """.trimIndent()
            try { view.evaluateJavascript(js, null) } catch (_: Throwable) { }
        }

        private fun poll(step: Int) {
            val view = web ?: return finish(null)
            if (done) return
            val js = """
                (function(){
                  var out=[]; try{ var e=performance.getEntriesByType('resource');
                    for(var i=0;i<e.length;i++) out.push(e[i].name); }catch(err){}
                  if(window.__ak) out=out.concat(window.__ak);
                  // Some players wait for a gesture: nudge the page the way a viewer would.
                  try{ if(!window.__akStart){ window.__akStart=1;
                    var v=document.querySelector('video'); if(v){ v.muted=true; v.play&&v.play().catch(function(){}); }
                  } }catch(err){}
                  return JSON.stringify(out.length>900?out.slice(out.length-900):out);
                })()
            """.trimIndent()
            try {
                view.evaluateJavascript(js) { raw ->
                    val list = decode(raw)
                    val media = list?.firstNotNullOfOrNull { hit(it) }
                    if (media != null) return@evaluateJavascript finish(media)
                    if (step < MAX_STEPS) Handler(Looper.getMainLooper()).postDelayed({ poll(step + 1) }, STEP_MS)
                    else finish(null)
                }
            } catch (_: Throwable) {
                finish(null)
            }
        }

        /** A manifest or video file inside one recorded address or one recorded response body. */
        private fun hit(entry: String): String? {
            val text = if (entry.startsWith("BODY:")) entry else entry.replace("\\/", "/")
            val match = MEDIA.find(text)?.value ?: return null
            val clean = match.replace("\\/", "/").trimEnd('\\', '"', '\'')
            if (clean.contains(".mpd")) return null                       // Widevine: never offered
            if (clean.contains("ads") || clean.contains("/ad/")) return null
            return clean
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

        private fun decode(raw: String?): List<String>? = try {
            if (raw.isNullOrBlank() || raw == "null") null
            else {
                // evaluateJavascript answers with a JSON string literal holding the JSON array.
                val arr = JSONArray(JSONObject("{\"v\":$raw}").getString("v"))
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotEmpty() } }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
