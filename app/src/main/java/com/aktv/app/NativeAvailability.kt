package com.aktv.app

import java.net.URI
import java.security.MessageDigest

/** Session-local refusals and bounded recovery. Does not persist or print URLs/credentials. */
internal class NativeAvailability {
    private val denied = HashMap<String, Long>()
    private val lookups = HashMap<LiveProvider, Pair<Long, PlaybackDiagnostics.Problem>>()
    private val recoverable = linkedMapOf<LiveProvider, Pair<Long, PlaybackDiagnostics.Problem>>()
    private val challenges = HashMap<LiveProvider, PlaybackDiagnostics.Problem>()
    private var checks = 0
    private var skipped = 0

    fun reset() {
        denied.clear(); lookups.clear(); recoverable.clear(); challenges.clear()
        checks = 0; skipped = 0
    }

    fun failed(source: TgStream?, provider: LiveProvider, failure: PlaybackDiagnostics.Problem, now: Long, metadata: Boolean = false) {
        if (failure.reply?.challenge == true) {
            challenges[provider] = failure
            recoverable.remove(provider)
        } else if (eligible(failure) && provider !in challenges) recoverable[provider] = now to failure
        else recoverable.remove(provider)
        if (failure.http in setOf(401, 403, 429) || failure.family == PlaybackDiagnostics.Family.CREDENTIALS) {
            val base = if (failure.family == PlaybackDiagnostics.Family.CREDENTIALS) 30_000L else 60_000L
            val wait = maxOf(base, failure.reply?.retryAfterMs ?: 0L)
            val until = if (wait > Long.MAX_VALUE - now) Long.MAX_VALUE else now + wait
            if (metadata || failure.http == 429) lookups[provider] = until to failure
            else source?.let(::requestKey)?.let { key ->
                if (denied.size >= 128) denied.entries.removeAll { it.value <= now }
                if (denied.size < 128) denied[key] = until
            }
        }
    }

    fun lookupRefusal(provider: LiveProvider, now: Long): PlaybackDiagnostics.Problem? {
        challenges[provider]?.let { skipped++; return it }
        val value = lookups[provider] ?: return null
        if (now >= value.first) { lookups.remove(provider); return null }
        skipped++
        return value.second
    }

    fun requestRefused(source: TgStream, now: Long): Boolean {
        val provider = LiveProviders.provider(source)
        if (provider in challenges || lookups[provider]?.let { it.first > now && it.second.http == 429 } == true) {
            skipped++; return true
        }
        val key = requestKey(source) ?: return false
        val until = denied[key] ?: return false
        if (now >= until) { denied.remove(key); return false }
        skipped++
        return true
    }

    /** Gentle checks only while the user remains on this channel. Challenges are not retried.
     * Another provider's decoder/DRM failure does not erase an eligible credential renewal.
     * Long Retry-After values stop work on that provider, rather than being clamped shorter.
     */
    fun nextCheckDelay(now: Long): Long? {
        if (checks >= 3) return null
        val waits = recoverable.filterKeys { it !in challenges }.values.mapNotNull { (at, failure) ->
            val retryAfter = ((failure.reply?.retryAfterMs ?: 0L) - (now - at).coerceAtLeast(0)).coerceAtLeast(0)
            if (retryAfter > 5 * 60_000) null else retryAfter
        }
        val providerWait = waits.minOrNull() ?: return null
        val wait = maxOf(listOf(30_000L, 60_000L, 120_000L)[checks], providerWait)
        checks++
        return wait
    }

    fun report() = "Automatic checks: $checks / 3; unchanged recent refusals skipped: $skipped" +
        if (challenges.isNotEmpty()) "; challenge-blocked providers: ${challenges.keys.joinToString { it.name }}" else ""

    private fun eligible(failure: PlaybackDiagnostics.Problem) =
        failure.http in setOf(401, 403, 408, 429, 500, 502, 503, 504) ||
            failure.family in setOf(PlaybackDiagnostics.Family.NETWORK, PlaybackDiagnostics.Family.CREDENTIALS)

    companion object {
        /** Only a direct Jio request can be compared before opening it. A relay can issue a NEW
         * signed redirect on the same URL, so it must not be suppressed just because its label/URL
         * stayed the same. DRM/key changes do not change the HTTP request's authorization.
         */
        fun requestKey(source: TgStream): String? {
            val uri = try { URI(source.url) } catch (_: Exception) { return null }
            if (uri.scheme != "https" || uri.host?.lowercase() !in setOf("jiotvmblive.cdn.jio.com", "jiotvpllive.cdn.jio.com") ||
                uri.port !in setOf(-1, 443) || !uri.path.orEmpty().startsWith("/bpk-tv/")) return null
            val request = StreamRequestPolicy(source).request(source.url)
            val headers = request.headers.mapKeys { it.key.lowercase() }.toMutableMap()
            headers.putIfAbsent("user-agent", TgHttp.USER_AGENT)
            val material = source.requestOrigin + "\n" + source.omitReferrer + "\n" + request.url + "\n" +
                headers.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }
            return MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}

/** Pure guard for delayed work; changing channel/session or leaving the foreground cancels it. */
internal data class NativeRecheck(val channel: String, val generation: Int, val dueAt: Long) {
    fun remaining(now: Long, currentChannel: String?, currentGeneration: Int, visible: Boolean, drmCheck: Boolean): Long? =
        if (channel != currentChannel || generation != currentGeneration || !visible || drmCheck) null
        else (dueAt - now).coerceAtLeast(0)
}
