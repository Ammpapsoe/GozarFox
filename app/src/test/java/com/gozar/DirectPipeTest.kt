package com.gozar

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPipeTest {
    private class Pipe : FoxPipe {
        var closed = false
        var written = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun write(buffer: ByteArray, offset: Int, length: Int) { written += length }
        override fun close() { closed = true }
    }

    @Test
    fun directWritesStopWhenSystemProtectionChanges() {
        val allowed = AtomicBoolean(true)
        val raw = Pipe()
        val guarded = DirectPipe(raw) { allowed.get() }
        guarded.write(byteArrayOf(1, 2), 0, 2)
        assertEquals(2, raw.written)
        assertFalse(raw.closed)
        allowed.set(false)
        assertThrows(IOException::class.java) { guarded.write(byteArrayOf(3), 0, 1) }
        assertEquals(2, raw.written)
        assertTrue(raw.closed)
    }

    @Test
    fun closingAllDirectPipesAlsoClosesIdleConnections() {
        val first = Pipe()
        val second = Pipe()
        DirectPipe(first) { true }
        DirectPipe(second) { true }
        DirectPipe.closeAll()
        assertTrue(first.closed)
        assertTrue(second.closed)
    }

    @Test
    fun blockedReadDoesNotDeliverDataAfterProtectionChanges() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val allowed = AtomicBoolean(true)
        val closed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val raw = object : FoxPipe {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) throw IOException("test timed out")
                buffer[offset] = 42
                return 1
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
            override fun close() { closed.set(true) }
        }
        val guarded = DirectPipe(raw) { allowed.get() }
        val reader = thread(isDaemon = true) {
            try {
                delivered.set(guarded.read(ByteArray(1), 0, 1) > 0)
            } catch (_: IOException) {
            } finally {
                done.countDown()
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        allowed.set(false)
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        reader.join(1000)
        assertFalse(delivered.get())
        assertTrue(closed.get())
    }
}
