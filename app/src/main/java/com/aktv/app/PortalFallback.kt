package com.aktv.app

/**
 * Same-channel lookup across Stalker portals: when a Live TV tune exhausts its own sources, the
 * viewer gets the same channel from another working portal instead of a dead end.
 *
 * Matching is deliberately strict — a cleaned exact match, or the same ignoring a trailing quality
 * tag (`HD`, `4K`, …). Language stays part of the identity: `Star Sports 1 Hindi` never matches
 * `Star Sports 1 Tamil`.
 */
internal object PortalFallback {
    private val QUALITY = setOf("hd", "fhd", "uhd", "4k", "8k", "sd", "hq", "hevc", "h265")

    /** Lowercased alphanumeric identity: punctuation, spaces and case never distinguish channels. */
    fun key(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /** [key] without a trailing quality tag, so `Zee Cinema HD` still finds `Zee Cinema`. */
    fun baseKey(name: String): String {
        val tokens = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val trimmed = if (tokens.size > 1 && tokens.last() in QUALITY) tokens.dropLast(1) else tokens
        return trimmed.joinToString("")
    }

    /**
     * Index into [names] of the same channel as [name], or -1. Exact cleaned matches win; quality
     * variants are second choice.
     */
    fun matchIndex(name: String, names: List<String>): Int {
        val want = key(name)
        if (want.isEmpty()) return -1
        names.forEachIndexed { index, candidate -> if (key(candidate) == want) return index }
        val wantBase = baseKey(name)
        if (wantBase.isNotEmpty()) {
            names.forEachIndexed { index, candidate -> if (baseKey(candidate) == wantBase) return index }
        }
        return -1
    }

    /** Ephemeral "X Vs Y" event streams the feed prepends: gone tomorrow, matching no subscription. */
    fun isEventStream(id: String): Boolean = id.startsWith("ls-")

    /**
     * Ordered twin hunt across portal name-lists: one hit per pool, pools keeps their order, so the
     * caller plays the viewer's own subscription before the shared ones. Each hit is a pool index
     * together with the channel index inside that pool.
     */
    fun twinIndices(name: String, pools: List<List<String>>): List<Pair<Int, Int>> =
        pools.mapIndexedNotNull { poolIndex, names ->
            matchIndex(name, names).takeIf { it >= 0 }?.let { poolIndex to it }
        }

    /**
     * A portal is settled for tune time when waiting longer cannot surface a channel we have not
     * seen: unconfigured, already holding channels, or finished loading (successfully or after its
     * retry). Anything else means a first playlist is still on its way and a tap should wait for it.
     */
    fun portalSettled(configured: Boolean, hasChannels: Boolean, inFlight: Boolean, loaded: Boolean, retried: Boolean): Boolean {
        if (!configured) return true
        if (hasChannels) return true
        if (inFlight) return false
        return loaded || retried
    }
}
