package com.aktv.app

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Use HTTPS response time for credential freshness, not a possibly incorrect TV wall clock.
 * Elapsed time keeps an observed reference advancing even if the user corrects the device date.
 * No timestamps or credentials are persisted.
 */
internal class HttpReferenceClock(
    private val wall: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private var reference: Long? = null
    private var observedAt = 0L
    @Synchronized fun observe(serverTimeMs: Long?) {
        if (serverTimeMs != null) { reference = serverTimeMs; observedAt = elapsed() }
    }
    @Synchronized fun now(): Long = reference?.let { it + (elapsed() - observedAt).coerceAtLeast(0) } ?: wall()

    @Synchronized fun hasReference() = reference != null

    companion object {
        fun parse(date: String?, age: String? = null): Long? {
            if (date == null) return null
            return try {
                val timestamp = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                val seconds = age?.toLongOrNull()?.coerceIn(0, 31_536_000) ?: 0
                Math.addExact(timestamp, seconds * 1000)
            } catch (_: Exception) { null }
        }
    }
}

/** One immediate credential refresh per provider/recovery cycle; never a retry loop or DRM bypass. */
internal class PlaybackAuthRecovery {
    private val refreshed = HashSet<LiveProvider>()
    fun reset() = refreshed.clear()
    fun retry(provider: LiveProvider, httpCode: Int?): Boolean =
        (httpCode == 401 || httpCode == 403) && refreshed.add(provider)
}
