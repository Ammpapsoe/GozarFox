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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Session-pool tests: concurrent tunnels must share one HTTP/2 connection,
 * the soft stream limit must never block, and a dead or refusing session
 * must be replaced instead of taking the tunnel down.
 */
class FoxTunnelPoolTest {
    private lateinit var originalContext: SSLContext
    private lateinit var originalHops: List<FoxHop>

    @Before
    fun prepare() {
        originalContext = SSLContext.getDefault()
        originalHops = FoxUpstream.hops
        SSLContext.setDefault(clientContext("test-ca.pem"))
        FoxTunnel.resetTransport()
    }

    @After
    fun restore() {
        SSLContext.setDefault(originalContext)
        FoxUpstream.hops = originalHops
        FoxTunnel.resetTransport()
    }

    @Test
    fun concurrentTunnelsShareOneSession() {
        EchoProxy().use { proxy ->
            val first = open(proxy)
            try {
                val results = ConcurrentHashMap<Int, String>()
                val threads = (1..3).map { index ->
                    thread(isDaemon = true) {
                        val tunnel = open(proxy)
                        try {
                            results[index] = echo(tunnel, "payload-$index")
                        } finally {
                            tunnel.close()
                        }
                    }
                }
                threads.forEach { it.join(15000) }
                assertEquals(3, results.size)
                for (index in 1..3) assertEquals("payload-$index", results[index])
                assertEquals("payload-0", echo(first, "payload-0"))
                assertEquals(1, proxy.sessions.get())
                assertEquals(4, proxy.streamIds.distinct().size)
                assertTrue(proxy.problems.isEmpty())
            } finally {
                first.close()
            }
        }
    }

    @Test
    fun streamsBeyondTheSoftLimitOpenASecondSessionAndStillSucceed() {
        EchoProxy().use { proxy ->
            val tunnels = ArrayList<FoxDuplex>()
            try {
                for (index in 1..12) {
                    val tunnel = open(proxy)
                    tunnels.add(tunnel)
                    assertEquals("payload-$index", echo(tunnel, "payload-$index"))
                }
                assertEquals(2, proxy.sessions.get())
                assertTrue(proxy.problems.isEmpty())
            } finally {
                for (tunnel in tunnels) {
                    try {
                        tunnel.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    @Test
    fun deadSessionIsReplacedWithoutFailingTheNextTunnel() {
        EchoProxy(goawayAfterEcho = true).use { proxy ->
            val tunnel = open(proxy)
            val payload = "payload-gone".toByteArray(Charsets.US_ASCII)
            tunnel.write(payload, 0, payload.size)
            val buffer = ByteArray(payload.size)
            var done = 0
            while (done < buffer.size) {
                val size = tunnel.read(buffer, done, buffer.size - done)
                if (size < 0) break
                done += size
            }
            assertEquals("payload-gone", String(buffer, 0, done, Charsets.US_ASCII))
            // The proxy sends GOAWAY right after the echo; the next read must surface it.
            try {
                while (tunnel.read(buffer, 0, buffer.size) >= 0) {
                }
            } catch (_: Exception) {
            }
            tunnel.close()
            val replacement = open(proxy)
            try {
                assertEquals("payload-next", echo(replacement, "payload-next"))
            } finally {
                replacement.close()
            }
            assertEquals(2, proxy.sessions.get())
        }
    }

    @Test
    fun huffmanEncodedRefusalOnAPooledSessionIsRaisedWithoutFallback() {
        EchoProxy(refuseStatus = 403).use { proxy ->
            try {
                open(proxy)
                fail("expected a proxy refusal")
            } catch (error: Exception) {
                assertTrue("expected FoxProxyRefusal, got $error", error is FoxProxyRefusal)
                assertEquals(403, (error as FoxProxyRefusal).status)
            }
            assertEquals(1, proxy.sessions.get())
            assertEquals(1, proxy.streamIds.size)
        }
    }

    private fun echo(tunnel: FoxDuplex, text: String): String {
        val payload = text.toByteArray(Charsets.US_ASCII)
        tunnel.write(payload, 0, payload.size)
        val buffer = ByteArray(payload.size)
        var done = 0
        val deadline = System.currentTimeMillis() + 10000
        while (done < buffer.size && System.currentTimeMillis() < deadline) {
            val size = tunnel.read(buffer, done, buffer.size - done)
            if (size < 0) break
            done += size
        }
        return String(buffer, 0, done, Charsets.US_ASCII)
    }

    private fun open(proxy: EchoProxy): FoxDuplex {
        FoxUpstream.hops = listOf(FoxHop("http", "127.0.0.1", proxy.port))
        val location = FoxLocation("", "", "", "edge.fastly.invalid", 2499, false)
        return FoxTunnel.open(location, "target.example.invalid", 443, "local-test-only", 4000)
    }

    private class Frame(val type: Int, val flags: Int, val id: Int, val payload: ByteArray)

    /** A loopback HTTP/2 CONNECT proxy that echoes every stream and counts sessions. */
    private class EchoProxy(
        private val goawayAfterEcho: Boolean = false,
        private val refuseStatus: Int = 0
    ) : Closeable {
        val sessions = AtomicInteger()
        val streamIds = Collections.synchronizedList(mutableListOf<Int>())
        val problems = ConcurrentLinkedQueue<Throwable>()
        private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        private val context = serverContext("server.p12")
        val port: Int get() = server.localPort
        private val worker = thread(isDaemon = true, name = "echo-proxy-accept") {
            while (!server.isClosed) {
                val raw = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                thread(isDaemon = true, name = "echo-proxy-session") {
                    try {
                        raw.use { handle(it) }
                    } catch (_: SSLException) {
                    } catch (_: SocketException) {
                    } catch (_: EOFException) {
                    } catch (error: Throwable) {
                        problems.add(error)
                    }
                }
            }
        }

        private fun handle(raw: Socket) {
            raw.soTimeout = 20000
            readHead(BufferedInputStream(raw.getInputStream()))
            raw.getOutputStream().apply {
                write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val socket = context.socketFactory.createSocket(raw, "127.0.0.1", raw.port, true) as SSLSocket
            socket.use {
                socket.useClientMode = false
                socket.sslParameters = socket.sslParameters.apply { applicationProtocols = arrayOf("h2") }
                socket.startHandshake()
                if (socket.applicationProtocol != "h2") error("unexpected ALPN: ${socket.applicationProtocol}")
                sessions.incrementAndGet()
                h2(socket)
            }
        }

        private fun h2(socket: SSLSocket) {
            val input = BufferedInputStream(socket.inputStream)
            val preface = ByteArray(24)
            readFully(input, preface)
            check(String(preface, Charsets.US_ASCII) == "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")
            check(readFrame(input).type == 4)
            val output = socket.outputStream
            synchronized(output) {
                output.write(frame(4, 0, 0, ByteArray(0)))
                output.flush()
            }
            while (true) {
                val next = readFrame(input)
                when (next.type) {
                    0x01 -> {
                        streamIds.add(next.id)
                        val reply = if (refuseStatus > 0) {
                            frame(1, 0x04, next.id, byteArrayOf(0x08, 0x83.toByte(), 0x68, 0x03, 0x3F))
                        } else {
                            frame(1, 0x04, next.id, byteArrayOf(0x88.toByte()))
                        }
                        synchronized(output) {
                            output.write(reply)
                            output.flush()
                        }
                    }
                    0x00 -> {
                        val size = next.payload.size
                        synchronized(output) {
                            output.write(frame(8, 0, 0, window(size)))
                            output.write(frame(8, 0, next.id, window(size)))
                            output.write(frame(0, 0, next.id, next.payload))
                            if (goawayAfterEcho) {
                                output.write(frame(7, 0, 0, ByteArray(8)))
                            }
                            output.flush()
                        }
                        if (goawayAfterEcho) return
                    }
                    0x04 -> if (next.flags and 0x01 == 0) {
                        synchronized(output) {
                            output.write(frame(4, 0x01, 0, ByteArray(0)))
                            output.flush()
                        }
                    }
                    0x06 -> if (next.flags and 0x01 == 0) {
                        synchronized(output) {
                            output.write(frame(6, 0x01, 0, next.payload))
                            output.flush()
                        }
                    }
                    0x07 -> return
                    else -> Unit
                }
            }
        }

        private fun window(amount: Int): ByteArray = byteArrayOf(
            ((amount ushr 24) and 0x7F).toByte(),
            ((amount ushr 16) and 0xFF).toByte(),
            ((amount ushr 8) and 0xFF).toByte(),
            (amount and 0xFF).toByte()
        )

        override fun close() {
            server.close()
            worker.join(2000)
        }
    }

    companion object {
        private fun clientContext(root: String): SSLContext {
            val certificate = resource(root).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            store.load(null, null)
            store.setCertificateEntry("loopback-root", certificate)
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(store)
            return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, SecureRandom()) }
        }

        private fun serverContext(name: String): SSLContext {
            val store = KeyStore.getInstance("PKCS12")
            resource(name).use { store.load(it, "local-test-only".toCharArray()) }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            managers.init(store, "local-test-only".toCharArray())
            return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
        }

        private fun resource(name: String): InputStream =
            FoxTunnelPoolTest::class.java.getResourceAsStream("/tls/$name") ?: error("missing test resource $name")

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

        private fun readFrame(input: InputStream): Frame {
            val head = ByteArray(9)
            readFully(input, head)
            val size = ((head[0].toInt() and 255) shl 16) or ((head[1].toInt() and 255) shl 8) or (head[2].toInt() and 255)
            if (size > 65536) error("oversized frame")
            val payload = ByteArray(size)
            readFully(input, payload)
            val id = ((head[5].toInt() and 0x7f) shl 24) or ((head[6].toInt() and 255) shl 16) or
                ((head[7].toInt() and 255) shl 8) or (head[8].toInt() and 255)
            return Frame(head[3].toInt() and 255, head[4].toInt() and 255, id, payload)
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
