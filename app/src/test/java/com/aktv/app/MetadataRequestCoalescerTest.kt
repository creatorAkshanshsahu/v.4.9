package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MetadataRequestCoalescerTest {
    @Test fun `parallel identical requests share one metadata fetch`() {
        val coalescer = MetadataRequestCoalescer<String, String>()
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<String> {
                coalescer.execute("JJTV:872:fresh") {
                    calls.incrementAndGet(); entered.countDown(); release.await(2, TimeUnit.SECONDS); "source-list"
                }
            }
            check(entered.await(2, TimeUnit.SECONDS))
            val second = pool.submit<String> {
                coalescer.execute("JJTV:872:fresh") { calls.incrementAndGet(); "different-list" }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (coalescer.waitingCount("JJTV:872:fresh") == 0 && System.nanoTime() < deadline) Thread.sleep(1)
            check(coalescer.waitingCount("JJTV:872:fresh") > 0)
            release.countDown()
            assertEquals("source-list", first.get(2, TimeUnit.SECONDS))
            assertEquals("source-list", second.get(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun `different channel and freshness requests do not share`() {
        val coalescer = MetadataRequestCoalescer<String, String>()
        assertEquals("a", coalescer.execute("TG:872:cached") { "a" })
        assertEquals("b", coalescer.execute("TG:873:cached") { "b" })
        assertEquals("c", coalescer.execute("TG:872:fresh") { "c" })
    }

    @Test fun `refusal retry delay is four seconds then ten seconds and bounded`() {
        assertEquals(4_000L, RefusalRetryBackoff.delayFor(0))
        assertEquals(10_000L, RefusalRetryBackoff.delayFor(1))
        assertNull(RefusalRetryBackoff.delayFor(2))
    }

    @Test fun `TG metadata has a hard deadline inside the provider window`() {
        val seconds = LiveMetadataRequests.deadlineSeconds(LiveProvider.TG)
        assertEquals(16L, seconds)
        check(seconds < 25L)
    }
}
