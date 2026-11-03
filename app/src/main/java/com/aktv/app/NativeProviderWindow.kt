package com.aktv.app

/** One provider cannot consume the whole tuning budget before another provider is even attempted.
 * Includes metadata lookup. Source changes and decoder retries do not renew this window.
 * The separate PlaybackStartupBudget still bounds the entire tuning/recovery cycle.
 */
internal class NativeProviderWindow(private val timeoutMs: Long = 25_000) {
    private var deadline: Long? = null
    fun enter(now: Long) { deadline = now + timeoutMs }
    fun recover(now: Long) { if (deadline == null) enter(now) }
    fun rendered() { deadline = null }
    fun clear() { deadline = null }
    fun expired(now: Long) = deadline?.let { now >= it } ?: false
}

/** Failed TG decoding must not force an untried JJTV stream into software mode. */
internal class NativeDecoderRetries {
    private val tried = HashSet<LiveProvider>()
    fun clear() = tried.clear()
    fun used(provider: LiveProvider) = provider in tried
    fun mark(provider: LiveProvider) { tried.add(provider) }
}
