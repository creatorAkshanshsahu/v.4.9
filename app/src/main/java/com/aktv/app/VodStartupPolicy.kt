package com.aktv.app

/** Keep a failed 404 source behind other advertised alternatives after refreshing its metadata. */
internal object VodSourceOrder {
    fun prioritize(sources: List<FrostSource>, preferredIdentity: String? = null, avoidIdentity: String? = null): List<FrostSource> =
        sources.sortedWith(compareBy<FrostSource>(
            { if (preferredIdentity != null && it.identity == preferredIdentity) 0 else 1 },
            { if (avoidIdentity != null && it.identity == avoidIdentity) 1 else 0 }
        ))
}

/** One-click startup retries transient metadata and one 404 refresh per provider, then falls back. */
internal object VodSourceConfirmation {
    const val REQUIRED_STABLE_PLAYBACK_MS = 15_000L

    fun shouldRemember(
        frameRendered: Boolean, ready: Boolean, playing: Boolean,
        elapsedSinceFrameMs: Long, playbackAdvancedMs: Long
    ): Boolean = frameRendered && ready && playing &&
        elapsedSinceFrameMs >= REQUIRED_STABLE_PLAYBACK_MS && playbackAdvancedMs >= 5_000L
}

internal class VodStartupPolicy {
    private val metadataRetried = HashSet<FrostProvider>()
    private val missingSourceRefreshed = HashSet<FrostProvider>()

    fun reset() {
        metadataRetried.clear()
        missingSourceRefreshed.clear()
    }

    fun retryMetadata(provider: FrostProvider, status: Int?, kind: String?): Boolean {
        val transient = when {
            status == 404 || (status != null && status in 500..599) -> true
            status != null -> false // 401/403/429/451 are not retried as metadata refreshes.
            else -> kind in setOf("empty", "timeout", "dns", "unavailable")
        }
        return transient && metadataRetried.add(provider)
    }

    fun refreshNotFound(provider: FrostProvider, status: Int?): Boolean =
        status == 404 && missingSourceRefreshed.add(provider)
}
