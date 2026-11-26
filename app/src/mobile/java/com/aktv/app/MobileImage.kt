package com.aktv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors

/**
 * The mobile edition's tiny image loader: memory + disk cache, one worker pool. The TV shelf has
 * its own loader tuned for the 10-foot UI; this one is tuned for small scrolling grids.
 */
internal class MobileImage {
    companion object {
        private val mem = LruCache<String, Bitmap>(96)
        private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "aktv-img").apply { isDaemon = true } }
        @Volatile private var disk: java.io.File? = null

        fun init(context: Context) {
            if (disk == null) disk = java.io.File(context.cacheDir, "mobile-img").apply { mkdirs() }
        }
    }

    fun load(url: String, into: ImageView) {
        if (url.isBlank()) return
        into.tag = url
        mem.get(url)?.let { if (into.tag == url) into.setImageBitmap(it); return }
        pool.submit {
            val bitmap = fetch(url)
            if (bitmap != null && into.tag == url) into.post { if (into.tag == url) into.setImageBitmap(bitmap) }
        }
    }

    private fun fetch(url: String): Bitmap? = runCatching {
        val key = url.hashCode().toString(16)
        val cached = disk?.let { java.io.File(it, key) }?.takeIf { it.exists() && it.length() in 1..2_000_000 }
        val bytes = cached?.readBytes() ?: URL(url).openConnection().run {
            connectTimeout = 8_000; readTimeout = 8_000
            getInputStream().use { it.readBytes().takeIf { b -> b.size in 1..2_000_000 } }
        } ?: return null
        if (cached == null) runCatching { disk?.let { java.io.File(it, key).writeBytes(bytes) } }
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { mem.put(url, it) }
    }.getOrNull()
}
