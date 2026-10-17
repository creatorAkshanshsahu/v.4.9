package com.aktv.app

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The same transport is used by Media3 and JVM redirect/header integration tests. */
internal object StreamHttp {
    private val base = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

    fun client(stream: TgStream): OkHttpClient {
        val policy = StreamRequestPolicy(stream)
        // A client per playback session shares connections/dispatcher, never its credentials.
        // Network interceptors run on EVERY redirect, unlike application interceptors.
        return base.newBuilder().addInterceptor { chain ->
            // This sees the requested resource and the final redirect together. Learning from each
            // network response would let an independently signed segment poison later track requests.
            val initial = chain.request()
            val response = chain.proceed(initial)
            if (response.isSuccessful) policy.observed(initial.url.toString(), response.request.url.toString())
            response
        }.addNetworkInterceptor { chain ->
            val original = chain.request()
            val request = policy.request(original.url.toString())
            val builder = original.newBuilder().url(request.url)
            stream.headers.keys.forEach { builder.removeHeader(it) }
            if (stream.omitReferrer) builder.removeHeader("Referer")
            builder.header("User-Agent", TgHttp.USER_AGENT)
            request.headers.forEach { (key, value) -> builder.header(key, value) }
            chain.proceed(builder.build())
        }.build()
    }
}
