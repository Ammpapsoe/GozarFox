package com.gozar

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxStackWindowTest {
    private class QueueInput : InputStream() {
        val queue = LinkedBlockingQueue<ByteArray>()
        private var current = ByteArray(0)
        private var offset = 0
        @Volatile var closed = false
        fun offer(bytes: ByteArray) { queue.offer(bytes) }
        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            val packet = queue.poll(3, TimeUnit.SECONDS) ?: return if (closed) -1 else 0
            val count = minOf(len, packet.size)
            packet.copyInto(buffer, off, 0, count)
            return count
        }
        override fun read(): Int {
            while (offset >= current.size) {
                if (closed && queue.isEmpty()) return -1
                current = queue.poll(2, TimeUnit.SECONDS) ?: if (closed) return -1 else continue
                offset = 0
            }
            return current[offset++].toInt() and 0xff
        }
    }

    private class QueueOutput : OutputStream() {
        val queue = LinkedBlockingQueue<ByteArray>()
        override fun write(b: Int) { queue.offer(byteArrayOf(b.toByte())) }
        override fun write(b: ByteArray, off: Int, len: Int) { queue.offer(b.copyOfRange(off, off + len)) }
        fun next(): ByteArray = queue.poll(3, TimeUnit.SECONDS) ?: error("no packet")
    }

    private class Pipe(val remote: QueueInput) : FoxPipe {
        val upload = ByteArrayOutputStream()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = remote.read(buffer, offset, length)
        override fun write(buffer: ByteArray, offset: Int, length: Int) { upload.write(buffer, offset, length) }
        override fun finishWrite() {}
        override fun close() { remote.closed = true }
    }

    private fun putU32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 255) shl 24) or ((data[offset + 1].toLong() and 255) shl 16) or
            ((data[offset + 2].toLong() and 255) shl 8) or (data[offset + 3].toLong() and 255)

    private fun packet(sequence: Long, acknowledgment: Long, flags: Int, options: ByteArray, payload: ByteArray, window: Int = 65535): ByteArray {
        val tcpLength = 20 + options.size + payload.size
        val out = ByteArray(20 + tcpLength)
        out[0] = 0x45
        out[2] = (out.size shr 8).toByte()
        out[3] = out.size.toByte()
        out[8] = 64.toByte()
        out[9] = 6.toByte()
        byteArrayOf(10, 111, 0, 2).copyInto(out, 12)
        byteArrayOf(203.toByte(), 0, 113, 7).copyInto(out, 16)
        out[20] = 0x30.toByte()
        out[21] = 0x39.toByte()
        out[22] = 0x01.toByte()
        out[23] = 0xbb.toByte()
        putU32(out, 24, sequence)
        putU32(out, 28, acknowledgment)
        out[32] = (((20 + options.size) / 4) shl 4).toByte()
        out[33] = flags.toByte()
        out[34] = (window shr 8).toByte()
        out[35] = window.toByte()
        options.copyInto(out, 40)
        payload.copyInto(out, 40 + options.size)
        return out
    }

    @Test
    fun synAckAdvertisesWindowScaleAndPeerMssIsRespected() {
        val input = QueueInput()
        val output = QueueOutput()
        val remote = QueueInput()
        val pipe = Pipe(remote)
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ -> pipe }, { _, _ -> })
        stack.start()
        try {
            val synOptions = byteArrayOf(2, 4, 0x03, 0xe8.toByte(), 3, 3, 7, 1)
            input.offer(packet(1000, 0, 0x02, synOptions, ByteArray(0)))
            val synAck = output.next()
            assertEquals(0x12, synAck[33].toInt() and 0xff)
            val headerWords = (synAck[32].toInt() and 0xff) ushr 4
            assertTrue(headerWords > 5)
            val options = synAck.copyOfRange(40, 20 + headerWords * 4)
            var sawScale = false
            var index = 0
            while (index < options.size) {
                val kind = options[index].toInt() and 0xff
                if (kind == 0) break
                if (kind == 1) { index++; continue }
                val length = options[index + 1].toInt() and 0xff
                if (kind == 3 && length == 3 && options[index + 2].toInt() == 7) sawScale = true
                index += length
            }
            assertTrue("SYN-ACK must advertise window scale 7", sawScale)
            val peerSequence = u32(synAck, 24) + 1
            input.offer(packet(1001, peerSequence, 0x10, ByteArray(0), ByteArray(0)))
            val body = ByteArray(2500) { (it % 251).toByte() }
            remote.offer(body)
            remote.closed = true
            var received = 0
            while (received < body.size) {
                val next = output.next()
                if ((next[9].toInt() and 0xff) != 6) continue
                val words = (next[32].toInt() and 0xff) ushr 4
                val payload = next.size - 20 - words * 4
                if (payload <= 0) continue
                assertTrue("segment $payload exceeds negotiated MSS 1000", payload <= 1000)
                received += payload
            }
            assertEquals(body.size, received)
        } finally {
            stack.stop()
            input.closed = true
            remote.closed = true
        }
    }

    @Test
    fun idleFlowDoesNotEmitKeepalivePackets() {
        val input = QueueInput()
        val output = QueueOutput()
        val remote = QueueInput()
        val pipe = Pipe(remote)
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ -> pipe }, { _, _ -> })
        stack.start()
        try {
            input.offer(packet(1000, 0, 0x02, ByteArray(0), ByteArray(0)))
            val synAck = output.next()
            val peerSequence = u32(synAck, 24) + 1
            input.offer(packet(1001, peerSequence, 0x10, ByteArray(0), ByteArray(0)))
            Thread.sleep(300)
            assertTrue("idle flow emitted a packet", output.queue.isEmpty())
        } finally {
            stack.stop()
            input.closed = true
            remote.closed = true
        }
    }
}
