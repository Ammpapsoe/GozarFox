package com.gozar

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxResolverScopeStressTest {
    private fun query(id: Int) = byteArrayOf(
        0, id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        3, 119, 119, 119, 4, 116, 101, 115, 116, 0, 0, 1, 0, 1
    )

    private fun response(q: ByteArray): ByteArray = q.copyOf().apply {
        this[2] = 0x81.toByte()
        this[3] = 0x80.toByte()
    }

    @Test
    fun invalidatedLookupDoesNotStartFallbackAcrossFiftyRaces() {
        repeat(50) { iteration ->
            val active = AtomicBoolean(true)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val fallbackCalls = AtomicInteger()
            val result = AtomicReference<ByteArray?>()
            val failure = AtomicReference<Throwable?>()
            val resolver = FoxResolver(0, { url, q ->
                if (url == "primary") {
                    started.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) error("primary timed out")
                    null
                } else {
                    fallbackCalls.incrementAndGet()
                    response(q)
                }
            }, endpoints = listOf("primary", "fallback"), raceHeadStartMs = 30_000)
            val scope = FoxDnsScope(Any()) { active.get() }
            val worker = thread(isDaemon = true) {
                try { result.set(resolver.answer(query(iteration), scope)) } catch (error: Throwable) { failure.set(error) }
            }
            try {
                assertTrue("primary did not start at $iteration", started.await(5, TimeUnit.SECONDS))
                active.set(false)
                release.countDown()
                worker.join(5000)
                assertFalse("lookup did not finish at $iteration", worker.isAlive)
                assertNull(failure.get())
                assertNull("old result was returned at $iteration", result.get())
                assertEquals("fallback ran for a stale scope at $iteration", 0, fallbackCalls.get())
            } finally {
                active.set(false)
                release.countDown()
                worker.join(5000)
            }
        }
    }

    @Test
    fun replacementLookupDoesNotJoinOldFlightAcrossFiftyRaces() {
        repeat(50) { iteration ->
            val oldActive = AtomicBoolean(true)
            val oldEntered = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val newDone = CountDownLatch(1)
            val calls = AtomicInteger()
            val oldResult = AtomicReference<ByteArray?>()
            val newResult = AtomicReference<ByteArray?>()
            val failure = AtomicReference<Throwable?>()
            val resolver = FoxResolver(0, { _, q ->
                calls.incrementAndGet()
                if (q[1] == 1.toByte()) {
                    oldEntered.countDown()
                    if (!releaseOld.await(5, TimeUnit.SECONDS)) error("old lookup timed out")
                }
                response(q)
            }, endpoints = listOf("primary"))
            val oldScope = FoxDnsScope(Any()) { oldActive.get() }
            val newScope = FoxDnsScope(Any()) { true }
            val oldWorker = thread(isDaemon = true) {
                try { oldResult.set(resolver.answer(query(1), oldScope)) } catch (error: Throwable) { failure.set(error) }
            }
            var newWorker: Thread? = null
            try {
                assertTrue("old lookup did not start at $iteration", oldEntered.await(5, TimeUnit.SECONDS))
                oldActive.set(false)
                newWorker = thread(isDaemon = true) {
                    try { newResult.set(resolver.answer(query(2), newScope)) } catch (error: Throwable) { failure.set(error) }
                    finally { newDone.countDown() }
                }
                assertTrue("new lookup joined old flight at $iteration", newDone.await(3, TimeUnit.SECONDS))
                assertNotNull("new lookup failed at $iteration", newResult.get())
                assertEquals(2, newResult.get()!![1].toInt() and 255)
                assertEquals("attempts must make separate requests", 2, calls.get())
                releaseOld.countDown()
                oldWorker.join(5000)
                assertFalse(oldWorker.isAlive)
                assertNull(oldResult.get())
                assertNull(failure.get())
            } finally {
                oldActive.set(false)
                releaseOld.countDown()
                oldWorker.join(5000)
                newWorker?.join(5000)
            }
        }
    }

    @Test
    fun cachedRepliesStayWithinTheirScopeAcrossFiftyTransitions() {
        repeat(50) { iteration ->
            val calls = AtomicInteger()
            val resolver = FoxResolver(0, { _, q ->
                calls.incrementAndGet()
                val head = response(q).apply { this[7] = 1 }
                head + byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4)
            }, endpoints = listOf("primary"))
            val first = FoxDnsScope(Any()) { true }
            val replacement = FoxDnsScope(Any()) { true }
            assertNotNull(resolver.answer(query(1), first))
            assertNotNull(resolver.answer(query(2), replacement))
            assertEquals("new scope reused old cache at $iteration", 2, calls.get())
            val hit = resolver.answer(query(3), replacement)
            assertNotNull(hit)
            assertEquals(3, hit!![1].toInt() and 255)
            assertEquals("same-scope cache missed at $iteration", 2, calls.get())
        }
    }

    @Test
    fun invalidatedTcpDnsPipeDiscardsItsAnswerAcrossFiftyRaces() {
        repeat(50) { iteration ->
            val active = AtomicBoolean(true)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val learned = AtomicInteger()
            val failure = AtomicReference<Throwable?>()
            val resolver = FoxResolver(0, { _, q ->
                entered.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) error("lookup timed out")
                response(q)
            }, endpoints = listOf("primary"))
            val scope = FoxDnsScope(Any()) { active.get() }
            val pipe = FoxDnsPipe(resolver, scope) { _, _ -> learned.incrementAndGet() }
            val q = query(iteration)
            val framed = byteArrayOf(0, q.size.toByte()) + q
            val writer = thread(isDaemon = true) {
                try { pipe.write(framed, 0, framed.size) } catch (error: Throwable) { failure.set(error) }
            }
            try {
                assertTrue("lookup did not enter at $iteration", entered.await(5, TimeUnit.SECONDS))
                active.set(false)
                release.countDown()
                writer.join(5000)
                assertFalse(writer.isAlive)
                assertTrue("stale writer did not fail", failure.get() is IOException)
                assertEquals("stale lookup taught a DNS name", 0, learned.get())
            } finally {
                active.set(false)
                release.countDown()
                pipe.close()
                writer.join(5000)
            }
        }
    }
}
