package com.aktv.app

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Shares one in-flight metadata lookup for identical provider/channel/freshness requests. */
internal class MetadataRequestCoalescer<K : Any, V : Any> {
    private val inFlight = ConcurrentHashMap<K, CompletableFuture<V>>()
    private val waiting = ConcurrentHashMap<K, AtomicInteger>()

    internal fun waitingCount(key: K): Int = waiting[key]?.get() ?: 0

    fun execute(key: K, task: () -> V): V {
        val mine = CompletableFuture<V>()
        val shared = inFlight.putIfAbsent(key, mine)
        if (shared != null) {
            val count = waiting.computeIfAbsent(key) { AtomicInteger() }
            count.incrementAndGet()
            try {
                return shared.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: ExecutionException) {
                val cause = error.cause
                if (cause is Exception) throw cause
                throw IllegalStateException("Metadata request failed", cause)
            } finally {
                if (count.decrementAndGet() == 0) waiting.remove(key, count)
            }
        }
        try {
            val result = task()
            mine.complete(result)
            return result
        } catch (error: Throwable) {
            mine.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(key, mine)
        }
    }
}

internal object RefusalRetryBackoff {
    private val delays = longArrayOf(4_000, 10_000)
    fun delayFor(retryIndex: Int): Long? = delays.getOrNull(retryIndex)
}
