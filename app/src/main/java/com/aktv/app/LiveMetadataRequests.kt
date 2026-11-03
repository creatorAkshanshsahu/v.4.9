package com.aktv.app

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class LiveMetadataTimeoutException(val provider: LiveProvider) : IOException("Live provider metadata deadline reached")

/** Coalesce identical playback metadata work and keep TG lookup comfortably inside its 25 s provider window. */
internal object LiveMetadataRequests {
    private val requests = MetadataRequestCoalescer<String, List<TgStream>>()
    private val network = Executors.newFixedThreadPool(2) { r -> Thread(r, "aktv-live-metadata").apply { isDaemon = true } }
    internal fun deadlineSeconds(provider: LiveProvider): Long = if (provider == LiveProvider.TG) 16L else 20L

    fun fetch(provider: LiveProvider, id: String, fresh: Boolean = false, v2: Boolean = false): List<TgStream> =
        requests.execute("${provider.name}:${if (v2) "v2" else "v1"}:$id:${if (fresh) "fresh" else "cached"}") {
            val future = network.submit(Callable { TgServers.fetchFrom(provider, id, fresh, v2) })
            try {
                future.get(deadlineSeconds(provider), TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                throw LiveMetadataTimeoutException(provider)
            } catch (error: InterruptedException) {
                future.cancel(true); Thread.currentThread().interrupt(); throw error
            } catch (error: ExecutionException) {
                val cause = error.cause
                if (cause is Exception) throw cause
                throw IllegalStateException("Live metadata request failed", cause)
            }
        }.let { WebsiteRequestParity.playbackSources(provider, it) }
}
