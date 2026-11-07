package com.gozar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministically overlap a leader and waiters on an uncacheable failure. */
class FoxResolverFlightStressTest {
    private fun query(id: Int): ByteArray = byteArrayOf(
        (id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        3, 111, 110, 101, 4, 116, 101, 115, 116, 0, 0, 1, 0, 1
    )

    private fun servfail(query: ByteArray): ByteArray = query.copyOf().apply {
        this[2] = 0x81.toByte()
        this[3] = 0x82.toByte()
    }

    @Test
    fun failedConcurrentLookupsShareOneFlightAndLaterRequestsRetryAcrossFiftyRaces() {
        repeat(50) { race ->
            val calls = AtomicInteger()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val waitersStarted = CountDownLatch(6)
            val done = CountDownLatch(7)
            val responses = arrayOfNulls<ByteArray>(7)
            val resolver = FoxResolver(
                0,
                request = { _, question ->
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                    servfail(question)
                },
                endpoints = listOf("https://resolver.example/dns-query")
            )
            thread(isDaemon = true, name = "dns-leader-$race") {
                try { responses[0] = resolver.answer(query(100)) } finally { done.countDown() }
            }
            try {
                assertTrue("leader did not reach the gated request in race $race", entered.await(5, TimeUnit.SECONDS))
                val waiters = (1..6).map { index ->
                    thread(isDaemon = true, name = "dns-waiter-$race-$index") {
                        waitersStarted.countDown()
                        try { responses[index] = resolver.answer(query(100 + index)) } finally { done.countDown() }
                    }
                }
                assertTrue(waitersStarted.await(5, TimeUnit.SECONDS))
                // Each waiter must be inside answer(), blocked on the leader,
                // before that leader is allowed to finish. This is not a sleep
                // used as a substitute for overlapping the actual race.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (waiters.any { it.state != Thread.State.TIMED_WAITING } && System.nanoTime() < deadline) Thread.yield()
                assertTrue("waiters did not all join the pending flight in race $race", waiters.all {
                    it.state == Thread.State.TIMED_WAITING
                })
            } finally {
                release.countDown()
            }
            assertTrue("flight did not finish in race $race", done.await(10, TimeUnit.SECONDS))
            assertEquals("concurrent failed lookup duplicated upstream work in race $race", 1, calls.get())
            responses.forEachIndexed { index, body ->
                assertNotNull("missing response for waiter $index in race $race", body)
                assertEquals(100 + index, body!![1].toInt() and 255)
                assertEquals(2, body[3].toInt() and 15)
            }
            // Failure must be shared, not cached: a later request tries again.
            assertEquals(2, resolver.answer(query(200))!![3].toInt() and 15)
            assertEquals("failure was accidentally cached in race $race", 2, calls.get())
        }
    }
}
