package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val output = ByteArrayOutputStream()
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
            output,
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
            repeat(8) { tunnel.input.offer(ipv6Syn()) }
            tunnel.stack.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (FoxLeakGuard.count(FoxLeakGuard.IPV6_DROPPED) < 8 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(0, tunnel.dialed.get())
            assertEquals(0, tunnel.output.size())
            // The guard must have accounted for every stopped frame.
            assertEquals(8L, FoxLeakGuard.count(FoxLeakGuard.IPV6_DROPPED))
            assertEquals(mapOf(FoxLeakGuard.IPV6_DROPPED to 8L), FoxLeakGuard.snapshot())
        }
    }

    private val positive = "api.example.test"
    private val canary = "nonce.gozar-check.invalid"

    private fun answer(host: String): FoxLeakGuard.Lookup = if (host == canary) {
        FoxLeakGuard.Lookup(FoxLeakGuard.LookupCode.NXDOMAIN)
    } else {
        FoxLeakGuard.Lookup(FoxLeakGuard.LookupCode.ANSWER, listOf("93.184.216.34"))
    }

    @Test
    fun successfulTunnelChecksDoNotClaimDeviceWideLeakFreedom() {
        FoxLeakGuard.reset()
        val report = FoxLeakGuard.diagnose(
            now = 1000L, positiveHost = positive, canaryHost = canary,
            resolve = { answer(it) }, exitIp = "45.131.7.9"
        )
        assertEquals(FoxLeakGuard.Check.OK, report.dnsCheck)
        assertEquals(FoxLeakGuard.Check.OK, report.exitCheck)
        assertEquals(FoxLeakGuard.Check.UNKNOWN, report.deviceLeakStatus)
        assertEquals("45.131.7.9", report.exitIp)
        assertNull(FoxLeakGuard.lastReport())
        val ticket = FoxLeakGuard.beginReport()
        assertTrue(FoxLeakGuard.publish(ticket, report))
        assertEquals(report, FoxLeakGuard.lastReport())
        FoxLeakGuard.clearReport()
        assertNull(FoxLeakGuard.lastReport())
    }

    @Test
    fun dnsPoisoningAndMissingEvidenceAreDistinguished() {
        fun run(resolve: (String) -> FoxLeakGuard.Lookup, exit: String = "45.1.1.1") =
            FoxLeakGuard.diagnose(positiveHost = positive, canaryHost = canary, resolve = resolve, exitIp = exit)
        val unavailable = FoxLeakGuard.Lookup(FoxLeakGuard.LookupCode.UNAVAILABLE)
        val privateAnswer = FoxLeakGuard.Lookup(FoxLeakGuard.LookupCode.ANSWER, listOf("10.0.0.1"))
        val spoofed = FoxLeakGuard.Lookup(FoxLeakGuard.LookupCode.ANSWER, listOf("5.61.103.1"))
        assertEquals(FoxLeakGuard.Check.UNKNOWN, run({ unavailable }).dnsCheck)
        assertEquals(FoxLeakGuard.Check.ISSUE, run({ host -> if (host == positive) privateAnswer else answer(host) }).dnsCheck)
        assertEquals(FoxLeakGuard.Check.ISSUE, run({ host -> if (host == canary) spoofed else answer(host) }).dnsCheck)
        // An empty NOERROR reply is neither NXDOMAIN nor proof of hijack.
        val empty = FoxLeakGuard.Lookup(FoxLeakGuard.LookupCode.ANSWER)
        assertEquals(FoxLeakGuard.Check.UNKNOWN, run({ host -> if (host == canary) empty else answer(host) }).dnsCheck)
        assertEquals(FoxLeakGuard.Check.UNKNOWN, run({ throw IllegalStateException() }).dnsCheck)
        assertEquals(FoxLeakGuard.Check.UNKNOWN, run({ answer(it) }, "not-an-ip").exitCheck)
        assertEquals(FoxLeakGuard.Check.UNKNOWN, run({ answer(it) }, "192.168.1.1").exitCheck)
        assertEquals(FoxLeakGuard.Check.UNKNOWN, run({ answer(it) }, "").exitCheck)
        assertEquals(FoxLeakGuard.Check.UNKNOWN, run({ answer(it) }).deviceLeakStatus)
    }

    @Test
    fun onlyNumericPublicLookingAddressesAreAccepted() {
        for (address in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255",
                "192.168.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.5",
                "192.0.2.1", "198.51.100.1", "203.0.113.1", "::1", "::ffff:127.0.0.1",
                "fd12:3456::1", "fe80::1", "2001:db8::1", "not-an-ip", "01.2.3.4", "999.2.3.4")) {
            assertFalse(address, FoxLeakGuard.isPublicAddress(address))
        }
        for (address in listOf("172.15.0.1", "172.32.0.1", "100.128.0.1", "8.8.8.8",
                "2606:4700:4700::1111", "::ffff:8.8.8.8")) {
            assertTrue(address, FoxLeakGuard.isPublicAddress(address))
        }
    }

    private fun wireAnswer(query: ByteArray, code: Int, ip: ByteArray? = null): ByteArray {
        val head = query.copyOf()
        head[2] = 0x81.toByte()
        head[3] = (0x80 or code).toByte()
        if (ip == null) return head
        head[7] = 1
        return head + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4) + ip
    }

    @Test
    fun vpnResolverDnsProbeDistinguishesNxdomainFromServfailAndMalformedResponses() {
        fun resolver(code: Int, ip: ByteArray? = null): FoxResolver = FoxResolver(
            0, request = { _, query -> wireAnswer(query, code, ip) },
            endpoints = listOf("https://resolver.example/dns-query")
        )
        val good = FoxLeakGuard.lookupA(resolver(0, byteArrayOf(8, 8, 8, 8)), positive)
        assertEquals(FoxLeakGuard.LookupCode.ANSWER, good.code)
        assertEquals(listOf("8.8.8.8"), good.addresses)
        assertEquals(FoxLeakGuard.LookupCode.NXDOMAIN, FoxLeakGuard.lookupA(resolver(3), canary).code)
        assertEquals(FoxLeakGuard.LookupCode.UNAVAILABLE, FoxLeakGuard.lookupA(resolver(2), canary).code)
        assertEquals(FoxLeakGuard.LookupCode.UNAVAILABLE, FoxLeakGuard.lookupA(resolver(0), "bad host").code)
        val malformed = FoxResolver(0, request = { _, query ->
            val body = wireAnswer(query, 0)
            body[7] = 1
            body + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 8)
        }, endpoints = listOf("https://resolver.example/dns-query"))
        assertEquals(FoxLeakGuard.LookupCode.UNAVAILABLE, FoxLeakGuard.lookupA(malformed, positive).code)
    }

    @Test
    fun countersAccumulateAndReset() {
        FoxLeakGuard.reset()
        FoxLeakGuard.note(FoxLeakGuard.LOCKDOWN_INTERRUPTION)
        FoxLeakGuard.note(FoxLeakGuard.LOCKDOWN_INTERRUPTION)
        assertEquals(2L, FoxLeakGuard.count(FoxLeakGuard.LOCKDOWN_INTERRUPTION))
        assertEquals(0L, FoxLeakGuard.count("never-noted"))
        FoxLeakGuard.reset()
        assertEquals(0L, FoxLeakGuard.count(FoxLeakGuard.LOCKDOWN_INTERRUPTION))
        assertNull(FoxLeakGuard.lastReport())
    }

    @Test
    fun synFloodKeepsFlowTableBounded() {
        FoxLeakGuard.reset()
        Tunnel().use { tunnel ->
            val base = ByteArray(40)
            base[0] = 0x45
            base[2] = 0
            base[3] = 40
            base[8] = 64
            base[9] = 6
            base[32] = 0x50 // TCP header length = five words
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
            // The sentinel is processed after all SYNs; this avoids passing
            // merely because the reader has not yet consumed the flood.
            tunnel.input.offer(byteArrayOf(0))
            tunnel.stack.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (FoxLeakGuard.count(FoxLeakGuard.MALFORMED_DROPPED) == 0L && System.nanoTime() < deadline) {
                assertTrue(tunnel.stack.active() <= 256)
                Thread.yield()
            }
            assertEquals(1L, FoxLeakGuard.count(FoxLeakGuard.MALFORMED_DROPPED))
            assertTrue(tunnel.output.size() > 0)
            assertTrue(tunnel.stack.active() <= 256)
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
