package com.aktv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** TMDB JSON transport, kept injectable so retry/status handling can be tested without the live service. */
internal class CatalogHttpTransport(private val client: OkHttpClient) {
    fun get(url: String): String {
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", Streams.CHROME)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return response.body?.string() ?: throw IOException("empty response body")
                    last = IOException("HTTP ${response.code}")
                }
            } catch (error: Exception) {
                last = error
            }
            Thread.sleep(500L * attempt)
        }
        throw last ?: IOException("no response")
    }
}
