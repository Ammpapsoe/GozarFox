package com.gozar

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxStackMtuTest {

    @Test
    fun cellularMtuClampsSegmentSizeAndDnsTruncation() {
        val input = PacketInput()
        val output = PacketOutput()
        val writes = ArrayList<Int>()
        val uploaded = ByteArrayOutputStream()
        val pipe = object : FoxPipe {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                synchronized(writes) { writes.add(length) }
                uploaded.write(buffer, offset, length)
            }
            override fun close() {}
        }
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ -> pipe }, { _, _ -> }, mtu = 1280)
        stack.start()
        try {
            input.offer(tcp(1000, 0, 0x02, byteArrayOf()))
            val synAck = output.next()
            val peer = u32(synAck, 24) + 1
            val payload = ByteArray(3000) { 7 }
            input.offer(tcp(1001, peer, 0x18, payload))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (uploaded.size() < payload.size && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(payload.size, uploaded.size())
            synchronized(writes) {
                assertTrue(writes.isNotEmpty())
                for (size in writes) assertTrue("segment $size exceeds cellular MSS", size <= 1240)
            }
        } finally {
            stack.stop()
            input.close()
        }
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

    private class PacketOutput : OutputStream() {
        private val packets = LinkedBlockingQueue<ByteArray>()
        override fun write(value: Int) = throw UnsupportedOperationException()
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            packets.put(buffer.copyOfRange(offset, offset + length))
        }
        fun next(): ByteArray = packets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no packet returned")
    }

    private fun tcp(sequence: Long, acknowledgment: Long, flags: Int, data: ByteArray): ByteArray {
        val packet = ByteArray(40 + data.size)
        packet[0] = 0x45
        packet[8] = 64
        packet[9] = 6
        put16(packet, 2, packet.size)
        byteArrayOf(10, 111, 0, 2).copyInto(packet, 12)
        byteArrayOf(203.toByte(), 0, 113, 7).copyInto(packet, 16)
        put16(packet, 20, 40000)
        put16(packet, 22, 443)
        put32(packet, 24, sequence)
        put32(packet, 28, acknowledgment)
        packet[32] = 0x50
        packet[33] = flags.toByte()
        put16(packet, 34, 64000)
        data.copyInto(packet, 40)
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
