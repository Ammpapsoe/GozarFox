package com.gozar

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
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

class FoxDirectRegistryStressTest {
    private class ControlledSocket(private val block: Boolean) : Socket() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wasClosed = AtomicBoolean(false)
        val output = ByteArrayOutputStream()
        override fun setTcpNoDelay(on: Boolean) = Unit
        override fun setSoTimeout(timeout: Int) = Unit
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            entered.countDown()
            if (block && !release.await(5, TimeUnit.SECONDS)) throw SocketException("test timed out")
            if (wasClosed.get()) throw SocketException("closed")
        }
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = output
        override fun shutdownOutput() = Unit
        override fun close() { wasClosed.set(true) }
    }

    @Test
    fun pendingDirectSocketIsClosedAndCannotBorrowNextAttemptAcrossFiftyRaces() {
        val queued = LinkedBlockingQueue<Socket>()
        val allocations = AtomicInteger()
        val registry = FoxDirectRegistry {
            allocations.incrementAndGet()
            queued.poll(5, TimeUnit.SECONDS) ?: error("missing socket")
        }
        repeat(50) { iteration ->
            val oldTicket = iteration * 2L + 1
            val newTicket = oldTicket + 1
            val oldSocket = ControlledSocket(true)
            val newSocket = ControlledSocket(false)
            val oldResult = AtomicReference<FoxPipe?>()
            val error = AtomicReference<Throwable?>()
            registry.start(oldTicket)
            queued.put(oldSocket)
            val worker = thread(isDaemon = true) {
                try { oldResult.set(registry.connect("127.0.0.1", 443, oldTicket)) }
                catch (failure: Throwable) { error.set(failure) }
            }
            var newer: FoxPipe? = null
            try {
                assertTrue("connect did not start at $iteration", oldSocket.entered.await(5, TimeUnit.SECONDS))
                registry.stop()
                assertTrue("pending socket was not closed at $iteration", oldSocket.wasClosed.get())
                registry.start(newTicket)
                val beforeStaleAttempt = allocations.get()
                assertNull("old ticket was accepted at $iteration", registry.connect("127.0.0.1", 443, oldTicket))
                assertEquals("old ticket allocated a socket", beforeStaleAttempt, allocations.get())
                queued.put(newSocket)
                newer = registry.connect("127.0.0.1", 443, newTicket)
                assertNotNull("new ticket failed at $iteration", newer)
                oldSocket.release.countDown()
                worker.join(5000)
                assertFalse("old connect did not finish at $iteration", worker.isAlive)
                assertNull("old connect returned a pipe", oldResult.get())
                assertNull(error.get())
                newer!!.write(byteArrayOf(42), 0, 1)
                assertEquals(1, newSocket.output.size())
                assertFalse("old cleanup closed new socket", newSocket.wasClosed.get())
            } finally {
                oldSocket.release.countDown()
                worker.join(5000)
                newer?.close()
                registry.stop()
            }
        }
    }

    @Test
    fun establishedDirectPipeRejectsWritesAfterStopAcrossFiftyTransitions() {
        repeat(50) { iteration ->
            val socket = ControlledSocket(false)
            val registry = FoxDirectRegistry { socket }
            val ticket = iteration + 1L
            registry.start(ticket)
            val pipe = registry.connect("127.0.0.1", 443, ticket)
            assertNotNull(pipe)
            try {
                pipe!!.write(byteArrayOf(1), 0, 1)
                registry.stop()
                assertTrue("socket not closed at $iteration", socket.wasClosed.get())
                assertTrue("revoked pipe accepted a write at $iteration", runCatching { pipe.write(byteArrayOf(2), 0, 1) }.isFailure)
                assertEquals("revoked write reached the socket", 1, socket.output.size())
            } finally {
                pipe?.close()
                registry.stop()
            }
        }
    }
}
