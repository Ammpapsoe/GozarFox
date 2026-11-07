package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FoxLeakGuardTest {

    private class Tunnel : AutoCloseable {
        val input = LinkedBlockingQueue<ByteArray>()
        val output = LinkedBlockingQueue<ByteArray>()
        val dialed = AtomicInteger()
        val stack = FoxStack(
            object : java.io.InputStream() {
                override fun read(): Int = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val packet = input.poll(50, TimeUnit.MILLISECONDS) ?: return -1
                    packet.copyInto(b, off)
                    return packet.size
                }
            },
            ByteArrayOutputStream(),
            0x0a6f0002,
            FoxResolver(0),
            { _, _, _ ->
                dialed.incrementAndGet()
                object : FoxPipe {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
                    override fun close() = Unit
                }
            },
            { _, _ -> }
        )
        override fun close() = stack.stop()
    }

    private fun ipv6Syn(): ByteArray {
        val packet = ByteArray(60)
        packet[0] = 0x60
        packet[4] = 0
        packet[5] = 20
        packet[6] = 6
        packet[7] = 40
        packet[21] = 0x02
        return packet
    }

    @Test
    fun ipv6FramesAreDroppedInsteadOfLeakingOutOfTheTunnel() {
        Tunnel().use { tunnel ->
            tunnel.stack.start()
            repeat(8) { tunnel.input.offer(ipv6Syn()) }
            Thread.sleep(300)
            assertEquals(0, tunnel.dialed.get())
            assertNull(tunnel.output.poll(200, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun synFloodKeepsFlowTableBounded() {
        Tunnel().use { tunnel ->
            tunnel.stack.start()
            val base = ByteArray(40)
            base[0] = 0x45
            base[2] = 0
            base[3] = 40
            base[8] = 64
            base[9] = 6
            byteArrayOf(10, 111, 0, 2).copyInto(base, 12)
            for (index in 0 until 4000) {
                val packet = base.copyOf()
                byteArrayOf(203.toByte(), 0, 113, 7).copyInto(packet, 16)
                packet[20] = ((1024 + index) / 256).toByte()
                packet[21] = ((1024 + index) % 256).toByte()
                packet[22] = 0
                packet[23] = 80
                packet[33] = 0x02
                tunnel.input.offer(packet)
            }
            Thread.sleep(600)
            assertTrue(tunnel.stack.active() <= 512)
        }
    }

    @Test
    fun automaticResolverListKeepsIndependentFallbacks() {
        val endpoints = DnsPolicy.endpoints(DnsPolicy.AUTO, "", false)
        assertTrue(endpoints.size >= 3)
        assertEquals(endpoints.size, endpoints.distinct().size)
        assertTrue(endpoints.all { it.startsWith("https://") })
    }

    private fun probeQuery(id: Int = 1, label: String = "one"): ByteArray = byteArrayOf(
        (id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        label.length.toByte()
    ) + label.toByteArray() + byteArrayOf(4, 116, 101, 115, 116, 0, 0, 1, 0, 1)

    private fun probeAnswer(query: ByteArray, ttl: Int = 2, rcode: Int = 0): ByteArray {
        val copy = query.copyOf()
        copy[2] = 0x81.toByte()
        copy[3] = (0x80 or rcode).toByte()
        if (rcode != 0) return copy
        copy[7] = 1
        return copy + byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, ttl.toByte(), 0, 4, 1, 2, 3, 4)
    }

    @Test
    fun automaticResolverSurvivesOneDeadEndpoint() {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val firstFailed = AtomicBoolean(false)
        val query = probeQuery()
        val reply = probeAnswer(query)
        val resolver = FoxResolver(0, request = { url, _ ->
            seen.add(url)
            if (firstFailed.compareAndSet(false, true)) null else reply
        }, endpoints = DnsPolicy.endpoints(DnsPolicy.AUTO, "", false))
        assertEquals(reply.toList(), resolver.answer(query)?.toList())
        assertTrue(seen.size >= 2)
    }

    @Test
    fun automaticResolverReportsFailureWhenEveryEndpointDies() {
        val calls = AtomicInteger()
        val resolver = FoxResolver(0, request = { _, _ -> calls.incrementAndGet(); null },
            endpoints = DnsPolicy.endpoints(DnsPolicy.AUTO, "", false))
        val answer = resolver.answer(probeQuery())!!
        assertEquals(2, answer[3].toInt() and 15)
        assertEquals(DnsPolicy.endpoints(DnsPolicy.AUTO, "", false).size, calls.get())
    }
}
