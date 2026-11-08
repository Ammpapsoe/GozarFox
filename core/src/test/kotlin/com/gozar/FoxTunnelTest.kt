package com.gozar

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FoxTunnelTest {
    private lateinit var originalContext: SSLContext
    private lateinit var originalHops: List<FoxHop>

    @Before
    fun prepare() {
        originalContext = SSLContext.getDefault()
        originalHops = FoxUpstream.hops
        SSLContext.setDefault(clientContext("test-ca.pem"))
        resetH2()
    }

    @After
    fun restore() {
        SSLContext.setDefault(originalContext)
        FoxUpstream.hops = originalHops
        resetH2()
    }

    @Test
    fun h2TunnelAuthenticatesAndPreservesLargeFramesAcrossSmallReads() {
        LocalProxy().use { proxy ->
            val reply = exchange(proxy)
            assertArrayEquals(proxy.reply, reply)
            assertEquals(listOf("h2"), proxy.protocols.toList())
            assertEquals("upload-over-tunnel", String(proxy.upload.toByteArray(), Charsets.US_ASCII))
            assertTrue(proxy.connectHeads.single().contains("CONNECT edge.fastly.invalid:2499"))
            assertTrue(proxy.authHeads.single().contains("Bearer local-test-only"))
            assertTrue(proxy.problems.isEmpty())
        }
    }

    @Test
    fun udpExtendedConnectPreservesDatagramBoundaries() {
        LocalProxy(udpMode = true).use { proxy ->
            FoxUpstream.hops = listOf(FoxHop("http", "127.0.0.1", proxy.port))
            val location = FoxLocation("", "", "", "edge.fastly.invalid", 2499, false)
            val stream = FoxTunnel.openUDP(location, "203.0.113.7", 443, "local-test-only", 4000)
            try {
                for (payload in listOf("one".toByteArray(), byteArrayOf(), "another".toByteArray())) {
                    stream.send(payload)
                    assertArrayEquals(payload, stream.receive())
                }
                val request = proxy.authHeads.single()
                assertTrue(request.contains("connect-udp"))
                assertTrue(request.contains("/.well-known/masque/udp/203.0.113.7/443/"))
                assertTrue(request.contains("Bearer local-test-only"))
                assertTrue(proxy.problems.isEmpty())
            } finally {
                stream.close()
            }
        }
    }

    @Test
    fun udpRequiresExtendedConnectAndCapsuleConfirmation() {
        for ((settings, capsule) in listOf(false to true, true to false)) {
            LocalProxy(udpMode = true, udpSettings = settings, udpCapsule = capsule).use { proxy ->
                FoxTunnel.resetTransport()
                FoxUpstream.hops = listOf(FoxHop("http", "127.0.0.1", proxy.port))
                val location = FoxLocation("", "", "", "edge.fastly.invalid", 2499, false)
                assertTrue(runCatching { FoxTunnel.openUDP(location, "203.0.113.7", 443, "local-test-only", 4000) }.isFailure)
                assertEquals(listOf("h2"), proxy.protocols.toList())
            }
        }
    }

    @Test
    fun udpDestinationMustBeANumericIpv4Address() {
        assertEquals("/.well-known/masque/udp/203.0.113.7/443/", FoxTunnel.udpPath("203.0.113.7", 443))
        for (value in listOf("example.com", "1.2.3.256", "1.2.03.4", "1.2.3", "1.2.3.4/evil")) {
            assertTrue("accepted $value", runCatching { FoxTunnel.udpPath(value, 443) }.isFailure)
        }
    }

    @Test
    fun paddedAndContinuedHeadersWithTableUpdateAreAccepted() {
        LocalProxy(splitH2Headers = true).use { proxy ->
            assertArrayEquals(proxy.reply, exchange(proxy))
            assertTrue(proxy.problems.isEmpty())
        }
    }

    @Test
    fun huffmanEncodedAuthenticationRefusalDoesNotRetryAnotherProtocol() {
        LocalProxy(refuseH2 = true).use { proxy ->
            val error = failure { open(proxy) }
            assertTrue(error is FoxProxyRefusal)
            assertEquals(407, (error as FoxProxyRefusal).status)
            assertEquals(listOf("h2"), proxy.protocols.toList())
        }
    }

    @Test
    fun zeroInitialWindowAndEarlyUpdatePermitBoundedUpload() {
        LocalProxy(smallWindow = true).use { proxy ->
            val pipe = open(proxy)
            val done = LinkedBlockingQueue<ByteArray>()
            val reader = thread(isDaemon = true) {
                val result = ByteArrayOutputStream()
                val data = ByteArray(512)
                while (true) {
                    val count = pipe.read(data, 0, data.size)
                    if (count < 0) break
                    result.write(data, 0, count)
                }
                done.put(result.toByteArray())
            }
            try {
                val request = "upload-over-tunnel".toByteArray(Charsets.US_ASCII)
                pipe.write(request, 0, request.size)
                val received = done.poll(5, TimeUnit.SECONDS)
                if (received == null || received.size != proxy.reply.size) {
                    // Surface the internals so a rare race identifies itself.
                    val probe = try {
                        pipe.finishWrite()
                        "session alive"
                    } catch (error: Exception) {
                        "session dead: ${error.message}"
                    }
                    fail(
                        "reader got ${received?.size ?: -1} of ${proxy.reply.size} bytes; " +
                            "proxy upload=${proxy.upload.size()}B protocols=${proxy.protocols} " +
                            "problems=${proxy.problems} $probe"
                    )
                }
                assertArrayEquals(proxy.reply, received)
                assertEquals("upload-over-tunnel", String(proxy.upload.toByteArray(), Charsets.US_ASCII))
                assertTrue(proxy.problems.isEmpty())
            } finally {
                pipe.close()
                reader.join(2000)
            }
        }
    }

    @Test
    fun unavailableH2AlpnFallsBackToVerifiedH1() {
        LocalProxy(h1Only = true).use { proxy ->
            assertArrayEquals(proxy.reply, exchange(proxy))
            assertEquals(listOf("http/1.1"), proxy.protocols.toList())
            assertTrue(proxy.problems.isEmpty())
        }
    }

    @Test
    fun pendingH1OpenIsRejectedAfterResetAcrossFiftyRaces() {
        val gates = LinkedBlockingQueue<Pair<CountDownLatch, CountDownLatch>>()
        LocalProxy(h1Only = true, beforeH1Reply = {
            val (entered, release) = gates.poll(10, TimeUnit.SECONDS) ?: error("missing H1 gate")
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }).use { proxy ->
            repeat(50) { iteration ->
                resetH2()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val handedOff = AtomicBoolean(false)
                gates.put(entered to release)
                val worker = thread(isDaemon = true) {
                    try {
                        val tunnel = open(proxy)
                        handedOff.set(true)
                        tunnel.close()
                    } catch (_: Exception) { }
                }
                try {
                    assertTrue("H1 open did not enter at iteration $iteration", entered.await(8, TimeUnit.SECONDS))
                    FoxTunnel.resetTransport()
                } finally {
                    release.countDown()
                }
                worker.join(10000)
                assertFalse("H1 open did not finish at iteration $iteration", worker.isAlive)
                assertFalse("obsolete H1 open escaped to the caller at iteration $iteration", handedOff.get())
                assertEquals(0, FoxTunnel.pooledSessionCount())
            }
        }
    }

    @Test
    fun resetClosesPendingH1ResponseAcrossFiftyRaces() {
        val gates = LinkedBlockingQueue<Pair<CountDownLatch, CountDownLatch>>()
        LocalProxy(h1Only = true, beforeH1Reply = {
            val (entered, release) = gates.poll(10, TimeUnit.SECONDS) ?: error("missing H1 gate")
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }).use { proxy ->
            repeat(50) { iteration ->
                resetH2()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                gates.put(entered to release)
                val worker = thread(isDaemon = true) {
                    try { open(proxy).close() } catch (_: Exception) { }
                }
                try {
                    assertTrue("H1 response did not block at $iteration", entered.await(8, TimeUnit.SECONDS))
                    FoxTunnel.resetTransport()
                    worker.join(2000)
                    assertFalse("reset left H1 response pending at $iteration", worker.isAlive)
                    assertEquals("pending H1 relay leaked at $iteration", 0, FoxTunnel.pendingRelayCount())
                } finally {
                    release.countDown()
                    worker.join(10000)
                }
            }
        }
    }

    @Test
    fun successfulH1TransfersOutOfPendingAcrossFiftyTransitions() {
        LocalProxy(h1Only = true).use { proxy ->
            repeat(50) { iteration ->
                resetH2()
                val tunnel = open(proxy)
                try {
                    assertEquals("H1 stayed pending at $iteration", 0, FoxTunnel.pendingRelayCount())
                    assertEquals("H1 was not tracked at $iteration", 1, FoxTunnel.activeH1Count())
                } finally {
                    tunnel.close()
                }
                assertEquals(0, FoxTunnel.activeH1Count())
            }
        }
    }

    @Test
    fun establishedH1IsRevokedOnResetAcrossFiftyRaces() {
        LocalProxy(h1Only = true).use { proxy ->
            repeat(50) { iteration ->
                resetH2()
                val tunnel = open(proxy)
                try {
                    assertEquals("H1 stream not tracked at iteration $iteration", 1, FoxTunnel.activeH1Count())
                    FoxTunnel.resetTransport()
                    assertEquals("H1 registry not cleared at iteration $iteration", 0, FoxTunnel.activeH1Count())
                    var accepted = false
                    try {
                        tunnel.write(byteArrayOf(1), 0, 1)
                        accepted = true
                    } catch (_: Exception) { }
                    assertFalse("established H1 survived reset at iteration $iteration", accepted)
                } finally {
                    tunnel.close()
                }
            }
        }
    }

    @Test
    fun staleTransportTicketCannotOpenAfterRestartAcrossFiftyRaces() {
        LocalProxy(h1Only = true).use { proxy ->
            FoxUpstream.hops = listOf(FoxHop("http", "127.0.0.1", proxy.port))
            val location = FoxLocation("", "", "", "edge.fastly.invalid", 2499, false)
            try {
                repeat(50) { iteration ->
                    val stale = FoxTunnel.startTransport()
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val opened = AtomicBoolean(false)
                    val failure = AtomicReference("")
                    val worker = thread(isDaemon = true) {
                        entered.countDown()
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            failure.set("test gate timed out")
                        } else try {
                            val pipe = FoxTunnel.open(location, "target.example.invalid", 443, "local-test-only", 4000, stale)
                            opened.set(true)
                            pipe.close()
                        } catch (error: Exception) { failure.set(error.message ?: "") }
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    FoxTunnel.stopTransport()
                    assertEquals(null, FoxTunnel.currentTransport())
                    val stoppedError = try {
                        FoxTunnel.open(location, "target.example.invalid", 443, "local-test-only", 4000).close()
                        ""
                    } catch (error: Exception) { error.message }
                    assertEquals("transport reset during session creation", stoppedError)
                    val fresh = FoxTunnel.startTransport()
                    assertTrue(fresh != stale && FoxTunnel.transportIsCurrent(fresh))
                    release.countDown()
                    worker.join(10000)
                    assertFalse("stale worker blocked at iteration $iteration", worker.isAlive)
                    assertFalse("stale ticket opened at iteration $iteration", opened.get())
                    assertEquals("transport reset during session creation", failure.get())
                    assertEquals("stale ticket reached a network socket at iteration $iteration", 0, proxy.connectHeads.size)
                }
            } finally {
                FoxTunnel.startTransport()
            }
        }
    }

    @Test
    fun establishedH1CloseAndResetCompleteAcrossFiftyRaces() {
        LocalProxy(h1Only = true).use { proxy ->
            repeat(50) { iteration ->
                resetH2()
                val tunnel = open(proxy)
                tunnel.write(byteArrayOf(1, 2, 3), 0, 3)
                val start = CountDownLatch(1)
                val closer = thread(isDaemon = true) { start.await(); tunnel.close() }
                val resetter = thread(isDaemon = true) { start.await(); FoxTunnel.resetTransport() }
                start.countDown()
                closer.join(10000)
                resetter.join(10000)
                assertFalse("H1 close blocked at iteration $iteration", closer.isAlive)
                assertFalse("H1 reset blocked at iteration $iteration", resetter.isAlive)
                assertEquals("H1 stream leaked at iteration $iteration", 0, FoxTunnel.activeH1Count())
                var accepted = false
                try {
                    tunnel.write(byteArrayOf(1), 0, 1)
                    accepted = true
                } catch (_: Exception) { }
                assertFalse("closed H1 accepted a write at iteration $iteration", accepted)
            }
        }
    }

    @Test
    fun h2FailureFallsBackToVerifiedH1Tunnel() {
        LocalProxy(rejectH2 = true).use { proxy ->
            val reply = exchange(proxy)
            assertArrayEquals(proxy.reply, reply)
            assertEquals(listOf("h2", "http/1.1"), proxy.protocols.toList())
            assertTrue(proxy.authHeads.last().contains("Proxy-Authorization: Bearer local-test-only"))
            assertEquals("upload-over-tunnel", String(proxy.upload.toByteArray(), Charsets.US_ASCII))
            assertTrue(proxy.problems.isEmpty())
        }
    }

    @Test
    fun packetStackAndVerifiedH2TunnelWorkTogetherWithoutAnOsDevice() {
        LocalProxy().use { proxy ->
            val input = PacketInput()
            val output = PacketOutput()
            FoxUpstream.hops = listOf(FoxHop("http", "127.0.0.1", proxy.port))
            val location = FoxLocation("", "", "", "edge.fastly.invalid", 2499, false)
            val stack = FoxStack(input, output, 0x0a6f0002, FoxResolver(0), { host, _, port ->
                FoxTunnel.open(location, host, port, "local-test-only", 4000)
            }, { _, _ -> })
            stack.start()
            try {
                val sequence = 1000L
                input.offer(tcp(sequence, 0, 0x02, byteArrayOf()))
                val synAck = output.next()
                assertEquals(0x12, synAck[33].toInt() and 0xff)
                val serverSequence = u32(synAck, 24) + 1
                input.offer(tcp(sequence + 1, serverSequence, 0x10, byteArrayOf()))
                val request = "upload-over-tunnel".toByteArray(Charsets.US_ASCII)
                input.offer(tcp(sequence + 1, serverSequence, 0x18, request))
                val received = ByteArrayOutputStream()
                while (received.size() < proxy.reply.size) {
                    val packet = output.next()
                    if ((packet[9].toInt() and 0xff) == 6 && packet.size > 40) {
                        received.write(packet, 40, packet.size - 40)
                    }
                }
                assertArrayEquals(proxy.reply, received.toByteArray())
                assertEquals("upload-over-tunnel", String(proxy.upload.toByteArray(), Charsets.US_ASCII))
                assertTrue(proxy.authHeads.single().contains("203.0.113.7:443"))
                assertTrue(proxy.authHeads.single().contains("Bearer local-test-only"))
            } finally {
                stack.stop()
                input.close()
            }
        }
    }

    @Test
    fun mismatchedHostnameIsRejectedBeforeAuthorization() {
        LocalProxy().use { proxy ->
            val error = failure { open(proxy, "other.fastly.invalid") }
            assertTrue(FoxTunnel.isTlsFailure(error))
            assertTrue(proxy.authHeads.isEmpty())
        }
    }

    @Test
    fun unknownRootIsRejectedBeforeAuthorization() {
        SSLContext.setDefault(clientContext("other-ca.pem"))
        LocalProxy().use { proxy ->
            val error = failure { open(proxy) }
            assertTrue(FoxTunnel.isTlsFailure(error))
            assertTrue(proxy.authHeads.isEmpty())
        }
    }

    @Test
    fun expiredCertificateIsRejectedBeforeAuthorization() {
        LocalProxy(alwaysExpired = true).use { proxy ->
            val error = failure { open(proxy) }
            assertTrue(FoxTunnel.isTlsFailure(error))
            assertTrue(proxy.authHeads.isEmpty())
        }
    }

    @Test
    fun fallbackCertificateFailureIsNotHiddenByFirstProtocolError() {
        LocalProxy(rejectH2 = true, secondExpired = true).use { proxy ->
            val error = failure { open(proxy) }
            assertTrue(FoxTunnel.isTlsFailure(error))
            assertEquals(listOf("h2"), proxy.protocols.toList())
        }
    }

    @Test
    fun classifiesNestedSecurityErrorsWithoutTreatingOrdinaryIoAsTls() {
        assertTrue(FoxTunnel.isTlsFailure(IllegalStateException("outer", java.security.cert.CertificateException("bad"))))
        assertFalse(FoxTunnel.isTlsFailure(java.io.IOException("timeout")))
    }

    private fun exchange(proxy: LocalProxy): ByteArray {
        val pipe = open(proxy)
        try {
            val request = "upload-over-tunnel".toByteArray(Charsets.US_ASCII)
            pipe.write(request, 0, request.size)
            val result = ByteArrayOutputStream()
            val small = ByteArray(137)
            while (true) {
                val count = pipe.read(small, 0, small.size)
                if (count < 0) break
                result.write(small, 0, count)
            }
            return result.toByteArray()
        } finally {
            pipe.close()
        }
    }

    private fun open(proxy: LocalProxy, name: String = "edge.fastly.invalid"): FoxDuplex {
        FoxUpstream.hops = listOf(FoxHop("http", "127.0.0.1", proxy.port))
        val location = FoxLocation("", "", "", name, 2499, false)
        return FoxTunnel.open(location, "target.example.invalid", 443, "local-test-only", 4000)
    }

    private fun failure(block: () -> Unit): Exception {
        try {
            block()
            fail("expected a TLS failure")
        } catch (error: Exception) {
            return error
        }
        throw AssertionError("expected a TLS failure")
    }

    private fun resetH2() {
        FoxTunnel.resetTransport()
    }

    private fun clientContext(root: String): SSLContext {
        val certificate = resource(root).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        store.setCertificateEntry("loopback-root", certificate)
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(store)
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, SecureRandom()) }
    }

    private class LocalProxy(
        private val rejectH2: Boolean = false,
        private val alwaysExpired: Boolean = false,
        private val secondExpired: Boolean = false,
        private val splitH2Headers: Boolean = false,
        private val refuseH2: Boolean = false,
        private val smallWindow: Boolean = false,
        private val h1Only: Boolean = false,
        private val beforeH1Reply: (() -> Unit)? = null,
        private val udpMode: Boolean = false,
        private val udpSettings: Boolean = true,
        private val udpCapsule: Boolean = true
    ) : Closeable {
        val reply = ByteArray(8000) { (it % 251).toByte() }
        val upload = ByteArrayOutputStream()
        val protocols = Collections.synchronizedList(mutableListOf<String>())
        val connectHeads = Collections.synchronizedList(mutableListOf<String>())
        val authHeads = Collections.synchronizedList(mutableListOf<String>())
        val problems = ConcurrentLinkedQueue<Throwable>()
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val valid = serverContext("server.p12")
        private val expired = serverContext("expired-server.p12")
        val port: Int get() = server.localPort
        private val worker = thread(isDaemon = true, name = "loopback-proxy-test") {
            var count = 0
            while (!server.isClosed) {
                val raw = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                count++
                try {
                    raw.use { handle(it, count) }
                } catch (_: SSLException) {
                } catch (_: SocketException) {
                } catch (_: EOFException) {
                } catch (error: Throwable) {
                    problems.add(error)
                }
            }
        }

        private fun handle(raw: Socket, count: Int) {
            raw.soTimeout = 5000
            val rawInput = BufferedInputStream(raw.getInputStream())
            connectHeads.add(readHead(rawInput))
            raw.getOutputStream().apply {
                write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val context = if (alwaysExpired || (secondExpired && count == 2)) expired else valid
            val socket = context.socketFactory.createSocket(raw, "127.0.0.1", raw.port, true) as SSLSocket
            socket.use {
                socket.useClientMode = false
                socket.sslParameters = socket.sslParameters.apply { applicationProtocols = if (h1Only) arrayOf("http/1.1") else arrayOf("h2", "http/1.1") }
                socket.startHandshake()
                val protocol = socket.applicationProtocol
                protocols.add(protocol)
                if (protocol == "h2") h2(socket) else if (protocol == "http/1.1") h1(socket) else error("unexpected ALPN: $protocol")
            }
        }

        private fun h2(socket: SSLSocket) {
            val input = BufferedInputStream(socket.inputStream)
            val preface = ByteArray(24)
            readFully(input, preface)
            check(String(preface, Charsets.US_ASCII) == "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")
            check(readFrame(input).first == 4)
            val output = socket.outputStream
            if (udpMode) {
                udpH2(input, output)
                return
            }
            val headers = readFrame(input)
            check(headers.first == 1)
            authHeads.add(String(headers.second, Charsets.UTF_8))
            if (rejectH2) {
                output.write(frame(7, 0, 0, ByteArray(8)))
                output.flush()
                return
            }
            val settings = if (smallWindow) byteArrayOf(0, 4, 0, 0, 0, 0) else ByteArray(0)
            output.write(frame(4, 0, 0, settings))
            if (smallWindow) output.write(frame(8, 0, 1, byteArrayOf(0, 0, 0, 5)))
            if (refuseH2) {
                output.write(frame(1, 4, 1, byteArrayOf(0x08, 0x83.toByte(), 0x68, 0x0e, 0xff.toByte())))
                output.flush()
                return
            } else if (splitH2Headers) {
                output.write(frame(1, 0x28, 1, byteArrayOf(2, 0, 0, 0, 0, 0, 0x20, 0, 0)))
                output.write(frame(9, 4, 1, byteArrayOf(0x88.toByte())))
            } else {
                output.write(frame(1, 4, 1, byteArrayOf(0x88.toByte())))
            }
            output.flush()
            while (upload.size() < "upload-over-tunnel".length) {
                val data = readFrame(input)
                if (data.first == 0) {
                    upload.write(data.second)
                    if (smallWindow) {
                        val amount = data.second.size
                        output.write(frame(8, 0, 1, byteArrayOf(0, 0, 0, amount.toByte())))
                        output.flush()
                    }
                }
            }
            output.write(frame(0, 1, 1, reply))
            output.flush()
        }

        private fun udpH2(input: BufferedInputStream, output: java.io.OutputStream) {
            val settings = if (udpSettings) byteArrayOf(0, 8, 0, 0, 0, 1) else ByteArray(0)
            output.write(frame(4, 0, 0, settings))
            output.flush()
            if (!udpSettings) return
            while (true) {
                val request = readFrame(input)
                if (request.first != 1) continue
                authHeads.add(String(request.second, Charsets.UTF_8))
                break
            }
            val response = ByteArrayOutputStream().apply {
                write(0x88) // indexed :status 200
                if (udpCapsule) write(FoxTunnel.pair("capsule-protocol", "?1"))
            }.toByteArray()
            output.write(frame(1, 4, 1, response))
            output.flush()
            if (!udpCapsule) return
            while (true) {
                val data = readFrame(input)
                if (data.first == 3) return
                if (data.first == 0) {
                    output.write(frame(0, 0, 1, data.second))
                    output.flush()
                }
            }
        }

        private fun h1(socket: SSLSocket) {
            val input = BufferedInputStream(socket.inputStream)
            authHeads.add(readHead(input))
            val output = socket.outputStream
            beforeH1Reply?.invoke()
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            val data = ByteArray("upload-over-tunnel".length)
            readFully(input, data)
            upload.write(data)
            output.write(reply)
            output.flush()
        }

        override fun close() {
            server.close()
            worker.join(2000)
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

    private class PacketOutput : java.io.OutputStream() {
        private val packets = LinkedBlockingQueue<ByteArray>()
        override fun write(value: Int) = throw UnsupportedOperationException()
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            packets.put(buffer.copyOfRange(offset, offset + length))
        }
        fun next(): ByteArray = packets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no packet returned")
    }

    companion object {
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

        private fun resource(name: String): InputStream =
            FoxTunnelTest::class.java.getResourceAsStream("/tls/$name") ?: error("missing test resource $name")

        private fun serverContext(name: String): SSLContext {
            val store = KeyStore.getInstance("PKCS12")
            resource(name).use { store.load(it, "local-test-only".toCharArray()) }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            managers.init(store, "local-test-only".toCharArray())
            return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
        }

        private fun readHead(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (out.size() < 8192) {
                val c = input.read()
                if (c < 0) throw EOFException()
                out.write(c)
                val bytes = out.toByteArray()
                if (bytes.size >= 4 && bytes.takeLast(4) == listOf(13.toByte(), 10.toByte(), 13.toByte(), 10.toByte())) {
                    return String(bytes, Charsets.US_ASCII)
                }
            }
            error("header too long")
        }

        private fun readFully(input: InputStream, data: ByteArray) {
            var position = 0
            while (position < data.size) {
                val size = input.read(data, position, data.size - position)
                if (size < 0) throw EOFException()
                position += size
            }
        }

        private fun readFrame(input: InputStream): Pair<Int, ByteArray> {
            val head = ByteArray(9)
            readFully(input, head)
            val size = ((head[0].toInt() and 255) shl 16) or ((head[1].toInt() and 255) shl 8) or (head[2].toInt() and 255)
            val payload = ByteArray(size)
            readFully(input, payload)
            return (head[3].toInt() and 255) to payload
        }

        private fun frame(type: Int, flags: Int, id: Int, payload: ByteArray): ByteArray {
            val out = ByteArray(9 + payload.size)
            out[0] = (payload.size ushr 16).toByte()
            out[1] = (payload.size ushr 8).toByte()
            out[2] = payload.size.toByte()
            out[3] = type.toByte()
            out[4] = flags.toByte()
            out[5] = ((id ushr 24) and 0x7f).toByte()
            out[6] = (id ushr 16).toByte()
            out[7] = (id ushr 8).toByte()
            out[8] = id.toByte()
            System.arraycopy(payload, 0, out, 9, payload.size)
            return out
        }
    }
}
