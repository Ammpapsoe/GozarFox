package com.gozar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedDatagramPipeTest {
    @Test
    fun staleScopeRejectsSendWithoutCallingRelay() {
        val sent = AtomicInteger()
        val closed = AtomicInteger()
        val fake = object : FoxDatagramPipe {
            override fun receive(): ByteArray? = null
            override fun send(payload: ByteArray) { sent.incrementAndGet() }
            override fun close() { closed.incrementAndGet() }
        }
        val scoped = ScopedDatagramPipe(fake) { false }
        assertTrue(runCatching { scoped.send(byteArrayOf(1)) }.isFailure)
        assertEquals(0, sent.get())
        assertEquals(1, closed.get())
    }

    @Test
    fun lateReceiveIsRevokedAcrossFiftyScopeChanges() {
        repeat(50) { iteration ->
            val valid = AtomicBoolean(true)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val published = AtomicBoolean(false)
            val fake = object : FoxDatagramPipe {
                override fun receive(): ByteArray? {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return byteArrayOf(7)
                }
                override fun send(payload: ByteArray) = Unit
                override fun close() { closed.countDown() }
            }
            val scoped = ScopedDatagramPipe(fake) { valid.get() }
            val worker = thread(isDaemon = true) {
                try { scoped.receive(); published.set(true) } catch (_: Exception) { }
            }
            try {
                assertTrue("receive did not start at $iteration", entered.await(5, TimeUnit.SECONDS))
                valid.set(false)
                release.countDown()
                worker.join(5000)
                assertFalse("receive blocked at $iteration", worker.isAlive)
                assertFalse("stale payload published at $iteration", published.get())
                assertTrue("pipe not closed at $iteration", closed.await(1, TimeUnit.SECONDS))
            } finally {
                release.countDown()
                worker.join(5000)
            }
        }
    }
}
