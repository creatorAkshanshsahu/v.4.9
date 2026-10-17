package com.aktv.app

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

/** The same transport is used by Media3 and JVM redirect/header integration tests. */
internal object StreamHttp {
    private val base = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

    fun client(stream: TgStream, observeManifest: ((HttpReply) -> Unit)? = null): OkHttpClient {
        val policy = StreamRequestPolicy(stream)
        val browserRequests = stream.omitReferrer || (stream.requestOrigin == TgResolver.SITE &&
            stream.headers.keys.none { it.equals("Origin", true) || it.equals("User-Agent", true) })
        // A client per playback session shares connections/dispatcher, never its credentials.
        // Network interceptors run on EVERY redirect, unlike application interceptors.
        return base.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val outgoing = if (!browserRequests) request else request.newBuilder().tag(FetchOrigin::class.java,
                FetchOrigin(stream.requestOrigin, request.url.toString())).build()
            val response = chain.proceed(outgoing)
            if (stream.type in setOf("dash", "hls") && request.url == stream.url.toHttpUrlOrNull()) {
                val reply = HttpReply.inspect(response)
                observeManifest?.invoke(reply)
                if (ManifestReplies.invalid(stream.type, reply)) {
                    response.close()
                    throw ManifestReplyException(reply, stream.type)
                }
            }
            response
        }.addNetworkInterceptor { chain ->
            val original = chain.request()
            val request = policy.request(original.url.toString())
            val builder = original.newBuilder().url(request.url)
            stream.headers.keys.forEach { builder.removeHeader(it) }
            if (stream.omitReferrer) builder.removeHeader("Referer")
            builder.header("User-Agent", TgHttp.USER_AGENT)
            request.headers.forEach { (key, value) -> builder.header(key, value) }
            original.tag(FetchOrigin::class.java)?.let { context ->
                builder.removeHeader("Origin")
                context.visit(original.url.toString())?.let { builder.header("Origin", it) }
            }
            val response = chain.proceed(builder.build())
            // Observe on the network side: OkHttp's BridgeInterceptor restores the caller's
            // original Request on the response (which can still contain the old catalogue token).
            // Call.request identifies the original resource across redirects; response.request
            // here identifies the actual authenticated wire URL. Independent media calls cannot
            // overwrite primary-manifest auth in StreamRequestPolicy.observed.
            if (response.isSuccessful) {
                val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                val playlist = response.request.url.encodedPath.endsWith(".m3u8", true) ||
                    type in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")
                policy.observed(chain.call().request().url.toString(), response.request.url.toString(), playlist)
            }
            response
        }.build()
    }
}
