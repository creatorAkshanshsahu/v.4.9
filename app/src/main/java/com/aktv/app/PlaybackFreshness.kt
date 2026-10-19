package com.aktv.app

/**
 * Decides when a resolved source should be replaced before the player starts.
 *
 * A signed Jio credential can expire between resolution and the first manifest request. A TV box
 * spends noticeably longer than a phone between those two moments (cold Media3, surface, codec and
 * DRM setup), so a credential that is "still valid" at resolve time can already be stale when the
 * manifest is fetched, producing an HTTP 403 that looks like a device-specific refusal.
 *
 * This only refetches the provider's current configuration; it never extends, guesses or re-signs
 * a credential. Callers bound it to one extra lookup per provider per playback session.
 */
internal object PlaybackFreshness {
    /** Phone-sized startup budget: a credential inside its last 30 s is refreshed before playing. */
    const val PHONE_MARGIN_MS = 30_000L

    /** TV-sized startup budget: older boxes need a wider window than a phone. */
    const val TV_MARGIN_MS = 60_000L

    fun marginMs(television: Boolean): Long = if (television) TV_MARGIN_MS else PHONE_MARGIN_MS

    /**
     * True only when at least one advertised source carries a readable expiry and the earliest one
     * falls inside [marginMs]. Opaque credentials (no readable expiry) are left to the provider and
     * the existing rejection recovery, so this can never force a loop or invent a token.
     */
    fun needsRefresh(sources: List<TgStream>, now: Long, marginMs: Long): Boolean {
        if (sources.isEmpty()) return false
        val earliest = sources.mapNotNull { StreamRequestPolicy.expiry(it.url, it.cookie) }.minOrNull() ?: return false
        return earliest - now < marginMs
    }

    /** Remaining validity in whole seconds, for the redacted report only. Null when unreadable. */
    fun remainingSeconds(sources: List<TgStream>, now: Long): Long? {
        val earliest = sources.mapNotNull { StreamRequestPolicy.expiry(it.url, it.cookie) }.minOrNull() ?: return null
        return (earliest - now) / 1000
    }
}
