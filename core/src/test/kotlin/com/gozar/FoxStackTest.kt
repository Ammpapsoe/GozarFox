package com.gozar

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxStackTest {
    @Test
    fun ipv4TcpPacketsFlowThroughFakeTunnelWithoutAnOsDevice() {
        MemoryTunnel().use { tunnel ->
            val initial = 1000L
            tunnel.input.offer(tcp(initial, 0, 0x02, byteArrayOf()))
            val synAck = tunnel.output.next()
            assertEquals(0x12, synAck[33].toInt() and 0xff)
            assertEquals(initial + 1, u32(synAck, 28))
            val peerSeq = u32(synAck, 24) + 1
            tunnel.input.offer(tcp(initial + 1, peerSeq, 0x10, byteArrayOf()))
            val body = "packet-level-loopback".toByteArray(Charsets.US_ASCII)
            tunnel.input.offer(tcp(initial + 1, peerSeq, 0x18, body))
            val response = tunnel.output.find { it.size > 40 && (it[9].toInt() and 0xff) == 6 }
            assertArrayEquals(body, response.copyOfRange(40, response.size))
            assertEquals("203.0.113.7:443", tunnel.dialed.get())
            assertArrayEquals(body, tunnel.pipe.upload.toByteArray())
            assertTrue(tunnel.uploaded.get() >= body.size)
            assertTrue(tunnel.downloadedReady.await(2, TimeUnit.SECONDS))
            assertTrue(tunnel.downloaded.get() >= body.size)
        }
    }

    @Test
    fun malformedShortTcpPacketDoesNotStopThePump() {
        MemoryTunnel().use { tunnel ->
            val malformed = ByteArray(28)
            malformed[0] = 0x45
            malformed[9] = 6
            malformed[2] = 0
            malformed[3] = 28
            byteArrayOf(10, 111, 0, 2).copyInto(malformed, 12)
            tunnel.input.offer(malformed)
            tunnel.input.offer(tcp(1000, 0, 0x02, byteArrayOf()))
            assertEquals(0x12, tunnel.output.next()[33].toInt() and 255)
        }
    }

    @Test
    fun remoteHalfCloseStillAllowsClientUploadBeforeFin() {
        val input = PacketInput()
        val output = PacketOutput()
        val allowEof = CountDownLatch(1)
        val uploaded = ByteArrayOutputStream()
        val finished = CountDownLatch(1)
        val pipe = object : FoxPipe {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { allowEof.await(); return -1 }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { uploaded.write(buffer, offset, length) }
            override fun finishWrite() { finished.countDown() }
            override fun close() { allowEof.countDown() }
        }
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ -> pipe }, { _, _ -> })
        stack.start()
        try {
            input.offer(tcp(1000, 0, 0x02, byteArrayOf()))
            val synAck = output.next()
            val peer = u32(synAck, 24) + 1
            allowEof.countDown()
            output.find { (it[33].toInt() and 0x01) != 0 }
            val payload = "after fin".toByteArray()
            input.offer(tcp(1001, peer + 1, 0x11, payload))
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertArrayEquals(payload, uploaded.toByteArray())
        } finally {
            stack.stop()
            input.close()
        }
    }

    @Test
    fun flowCapturesItsScopeBeforeDelayedWorkerAcrossFiftyRaces() {
        data class Gate(val entered: CountDownLatch, val release: CountDownLatch, val denied: CountDownLatch)
        val input = PacketInput()
        val output = PacketOutput()
        val epoch = AtomicInteger(1)
        val gates = LinkedBlockingQueue<Gate>()
        val opened = AtomicInteger(0)
        val unscoped = AtomicInteger(0)
        val stack = FoxStack(
            input, output, 0x0a6f0002, FoxResolver(0),
            { _, _, _ -> unscoped.incrementAndGet(); throw IOException("unscoped") },
            { _, _ -> },
            prepareDial = { _, _, _ ->
                val ticket = epoch.get()
                val gate = gates.poll(5, TimeUnit.SECONDS) ?: error("missing test gate")
                val open: () -> FoxPipe = {
                    gate.entered.countDown()
                    if (!gate.release.await(5, TimeUnit.SECONDS)) throw IOException("test gate timed out")
                    if (ticket != epoch.get()) {
                        gate.denied.countDown()
                        throw IOException("stale scope")
                    }
                    opened.incrementAndGet()
                    EchoPipe()
                }
                open
            }
        )
        stack.start()
        try {
            repeat(50) { iteration ->
                val gate = Gate(CountDownLatch(1), CountDownLatch(1), CountDownLatch(1))
                gates.put(gate)
                val packet = tcp(1000, 0, 0x02, byteArrayOf())
                put16(packet, 20, 40000 + iteration)
                input.offer(packet)
                assertTrue("worker did not enter at iteration $iteration", gate.entered.await(5, TimeUnit.SECONDS))
                epoch.incrementAndGet() // stop and reconnect while the old worker is paused
                gate.release.countDown()
                assertTrue("stale scope not rejected at iteration $iteration", gate.denied.await(5, TimeUnit.SECONDS))
            }
            assertEquals(0, opened.get())
            assertEquals(0, unscoped.get())
        } finally {
            stack.stop()
            input.close()
        }
    }

    @Test
    fun unsupportedUdpReturnsIcmpWithoutCallingTheTunnel() {
        MemoryTunnel().use { tunnel ->
            tunnel.input.offer(udp(443, ByteArray(16) { it.toByte() }))
            val response = tunnel.output.next()
            assertEquals(1, response[9].toInt() and 0xff)
            assertEquals(3, response[20].toInt() and 0xff)
            assertEquals(3, response[21].toInt() and 0xff)
            assertEquals(null, tunnel.dialed.get())
        }
    }

    @Test
    fun ipv6DnsQuestionIsAnsweredLocallyWithoutExternalQueries() {
        MemoryTunnel().use { tunnel ->
            val query = byteArrayOf(
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x03, 0x77, 0x77, 0x77, 0x07, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65,
                0x07, 0x69, 0x6e, 0x76, 0x61, 0x6c, 0x69, 0x64, 0x00, 0x00, 0x1c, 0x00, 0x01
            )
            tunnel.input.offer(udp(53, query))
            val response = tunnel.output.next()
            assertEquals(17, response[9].toInt() and 0xff)
            val answer = response.copyOfRange(28, response.size)
            assertEquals(0x12, answer[0].toInt() and 0xff)
            assertEquals(0x34, answer[1].toInt() and 0xff)
            assertTrue(answer[2].toInt() and 0x80 != 0)
            assertEquals(0, answer[6].toInt() and 0xff)
            assertEquals(0, answer[7].toInt() and 0xff)
            assertEquals(null, tunnel.dialed.get())
        }
    }

    @Test
    fun dnsLearningRecordsEveryIpv4AnswerForDirectHostDecisions() {
        val query = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x04, 0x74, 0x65, 0x73, 0x74, 0x07, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65,
            0x00, 0x00, 0x01, 0x00, 0x01
        )
        val header = query.copyOfRange(0, 12)
        header[2] = 0x81.toByte()
        header[3] = 0x80.toByte()
        header[7] = 3
        fun record(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(
            0xc0.toByte(), 0x0c, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3c,
            0x00, 0x04, a.toByte(), b.toByte(), c.toByte(), d.toByte()
        )
        val answer = header + query.copyOfRange(12, query.size) + record(1, 2, 3, 4) + record(5, 6, 7, 8) + record(203, 0, 113, 7)
        val input = PacketInput()
        val output = PacketOutput()
        val destinations = LinkedBlockingQueue<Pair<String, String>>()
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { host, address, _ ->
            destinations.put(host to address)
            EchoPipe()
        }, { _, _ -> })
        val learn = FoxStack::class.java.getDeclaredMethod("learn", ByteArray::class.java, ByteArray::class.java)
        learn.isAccessible = true
        learn.invoke(stack, query, answer)
        assertEquals("test.example", stack.nameOf(0x01020304))
        assertEquals("test.example", stack.nameOf(0x05060708))
        stack.start()
        try {
            input.offer(tcp(1000, 0, 0x02, byteArrayOf()))
            assertEquals("test.example" to "203.0.113.7", destinations.poll(5, TimeUnit.SECONDS))
        } finally {
            stack.stop()
            input.close()
        }
    }

    @Test
    fun directHostRulesMatchDomainsAndOptionalLanWithoutMatchingUnrelatedHosts() {
        FoxRoute.apply(false, "example.test\napi.other.test")
        assertTrue(FoxRoute.direct("sub.example.test"))
        assertTrue(FoxRoute.direct("api.other.test"))
        assertTrue(!FoxRoute.direct("badexample.test"))
        assertTrue(!FoxRoute.direct("192.168.1.2"))
        FoxRoute.apply(true, "")
        assertTrue(FoxRoute.direct("192.168.1.2"))
        assertTrue(!FoxRoute.direct("203.0.113.7"))
        FoxRoute.apply(false, "")
    }

    @Test
    fun fullUploadQueueDoesNotAcknowledgeDiscardedBytesAndReopensWindow() {
        val input = PacketInput()
        val output = PacketOutput()
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val pipe = object : FoxPipe {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { stopped.await(); return -1 }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { release.await() }
            override fun close() { stopped.countDown(); release.countDown() }
        }
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ -> pipe }, { _, _ -> })
        stack.start()
        try {
            input.offer(tcp(1000, 0, 0x02, byteArrayOf()))
            val synAck = output.next()
            val peer = u32(synAck, 24) + 1
            var sequence = 1001L
            val payload = ByteArray(1360) { 5 }
            var rejected = false
            repeat(500) {
                if (!rejected) {
                    input.offer(tcp(sequence, peer, 0x18, payload))
                    val ack = output.next()
                    val next = u32(ack, 28)
                    if (next == sequence) rejected = true else {
                        assertEquals(sequence + payload.size, next)
                        sequence = next
                    }
                }
            }
            assertTrue("the bounded queue should apply backpressure", rejected)
            release.countDown()
            val update = output.find { u32(it, 28) == sequence && ((it[34].toInt() and 255) shl 8 or (it[35].toInt() and 255)) > 0 }
            assertEquals(sequence, u32(update, 28))
            input.offer(tcp(sequence, peer, 0x18, payload))
            output.find { u32(it, 28) == sequence + payload.size }
        } finally {
            release.countDown()
            stack.stop()
            input.close()
        }
    }

    @Test
    fun cancelledDialClosesTheLatePipe() {
        val input = PacketInput()
        val output = PacketOutput()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val pipe = object : FoxPipe {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
            override fun close() { closed.countDown() }
        }
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ ->
            started.countDown()
            release.await()
            pipe
        }, { _, _ -> })
        stack.start()
        try {
            input.offer(tcp(1000, 0, 0x02, byteArrayOf()))
            output.next()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            input.offer(tcp(1001, 0, 0x04, byteArrayOf()))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (stack.active() > 0 && System.nanoTime() < until) Thread.yield()
            assertEquals(0, stack.active())
            release.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            stack.stop()
            input.close()
        }
    }

    @Test
    fun finWithDataIsDeliveredBeforeHalfClose() {
        val input = PacketInput()
        val output = PacketOutput()
        val written = ByteArrayOutputStream()
        val finished = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val pipe = object : FoxPipe {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { closed.await(); return -1 }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { written.write(buffer, offset, length) }
            override fun finishWrite() { finished.countDown() }
            override fun close() { closed.countDown() }
        }
        val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { _, _, _ -> pipe }, { _, _ -> })
        stack.start()
        try {
            input.offer(tcp(1000, 0, 0x02, byteArrayOf()))
            val synAck = output.next()
            val payload = "last bytes".toByteArray()
            input.offer(tcp(1001, u32(synAck, 24) + 1, 0x11, payload))
            val ack = output.next()
            assertEquals(1001L + payload.size + 1, u32(ack, 28))
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertArrayEquals(payload, written.toByteArray())
        } finally {
            stack.stop()
            input.close()
        }
    }

    private class MemoryTunnel : Closeable {
        val input = PacketInput()
        val output = PacketOutput()
        val pipe = EchoPipe()
        val dialed = AtomicReference<String?>()
        val uploaded = AtomicInteger()
        val downloaded = AtomicInteger()
        val downloadedReady = CountDownLatch(1)
        private val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { host, _, port ->
            dialed.set("$host:$port")
            pipe
        }, { inbound, bytes ->
            if (inbound) {
                downloaded.addAndGet(bytes.toInt())
                downloadedReady.countDown()
            } else uploaded.addAndGet(bytes.toInt())
        })

        init {
            stack.start()
        }

        override fun close() {
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
        fun next(): ByteArray {
            val packet = packets.poll(5, TimeUnit.SECONDS)
            assertNotNull("no packet returned", packet)
            return packet!!
        }
        fun find(predicate: (ByteArray) -> Boolean): ByteArray {
            repeat(5) {
                val packet = next()
                if (predicate(packet)) return packet
            }
            throw AssertionError("expected packet was not emitted")
        }
    }

    private class EchoPipe : FoxPipe {
        val upload = ByteArrayOutputStream()
        private val pending = LinkedBlockingQueue<ByteArray>()
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            val data = buffer.copyOfRange(offset, offset + length)
            upload.write(data)
            pending.put(data)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val data = pending.poll(5, TimeUnit.SECONDS) ?: return -1
            if (data.isEmpty()) return -1
            val count = minOf(data.size, length)
            System.arraycopy(data, 0, buffer, offset, count)
            return count
        }
        override fun close() {
            pending.offer(byteArrayOf())
        }
    }

    companion object {
        private fun ipv4(protocol: Int, payloadLength: Int): ByteArray {
            val packet = ByteArray(20 + payloadLength)
            packet[0] = 0x45
            packet[8] = 64
            packet[9] = protocol.toByte()
            put16(packet, 2, packet.size)
            byteArrayOf(10, 111, 0, 2).copyInto(packet, 12)
            byteArrayOf(203.toByte(), 0, 113, 7).copyInto(packet, 16)
            return packet
        }

        private fun tcp(sequence: Long, acknowledgment: Long, flags: Int, data: ByteArray): ByteArray {
            val packet = ipv4(6, 20 + data.size)
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

        private fun udp(port: Int, data: ByteArray): ByteArray {
            val packet = ipv4(17, 8 + data.size)
            put16(packet, 20, 40000)
            put16(packet, 22, port)
            put16(packet, 24, 8 + data.size)
            data.copyInto(packet, 28)
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
