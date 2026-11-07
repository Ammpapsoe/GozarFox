package com.gozar

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxDnsStackScopeStressTest {
    private class Packets : InputStream() {
        private val queue = LinkedBlockingQueue<ByteArray>()
        fun offer(bytes: ByteArray) = queue.put(bytes)
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val packet = queue.poll(5, TimeUnit.SECONDS) ?: return -1
            if (packet.isEmpty()) return -1
            System.arraycopy(packet, 0, buffer, offset, packet.size)
            return packet.size
        }
        override fun close() { queue.offer(byteArrayOf()) }
    }

    private class Replies : OutputStream() {
        val packets = LinkedBlockingQueue<ByteArray>()
        override fun write(value: Int) = throw UnsupportedOperationException()
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            packets.put(buffer.copyOfRange(offset, offset + length))
        }
    }

    private fun query(id: Int) = byteArrayOf(
        0, id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        3, 119, 119, 119, 4, 116, 101, 115, 116, 0, 0, 1, 0, 1
    )

    private fun answer(q: ByteArray, third: Int, fourth: Int): ByteArray {
        val head = q.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
        }
        return head + byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 10, 0, third.toByte(), fourth.toByte())
    }

    private fun packet(id: Int): ByteArray {
        val q = query(id)
        val packet = ByteArray(28 + q.size)
        packet[0] = 0x45
        packet[8] = 64
        packet[9] = 17
        put16(packet, 2, packet.size)
        byteArrayOf(10, 111, 0, 2).copyInto(packet, 12)
        byteArrayOf(10, 111, 0, 53).copyInto(packet, 16)
        put16(packet, 20, 40000)
        put16(packet, 22, 53)
        put16(packet, 24, 8 + q.size)
        q.copyInto(packet, 28)
        return packet
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    @Test
    fun udpDnsDiscardsOldAttemptAndServesNewAcrossFiftyRaces() {
        data class Gate(val entered: CountDownLatch, val release: CountDownLatch)
        val gates = LinkedBlockingQueue<Gate>()
        val epoch = AtomicLong(1)
        val input = Packets()
        val output = Replies()
        val resolver = FoxResolver(0, { _, q ->
            val id = q[1].toInt() and 255
            val iteration = (id - 1) / 2
            if (id % 2 == 1) {
                val gate = gates.poll(5, TimeUnit.SECONDS) ?: error("missing gate")
                gate.entered.countDown()
                if (!gate.release.await(5, TimeUnit.SECONDS)) error("old DNS timed out")
                answer(q, 1, iteration + 1)
            } else {
                answer(q, 2, iteration + 1)
            }
        }, endpoints = listOf("primary"))
        val stack = FoxStack(input, output, 0x0a6f0002, resolver,
            { _, _, _ -> throw AssertionError("unexpected TCP dial") }, { _, _ -> },
            prepareDns = {
                val captured = epoch.get()
                FoxDnsScope(captured) { epoch.get() == captured }
            })
        val field = FoxStack::class.java.getDeclaredField("dnsWorkers").apply { isAccessible = true }
        val workers = field.get(stack) as ThreadPoolExecutor
        fun awaitIdle() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (workers.activeCount > 0 && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals("DNS worker did not finish", 0, workers.activeCount)
        }
        stack.start()
        try {
            repeat(50) { iteration ->
                val gate = Gate(CountDownLatch(1), CountDownLatch(1))
                gates.put(gate)
                input.offer(packet(iteration * 2 + 1))
                assertTrue("old DNS did not begin at $iteration", gate.entered.await(5, TimeUnit.SECONDS))
                epoch.incrementAndGet()
                gate.release.countDown()
                awaitIdle()
                assertNull("old response emitted at $iteration", output.packets.poll())
                assertNull("old response changed DNS names at $iteration", stack.nameOf(0x0a000100 + iteration + 1))
                input.offer(packet(iteration * 2 + 2))
                val reply = output.packets.poll(5, TimeUnit.SECONDS)
                assertNotNull("replacement response missing at $iteration", reply)
                assertEquals(iteration * 2 + 2, reply!![29].toInt() and 255)
                awaitIdle()
                assertEquals("www.test", stack.nameOf(0x0a000200 + iteration + 1))
                epoch.incrementAndGet()
            }
        } finally {
            stack.stop()
            input.close()
        }
    }
}
