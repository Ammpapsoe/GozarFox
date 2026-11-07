package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
        FoxLeakGuard.reset()
        Tunnel().use { tunnel ->
            tunnel.stack.start()
            repeat(8) { tunnel.input.offer(ipv6Syn()) }
            Thread.sleep(300)
            assertEquals(0, tunnel.dialed.get())
            assertNull(tunnel.output.poll(200, TimeUnit.MILLISECONDS))
            // The guard must have accounted for every stopped frame.
            assertEquals(8L, FoxLeakGuard.count(FoxLeakGuard.IPV6_DROPPED))
            assertEquals(mapOf(FoxLeakGuard.IPV6_DROPPED to 8L), FoxLeakGuard.snapshot())
        }
    }

    @Test
    fun selfTestPassesWhenDnsAndExitBothComeFromTheTunnel() {
        FoxLeakGuard.reset()
        val report = FoxLeakGuard.selfTest(
            now = 1000L,
            baselineIp = "2.176.10.5",
            resolve = { listOf("93.184.216.34") },
            exitIp = { "45.131.7.9" }
        )
        assertTrue(report.passed)
        assertEquals("45.131.7.9", report.exitIp)
        assertEquals(report, FoxLeakGuard.lastReport())
        assertEquals("", report.detail)
    }

    @Test
    fun selfTestFailsEveryLeakShape() {
        // No DNS answer at all.
        var report = FoxLeakGuard.selfTest(baselineIp = "2.2.2.2", resolve = { emptyList() }, exitIp = { "45.1.1.1" })
        assertTrue(!report.dnsLeakFree && report.exitVerified)
        // DNS answered with a private address (the classic ISP-hijack leak).
        report = FoxLeakGuard.selfTest(baselineIp = "2.2.2.2", resolve = { listOf("10.10.10.10") }, exitIp = { "45.1.1.1" })
        assertTrue(!report.dnsLeakFree)
        // Exit still on the baseline IP: traffic is not going through the tunnel.
        report = FoxLeakGuard.selfTest(baselineIp = "2.2.2.2", resolve = { listOf("93.184.216.34") }, exitIp = { "2.2.2.2" })
        assertTrue(report.dnsLeakFree && !report.exitVerified)
        assertTrue(report.detail.contains("unchanged"))
        // Exit unavailable.
        report = FoxLeakGuard.selfTest(baselineIp = "2.2.2.2", resolve = { listOf("93.184.216.34") }, exitIp = { "" })
        assertTrue(!report.exitVerified)
        // Exit on a local address.
        report = FoxLeakGuard.selfTest(baselineIp = "2.2.2.2", resolve = { listOf("93.184.216.34") }, exitIp = { "192.168.1.1" })
        assertTrue(!report.exitVerified)
        // A throwing probe is a failure, not a crash.
        report = FoxLeakGuard.selfTest(baselineIp = "2.2.2.2", resolve = { throw RuntimeException() }, exitIp = { throw RuntimeException() })
        assertTrue(!report.passed)
        // Without a baseline, any non-local exit passes.
        report = FoxLeakGuard.selfTest(baselineIp = "", resolve = { listOf("93.184.216.34") }, exitIp = { "45.1.1.1" })
        assertTrue(report.passed)
    }

    @Test
    fun localAddressClassificationCoversBothFamilies() {
        assertTrue(FoxLeakGuard.isLocalAddress("127.0.0.1"))
        assertTrue(FoxLeakGuard.isLocalAddress("10.1.2.3"))
        assertTrue(FoxLeakGuard.isLocalAddress("172.16.0.1"))
        assertTrue(FoxLeakGuard.isLocalAddress("172.31.255.255"))
        assertTrue(FoxLeakGuard.isLocalAddress("192.168.0.1"))
        assertTrue(FoxLeakGuard.isLocalAddress("169.254.1.1"))
        assertTrue(FoxLeakGuard.isLocalAddress("100.64.0.1"))
        assertTrue(FoxLeakGuard.isLocalAddress("0.0.0.0"))
        assertTrue(FoxLeakGuard.isLocalAddress("224.0.0.5"))
        assertTrue(FoxLeakGuard.isLocalAddress("::1"))
        assertTrue(FoxLeakGuard.isLocalAddress("fd12:3456::1"))
        assertTrue(FoxLeakGuard.isLocalAddress("fe80::1"))
        assertTrue(!FoxLeakGuard.isLocalAddress("172.15.0.1"))
        assertTrue(!FoxLeakGuard.isLocalAddress("172.32.0.1"))
        assertTrue(!FoxLeakGuard.isLocalAddress("100.128.0.1"))
        assertTrue(!FoxLeakGuard.isLocalAddress("8.8.8.8"))
        assertTrue(!FoxLeakGuard.isLocalAddress("2606:4700:4700::1111"))
        assertTrue(!FoxLeakGuard.isLocalAddress("not-an-ip"))
    }

    @Test
    fun countersAccumulateAndReset() {
        FoxLeakGuard.reset()
        FoxLeakGuard.note(FoxLeakGuard.KILL_SWITCH_BLOCKED)
        FoxLeakGuard.note(FoxLeakGuard.KILL_SWITCH_BLOCKED)
        FoxLeakGuard.note(FoxLeakGuard.DNS_OUTSIDE_TUNNEL)
        assertEquals(2L, FoxLeakGuard.count(FoxLeakGuard.KILL_SWITCH_BLOCKED))
        assertEquals(0L, FoxLeakGuard.count("never-noted"))
        FoxLeakGuard.reset()
        assertEquals(0L, FoxLeakGuard.count(FoxLeakGuard.KILL_SWITCH_BLOCKED))
        assertNull(FoxLeakGuard.lastReport())
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
        val seen = mutableListOf<String>()
        var dead = true
        val query = probeQuery()
        val reply = probeAnswer(query)
        val resolver = FoxResolver(0, request = { url, _ ->
            seen += url
            if (dead) { dead = false; null } else reply
        }, endpoints = DnsPolicy.endpoints(DnsPolicy.AUTO, "", false))
        assertEquals(reply.toList(), resolver.answer(query)?.toList())
        assertTrue(seen.size >= 2)
    }

    @Test
    fun automaticResolverReportsFailureWhenEveryEndpointDies() {
        var calls = 0
        val resolver = FoxResolver(0, request = { _, _ -> calls++; null },
            endpoints = DnsPolicy.endpoints(DnsPolicy.AUTO, "", false))
        val answer = resolver.answer(probeQuery())!!
        assertEquals(2, answer[3].toInt() and 15)
        assertEquals(DnsPolicy.endpoints(DnsPolicy.AUTO, "", false).size, calls)
    }
}
