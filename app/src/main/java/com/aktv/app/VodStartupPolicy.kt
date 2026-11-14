package com.aktv.app

import java.util.Locale

/** Keep a failed 404 source behind other advertised alternatives and rank TMDB-exact OG lanes ahead of fuzzy scrapers. */
internal object VodSourceOrder {
    private val FUZZY_OG_SCRAPERS = Regex(
        "^source\\s+(2|3|4|5|6|7|9|10|11)(\\s+proxy)?$",
        RegexOption.IGNORE_CASE
    )

    fun isFuzzyOgScraper(source: FrostSource): Boolean =
        source.provider == FrostProvider.OG && FUZZY_OG_SCRAPERS.matches(source.scraperName.trim())

    fun prioritize(sources: List<FrostSource>, preferredIdentity: String? = null, avoidIdentity: String? = null): List<FrostSource> =
        sources.sortedWith(compareBy<FrostSource>(
            { if (preferredIdentity != null && it.identity == preferredIdentity) 0 else 1 },
            { if (avoidIdentity != null && it.identity == avoidIdentity) 1 else 0 },
            { if (isFuzzyOgScraper(it)) 1 else 0 }
        ))
}

/** Rejects short trailer/bumper/decoy clips (such as a 1-minute anime clip returned for a full-length movie). */
internal object VodDurationGuard {
    const val DEFAULT_MIN_MOVIE_PROBE_SECONDS = 210
    private const val UNKNOWN_MOVIE_MIN_MS = 210_000L
    private const val FEATURE_FLOOR_MS = 240_000L
    private const val SERIES_BUMPER_MAX_MS = 45_000L

    fun parseMinutes(runtime: String?): Int {
        val raw = runtime?.trim()?.lowercase(Locale.US).orEmpty()
        if (raw.isEmpty()) return 0
        val hm = Regex("(\\d{1,2})\\s*h(?:\\s*(\\d{1,2})\\s*m)?").find(raw)
        if (hm != null) {
            val hours = hm.groupValues[1].toIntOrNull() ?: 0
            val mins = hm.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            return (hours * 60 + mins).coerceAtLeast(0)
        }
        val mOnly = Regex("(\\d{1,3})\\s*m").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        if (mOnly != null) return mOnly.coerceAtLeast(0)
        return raw.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    fun minProbeSeconds(isSeries: Boolean, expectedRuntimeMinutes: Int): Int {
        if (isSeries) return 45
        if (expectedRuntimeMinutes in 1..18) return 0
        return DEFAULT_MIN_MOVIE_PROBE_SECONDS
    }

    fun isBogusShortClip(
        isSeries: Boolean,
        expectedRuntimeMinutes: Int,
        durationMs: Long,
        hasAlternativeSources: Boolean
    ): Boolean {
        if (!hasAlternativeSources || durationMs <= 0L) return false
        if (isSeries) return durationMs < SERIES_BUMPER_MAX_MS
        if (expectedRuntimeMinutes in 1..18) return false
        if (expectedRuntimeMinutes >= 25) {
            val minFeatureMs = maxOf(FEATURE_FLOOR_MS, (expectedRuntimeMinutes * 60_000L) / 3L)
            return durationMs < minFeatureMs
        }
        return durationMs < UNKNOWN_MOVIE_MIN_MS
    }
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
