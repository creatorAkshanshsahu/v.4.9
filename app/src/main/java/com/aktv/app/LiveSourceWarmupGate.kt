package com.aktv.app

/** Coalesces focus-driven metadata prefetches without storing signed URLs outside the resolvers' short memory cache. */
internal class LiveSourceWarmupGate(private val cooldownMs: Long = 45_000L) {
    private val lastStarted = HashMap<String, Long>()
    private val inFlight = HashSet<String>()

    @Synchronized fun begin(key: String, elapsedMs: Long): Boolean {
        if (key in inFlight) return false
        val previous = lastStarted[key]
        if (previous != null && elapsedMs >= previous && elapsedMs - previous < cooldownMs) return false
        lastStarted[key] = elapsedMs
        inFlight.add(key)
        return true
    }

    @Synchronized fun finish(key: String) { inFlight.remove(key) }
}
