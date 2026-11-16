package com.gozar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class FoxUdpAssociateTest {

    private fun query(id: Int) = byteArrayOf(
        0, id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        3, 119, 119, 119, 4, 116, 101, 115, 116, 0, 0, 1, 0, 1
    )

    private fun answer(q: ByteArray, ip: ByteArray): ByteArray {
        val head = q.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
        }
        return head + byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4) + ip
    }

    private fun wireQuery(): ByteArray = query(9)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun socksDatagram(atyp: Int, address: ByteArray, port: Int, payload: ByteArray): ByteArray {
        val addrField = if (atyp == 0x03) byteArrayOf(address.size.toByte()) + address else address
        val out = ByteArray(3 + 1 + addrField.size + 2 + payload.size)
        out[3] = atyp.toByte()
        addrField.copyInto(out, 4)
        putU16(out, 4 + addrField.size, port)
        payload.copyInto(out, 4 + addrField.size + 2)
        return out
    }

    private inner class FakeDatagramPipe : FoxDatagramPipe {
        val sent = LinkedBlockingQueue<ByteArray>()
        private val replies = LinkedBlockingQueue<ByteArray>()
        override fun send(payload: ByteArray) { sent.offer(payload) }
        override fun receive(): ByteArray? = replies.poll(5, TimeUnit.SECONDS)
        override fun close() = Unit
        fun emit(payload: ByteArray) = replies.offer(payload)
    }

    private fun relay(
        bridge: FoxDnsBridge?,
        openUdp: (String, Int) -> FoxDatagramPipe? = { _, _ -> null },
        stale: () -> Boolean = { false },
        count: ((Boolean, Long) -> Unit)? = null,
    ): Pair<FoxUdpAssociate, DatagramSocket> {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val association = FoxUdpAssociate(socket, 1280, bridge, openUdp, count, stale)
        association.start()
        return association to socket
    }

    @Test
    fun dnsDatagramsAreAnsweredLocallyWithTheRequestedSource() {
        val resolvedTo = byteArrayOf(1, 2, 3, 4)
        val resolver = FoxResolver(0, { _, q -> answer(q, resolvedTo) }, endpoints = listOf("synthetic"))
        val bridge = FoxDnsBridge(resolver, 1280) { FoxDnsScope("test") { true } }
        val (association, socket) = relay(bridge)
        val client = DatagramSocket()
        try {
            val packet = socksDatagram(0x01, byteArrayOf(10, 111, 0, 53), 53, wireQuery())
            client.send(DatagramPacket(packet, packet.size, InetAddress.getByName("127.0.0.1"), socket.localPort))
            client.soTimeout = 3000
            val buf = ByteArray(65535)
            val incoming = DatagramPacket(buf, buf.size)
            client.receive(incoming)
            val data = incoming.data.copyOfRange(incoming.offset, incoming.offset + incoming.length)
            assertEquals(0x01, data[3].toInt() and 255)
            assertArrayEquals(byteArrayOf(10, 111, 0, 53), data.copyOfRange(4, 8))
            assertEquals(53, ((data[8].toInt() and 255) shl 8) or (data[9].toInt() and 255))
            // The trailing A record carries the resolved address.
            assertArrayEquals(resolvedTo, data.copyOfRange(data.size - 4, data.size))
            assertEquals("www.test", resolver.nameOf(0x01020304))
        } finally {
            client.close()
            association.close()
        }
    }

    @Test
    fun nonDnsDatagramsFlowThroughTheOpenedTunnelAndReturnWithTheFlowSource() {
        val pipe = FakeDatagramPipe()
        var openedFor: Pair<String, Int>? = null
        val (association, socket) = relay(
            bridge = null,
            openUdp = { host, port -> openedFor = host to port; pipe }
        )
        val client = DatagramSocket()
        try {
            val payload = "ping".toByteArray()
            val target = byteArrayOf(203.toByte(), 0, 113, 7)
            val packet = socksDatagram(0x01, target, 443, payload)
            client.send(DatagramPacket(packet, packet.size, InetAddress.getByName("127.0.0.1"), socket.localPort))
            val sent = pipe.sent.poll(3, TimeUnit.SECONDS)
            assertNotNull("flow never opened", sent)
            assertArrayEquals(payload, sent)
            assertEquals("203.0.113.7" to 443, openedFor)
            // The relay must present the answer as coming from the target.
            val answer = "pong".toByteArray()
            pipe.emit(answer)
            client.soTimeout = 3000
            val buf = ByteArray(65535)
            val incoming = DatagramPacket(buf, buf.size)
            client.receive(incoming)
            val data = incoming.data.copyOfRange(incoming.offset, incoming.offset + incoming.length)
            assertEquals(0x01, data[3].toInt() and 255)
            assertArrayEquals(target, data.copyOfRange(4, 8))
            assertEquals(443, ((data[8].toInt() and 255) shl 8) or (data[9].toInt() and 255))
            assertArrayEquals(answer, data.copyOfRange(10, data.size))
        } finally {
            client.close()
            association.close()
        }
    }

    @Test
    fun staleAttemptsDropDnsAndRelayTraffic() {
        val resolver = FoxResolver(0, { _, q -> answer(q, byteArrayOf(1, 2, 3, 4)) }, endpoints = listOf("synthetic"))
        val bridge = FoxDnsBridge(resolver, 1280) { FoxDnsScope("test") { false } } // always stale
        val opened = LinkedBlockingQueue<String>()
        val (association, socket) = relay(
            bridge = bridge,
            openUdp = { host, port -> opened.offer("$host:$port"); FakeDatagramPipe() },
            stale = { true }
        )
        val client = DatagramSocket()
        try {
            val target = byteArrayOf(10, 111, 0, 53)
            val dns = socksDatagram(0x01, target, 53, wireQuery())
            client.send(DatagramPacket(dns, dns.size, InetAddress.getByName("127.0.0.1"), socket.localPort))
            val other = socksDatagram(0x01, byteArrayOf(203.toByte(), 0, 113, 7), 443, "ping".toByteArray())
            client.send(DatagramPacket(other, other.size, InetAddress.getByName("127.0.0.1"), socket.localPort))
            client.soTimeout = 1200
            val buf = ByteArray(64)
            try {
                client.receive(DatagramPacket(buf, buf.size))
                throw AssertionError("a stale attempt published a reply")
            } catch (_: java.net.SocketTimeoutException) {
                // Expected: fail closed.
            }
            assertEquals(0, opened.size)
        } finally {
            client.close()
            association.close()
        }
    }

    @Test
    fun fragmentedDatagramsAreDropped() {
        val resolver = FoxResolver(0, { _, q -> answer(q, byteArrayOf(1, 2, 3, 4)) }, endpoints = listOf("synthetic"))
        val bridge = FoxDnsBridge(resolver, 1280) { FoxDnsScope("test") { true } }
        val (association, socket) = relay(bridge)
        val client = DatagramSocket()
        try {
            val target = byteArrayOf(10, 111, 0, 53)
            val base = socksDatagram(0x01, target, 53, wireQuery())
            base[2] = 1 // FRAG != 0
            client.send(DatagramPacket(base, base.size, InetAddress.getByName("127.0.0.1"), socket.localPort))
            client.soTimeout = 1000
            try {
                client.receive(DatagramPacket(ByteArray(64), 64))
                throw AssertionError("a fragmented datagram was answered")
            } catch (_: java.net.SocketTimeoutException) {
                // Expected.
            }
        } finally {
            client.close()
            association.close()
        }
    }

    @Test
    fun socksAssociateNegotiatesALoopbackRelayAndServesDns() {
        val resolvedTo = byteArrayOf(5, 6, 7, 8)
        val resolver = FoxResolver(0, { _, q -> answer(q, resolvedTo) }, endpoints = listOf("synthetic"))
        val bridge = FoxDnsBridge(resolver, 1280) { FoxDnsScope("test") { true } }
        FoxLink.attachDns(bridge)
        val proxy = FoxProxy(0, pass = { "session-credential" })
        assertEquals(true, proxy.start())
        try {
            java.net.Socket("127.0.0.1", proxy.boundPort()).use { control ->
                control.soTimeout = 3000
                control.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x02))
                control.getOutputStream().flush()
                val method = ByteArray(2)
                readFully(control.getInputStream(), method)
                assertArrayEquals(byteArrayOf(0x05, 0x02), method)
                val credential = proxy.sessionCredential().toByteArray(Charsets.UTF_8)
                val login = ByteArray(3 + 2 * credential.size)
                login[0] = 0x01
                login[1] = credential.size.toByte()
                credential.copyInto(login, 2)
                login[2 + credential.size] = credential.size.toByte()
                credential.copyInto(login, 3 + credential.size)
                control.getOutputStream().write(login)
                control.getOutputStream().flush()
                val auth = ByteArray(2)
                readFully(control.getInputStream(), auth)
                assertArrayEquals(byteArrayOf(0x01, 0x00), auth)
                // UDP ASSOCIATE with the (ignored) zero endpoint, exactly what hev sends.
                control.getOutputStream().write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                control.getOutputStream().flush()
                val reply = ByteArray(10)
                readFully(control.getInputStream(), reply)
                assertEquals(0x05, reply[0].toInt())
                assertEquals(0x00, reply[1].toInt())
                assertEquals(0x01, reply[3].toInt())
                assertEquals(127, reply[4].toInt())
                val bndPort = ((reply[8].toInt() and 255) shl 8) or (reply[9].toInt() and 255)
                val client = DatagramSocket()
                try {
                    val dns = socksDatagram(0x01, byteArrayOf(10, 111, 0, 53), 53, wireQuery())
                    val target = InetAddress.getByName("127.0.0.1")
                    client.send(DatagramPacket(dns, dns.size, target, bndPort))
                    client.soTimeout = 3000
                    val buf = ByteArray(65535)
                    val packet = DatagramPacket(buf, buf.size)
                    client.receive(packet)
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    assertEquals(0x01, data[3].toInt() and 255)
                    assertArrayEquals(byteArrayOf(10, 111, 0, 53), data.copyOfRange(4, 8))
                    assertEquals(53, ((data[8].toInt() and 255) shl 8) or (data[9].toInt() and 255))
                    assertArrayEquals(resolvedTo, data.copyOfRange(data.size - 4, data.size))
                } finally {
                    client.close()
                }
            }
        } finally {
            proxy.stop()
            FoxLink.attachDns(null)
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray) {
        var done = 0
        while (done < buffer.size) {
            val n = input.read(buffer, done, buffer.size - done)
            if (n < 0) throw java.io.EOFException()
            done += n
        }
    }
}
