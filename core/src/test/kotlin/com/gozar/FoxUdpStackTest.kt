package com.gozar

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxUdpStackTest {
    @Test
    fun datagramsIncludingEmptyPacketsTraverseThePacketEngine() {
        val input = PacketInput()
        val output = PacketOutput()
        val echo = EchoDatagram()
        val target = AtomicReference<String>()
        val direct = AtomicInteger()
        val stack = FoxStack(
            input, output, 0x0a6f0002, FoxResolver(0),
            { _, _, _ -> direct.incrementAndGet(); error("unexpected TCP/direct dial") },
            { _, _ -> },
            prepareUdp = { host, port ->
                target.set("$host:$port")
                val open: () -> FoxDatagramPipe = { echo }
                open
            }
        )
        stack.start()
        try {
            for (body in listOf("one".toByteArray(), byteArrayOf(), "longer packet".toByteArray())) {
                input.offer(udp(40000, 443, body))
                val reply = output.next()
                assertEquals(17, reply[9].toInt() and 255)
                assertEquals(443, u16(reply, 20))
                assertEquals(40000, u16(reply, 22))
                assertArrayEquals(body, reply.copyOfRange(28, reply.size))
            }
            assertEquals("203.0.113.7:443", target.get())
            assertEquals(0, direct.get())
            assertEquals(1, stack.activeDatagrams())
        } finally {
            stack.stop()
            input.close()
        }
        assertTrue(echo.closed.await(2, TimeUnit.SECONDS))
        assertEquals(0, stack.activeDatagrams())
    }

    @Test
    fun refusingUdpRelayNeverUsesDirectOrTcpDial() {
        val input = PacketInput()
        val output = PacketOutput()
        val direct = AtomicInteger()
        val attempted = CountDownLatch(1)
        val stack = FoxStack(
            input, output, 0x0a6f0002, FoxResolver(0),
            { _, _, _ -> direct.incrementAndGet(); error("unexpected direct") },
            { _, _ -> },
            prepareUdp = { _, _ ->
                val open: () -> FoxDatagramPipe = {
                    attempted.countDown()
                    throw java.io.IOException("synthetic refusal")
                }
                open
            }
        )
        stack.start()
        try {
            input.offer(udp(40000, 443, "quic".toByteArray()))
            assertTrue(attempted.await(3, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (stack.activeDatagrams() != 0 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(0, stack.activeDatagrams())
            assertEquals(0, direct.get())
            assertFalse(output.hasPacket())
        } finally {
            stack.stop()
            input.close()
        }
    }

    @Test
    fun stopRevokesLateUdpOpenAcrossFiftyForcedInterleavings() {
        repeat(50) { iteration ->
            val input = PacketInput()
            val output = PacketOutput()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val echo = EchoDatagram()
            val stack = FoxStack(
                input, output, 0x0a6f0002, FoxResolver(0),
                { _, _, _ -> error("unexpected direct") }, { _, _ -> },
                prepareUdp = { _, _ ->
                    val open: () -> FoxDatagramPipe = {
                        entered.countDown()
                        // Deliberately ignore interruption to force a late open.
                        while (true) {
                            try { release.await(); break } catch (_: InterruptedException) { }
                        }
                        echo
                    }
                    open
                }
            )
            stack.start()
            try {
                input.offer(udp(40000 + iteration, 443, byteArrayOf(1)))
                assertTrue("open never entered at $iteration", entered.await(3, TimeUnit.SECONDS))
                stack.stop()
                release.countDown()
                assertTrue("late relay not revoked at $iteration", echo.closed.await(3, TimeUnit.SECONDS))
                assertEquals("flow survived stop at $iteration", 0, stack.activeDatagrams())
                assertFalse("late relay emitted a packet at $iteration", output.hasPacket())
            } finally {
                release.countDown()
                stack.stop()
                input.close()
            }
        }
    }

    private class EchoDatagram : FoxDatagramPipe {
        private val packets = LinkedBlockingQueue<ByteArray>()
        private val done = AtomicInteger()
        val closed = CountDownLatch(1)
        override fun receive(): ByteArray? {
            while (done.get() == 0) {
                val packet = packets.poll(100, TimeUnit.MILLISECONDS)
                if (packet != null) return packet
            }
            return null
        }
        override fun send(payload: ByteArray) {
            check(done.get() == 0)
            packets.put(payload.copyOf())
        }
        override fun close() {
            if (done.compareAndSet(0, 1)) closed.countDown()
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
        override fun close() { packets.offer(byteArrayOf()) }
    }

    private class PacketOutput : OutputStream() {
        private val packets = LinkedBlockingQueue<ByteArray>()
        override fun write(value: Int) = throw UnsupportedOperationException()
        override fun write(buffer: ByteArray, offset: Int, length: Int) { packets.put(buffer.copyOfRange(offset, offset + length)) }
        fun next(): ByteArray = packets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no UDP response")
        fun hasPacket(): Boolean = packets.poll(30, TimeUnit.MILLISECONDS) != null
    }

    private fun udp(appPort: Int, remotePort: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(28 + payload.size)
        packet[0] = 0x45
        packet[8] = 64
        packet[9] = 17
        put16(packet, 2, packet.size)
        byteArrayOf(10, 111, 0, 2).copyInto(packet, 12)
        byteArrayOf(203.toByte(), 0, 113, 7).copyInto(packet, 16)
        put16(packet, 20, appPort)
        put16(packet, 22, remotePort)
        put16(packet, 24, 8 + payload.size)
        payload.copyInto(packet, 28)
        return packet
    }

    private fun put16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }

    private fun u16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 255) shl 8) or (packet[offset + 1].toInt() and 255)
}
