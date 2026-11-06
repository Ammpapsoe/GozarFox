package com.gozar

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queue-behavior tests for the packet stack: reordering, sequence wrap-around,
 * bounded reorder window, SYN flood backpressure, RST teardown and window scale.
 */
class FoxStackQueueTest {

    @Test
    fun outOfOrderSegmentsAreReassembledInOrder() {
        val input = PacketInput()
        val output = PacketOutput()
        val tunnel = MemoryTunnel()
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ -> tunnel }, { _, _ -> })
        stack.start()
        try {
            input.offer(tcp(1000, 0, 0x02, byteArrayOf()))
            val synAck = output.next()
            assertEquals(0x12, synAck[33].toInt() and 0xff)
            val peer = u32(synAck, 24) + 1
            // "second" arrives before the segment that precedes it.
            input.offer(tcp(1007, peer, 0x18, "second".toByteArray()))
            input.offer(tcp(1001, peer, 0x18, "first!".toByteArray()))
            assertEquals("first!second", tunnel.awaitText(12))
            val ack = output.find { u32(it, 28) == 1013L }
            assertEquals(1013L, u32(ack, 28))
        } finally {
            stack.stop()
            input.close()
        }
    }

    @Test
    fun reorderWindowIsBoundedAndStaleRetransmitsAreDropped() {
        val input = PacketInput()
        val output = PacketOutput()
        val tunnel = MemoryTunnel()
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ -> tunnel }, { _, _ -> })
        stack.start()
        try {
            input.offer(tcp(1000, 0, 0x02, byteArrayOf()))
            val peer = u32(output.next(), 24) + 1
            // Twelve future segments; only the first ten may be kept.
            for (index in 1..12) {
                input.offer(tcp(1001 + index * 4L, peer, 0x18, "p%03d".format(index).toByteArray()))
            }
            input.offer(tcp(1001, peer, 0x18, "p000".toByteArray()))
            val expected = "p000" + (1..10).joinToString("") { "p%03d".format(it) }
            assertEquals(expected, tunnel.awaitText(44))
            Thread.sleep(300)
            assertFalse(tunnel.text().contains("p011"))
            assertFalse(tunnel.text().contains("p012"))
            // A retransmit of already-delivered data must not be delivered twice.
            input.offer(tcp(1001, peer, 0x18, "p000".toByteArray()))
            Thread.sleep(300)
            assertEquals(44, tunnel.text().length)
        } finally {
            stack.stop()
            input.close()
        }
    }

    @Test
    fun sequenceWrapAroundReordersAcrossThe32BitBoundary() {
        val input = PacketInput()
        val output = PacketOutput()
        val tunnel = MemoryTunnel()
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ -> tunnel }, { _, _ -> })
        stack.start()
        try {
            val initial = 0xFFFFFFF0L
            input.offer(tcp(initial, 0, 0x02, byteArrayOf()))
            val peer = u32(output.next(), 24) + 1
            val first = (initial + 1) and 0xFFFFFFFFL
            val second = (first + 8) and 0xFFFFFFFFL
            input.offer(tcp(second, peer, 0x18, "bbbbbbbb".toByteArray()))
            input.offer(tcp(first, peer, 0x18, "aaaaaaaa".toByteArray()))
            assertEquals("aaaaaaaabbbbbbbb", tunnel.awaitText(16))
        } finally {
            stack.stop()
            input.close()
        }
    }

    @Test
    fun synFloodIsRejectedWithResets() {
        val input = PacketInput()
        val output = PacketOutput()
        val tunnel = MemoryTunnel()
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ -> tunnel }, { _, _ -> })
        stack.start()
        try {
            repeat(600) { index ->
                input.offer(tcp(1000, 0, 0x02, byteArrayOf(), 20000 + index))
            }
            var resets = 0
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                val packet = output.poll(300) ?: break
                if (packet.size > 33 && packet[9].toInt() and 0xff == 6 && packet[33].toInt() and 0x04 != 0) resets++
            }
            assertTrue("expected RST backpressure, got $resets", resets >= 1)
            assertTrue("flow table must stay bounded", stack.active() <= 256)
        } finally {
            stack.stop()
            input.close()
        }
    }

    @Test
    fun rstClosesTheFlowAndItsPipe() {
        val input = PacketInput()
        val output = PacketOutput()
        val tunnel = MemoryTunnel()
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ -> tunnel }, { _, _ -> })
        stack.start()
        try {
            input.offer(tcp(1000, 0, 0x02, byteArrayOf()))
            val peer = u32(output.next(), 24) + 1
            input.offer(tcp(1001, peer, 0x04, byteArrayOf()))
            assertTrue("the tunnel pipe should be closed", tunnel.awaitClose(3000))
            val deadline = System.currentTimeMillis() + 3000
            while (stack.active() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(0, stack.active())
        } finally {
            stack.stop()
            input.close()
        }
    }

    @Test
    fun windowScaleOptionIsAnnouncedOnEveryHandshake() {
        val input = PacketInput()
        val output = PacketOutput()
        val tunnel = MemoryTunnel()
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ -> tunnel }, { _, _ -> })
        stack.start()
        try {
            val syn = tcp(1000, 0, 0x02, byteArrayOf(), 40000, byteArrayOf(3, 3, 5, 0))
            input.offer(syn)
            val synAck = output.next()
            assertEquals(0x12, synAck[33].toInt() and 0xff)
            assertTrue("SYN-ACK must carry the stack's window scale", contains(synAck, byteArrayOf(3, 3, 7, 0)))
        } finally {
            stack.stop()
            input.close()
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    private class MemoryTunnel : FoxPipe {
        private val written = ByteArrayOutputStream()
        private val stop = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            stop.await(30, TimeUnit.SECONDS)
            return -1
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            synchronized(written) { written.write(buffer, offset, length) }
        override fun close() {
            stop.countDown()
            closed.countDown()
        }
        fun text(): String = synchronized(written) { String(written.toByteArray(), Charsets.US_ASCII) }
        fun awaitText(length: Int): String {
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                val current = text()
                if (current.length >= length) return current
                Thread.sleep(10)
            }
            return text()
        }
        fun awaitClose(timeoutMs: Long): Boolean = closed.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private class PacketInput : InputStream() {
        private val packets = LinkedBlockingQueue<ByteArray>()
        fun offer(packet: ByteArray) = packets.put(packet)
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val packet = packets.poll(5, TimeUnit.SECONDS) ?: return -1
            if (packet.isEmpty()) return -1
            System.arraycopy(packet, 0, buffer, offset, packet.size)
            return packet.size
        }
        override fun close() {
            packets.offer(byteArrayOf())
        }
    }

    private class PacketOutput : java.io.OutputStream() {
        private val packets = LinkedBlockingQueue<ByteArray>()
        override fun write(value: Int) = throw UnsupportedOperationException()
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            packets.put(buffer.copyOfRange(offset, offset + length))
        }
        fun next(): ByteArray = packets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no packet returned")
        fun poll(timeoutMs: Long): ByteArray? = packets.poll(timeoutMs, TimeUnit.MILLISECONDS)
        fun find(predicate: (ByteArray) -> Boolean): ByteArray {
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                val packet = packets.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (predicate(packet)) return packet
            }
            throw AssertionError("no matching packet")
        }
    }

    companion object {
        private fun tcp(
            sequence: Long,
            acknowledgment: Long,
            flags: Int,
            data: ByteArray,
            sourcePort: Int = 40000,
            options: ByteArray? = null
        ): ByteArray {
            // 20-byte IPv4 header, then the TCP header (plus options), then data.
            val tcpHeader = 20 + (options?.size ?: 0)
            val packet = ByteArray(20 + tcpHeader + data.size)
            packet[0] = 0x45
            packet[8] = 64
            packet[9] = 6
            put16(packet, 2, packet.size)
            byteArrayOf(10, 111, 0, 2).copyInto(packet, 12)
            byteArrayOf(203.toByte(), 0, 113, 7).copyInto(packet, 16)
            put16(packet, 20, sourcePort)
            put16(packet, 22, 443)
            put32(packet, 24, sequence)
            put32(packet, 28, acknowledgment)
            packet[32] = ((tcpHeader / 4) shl 4).toByte()
            packet[33] = flags.toByte()
            put16(packet, 34, 64000)
            options?.copyInto(packet, 40)
            data.copyInto(packet, 20 + tcpHeader)
            return packet
        }

        private fun put16(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value ushr 8).toByte()
            data[offset + 1] = value.toByte()
        }

        private fun put32(data: ByteArray, offset: Int, value: Long) {
            data[offset] = (value ushr 24).toByte()
            data[offset + 1] = (value ushr 16).toByte()
            data[offset + 2] = (value ushr 8).toByte()
            data[offset + 3] = value.toByte()
        }

        private fun u32(data: ByteArray, offset: Int): Long =
            ((data[offset].toLong() and 255) shl 24) or ((data[offset + 1].toLong() and 255) shl 16) or
                ((data[offset + 2].toLong() and 255) shl 8) or (data[offset + 3].toLong() and 255)
    }
}
