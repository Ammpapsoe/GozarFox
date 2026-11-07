package com.gozar

import java.io.IOException
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class FoxRelaySocketStressTest {
    private class RefusingSocket(private val failAt: String) : Socket() {
        val wasClosed = AtomicBoolean(false)
        override fun setKeepAlive(on: Boolean) {
            if (failAt == "setup") throw IOException("synthetic setup failure")
        }
        override fun setTcpNoDelay(on: Boolean) = Unit
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            if (failAt == "connect") throw IOException("synthetic refused connection")
        }
        override fun setSoTimeout(timeout: Int) {
            if (failAt == "timeout") throw IOException("synthetic timeout setup failure")
        }
        override fun close() { wasClosed.set(true) }
    }

    @Test
    fun failedRelayDialAlwaysClosesRawSocketAcrossFiftyTransitions() {
        val previous = FoxUpstream.hops
        try {
            repeat(50) { iteration ->
                for (hops in listOf(emptyList(), listOf(FoxHop("http", "127.0.0.1", 8080)))) {
                    FoxUpstream.hops = hops
                    for (stage in listOf("setup", "connect", "timeout")) {
                        val socket = RefusingSocket(stage)
                        val failure = runCatching { FoxTunnel.chain("127.0.0.1", 443, 1000, socket) }.exceptionOrNull()
                        assertTrue("unexpected exception at $iteration/$stage", failure is IOException)
                        assertTrue("failed relay socket remained open at $iteration/$stage", socket.wasClosed.get())
                    }
                }
            }
        } finally {
            FoxUpstream.hops = previous
        }
    }

    private class PendingProxy(private val tls: Boolean) : Closeable {
        data class Gate(val entered: CountDownLatch, val closed: CountDownLatch)
        val gates = LinkedBlockingQueue<Gate>()
        private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                thread(isDaemon = true) {
                    val gate = gates.poll(5, TimeUnit.SECONDS) ?: return@thread
                    try {
                        socket.use { client ->
                            client.soTimeout = 4000
                            val input = client.getInputStream()
                            var tail = ""
                            while (!tail.endsWith("\r\n\r\n")) {
                                val c = input.read()
                                if (c < 0) return@use
                                tail = (tail + c.toChar()).takeLast(4)
                            }
                            if (tls) {
                                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                                client.getOutputStream().flush()
                                if (input.read() < 0) return@use
                            }
                            gate.entered.countDown()
                            while (input.read() >= 0) { }
                            gate.closed.countDown()
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: SocketException) {
                        gate.closed.countDown()
                    }
                }
            }
        }
        override fun close() { server.close(); acceptor.join(5000) }
    }

    private fun resetInterruptsPendingRelay(tls: Boolean) {
        val previous = FoxUpstream.hops
        FoxTunnel.startTransport()
        PendingProxy(tls).use { proxy ->
            FoxUpstream.hops = listOf(FoxHop("http", "127.0.0.1", proxy.port))
            val location = FoxLocation("", "", "", "edge.fastly.invalid", 2499, false)
            try {
                repeat(50) { iteration ->
                    FoxTunnel.resetTransport()
                    val gate = PendingProxy.Gate(CountDownLatch(1), CountDownLatch(1))
                    proxy.gates.put(gate)
                    val opener = thread(isDaemon = true) {
                        try { FoxTunnel.open(location, "target.invalid", 443, "synthetic", 6000).close() } catch (_: Exception) { }
                    }
                    try {
                        assertTrue("relay did not enter at $iteration", gate.entered.await(5, TimeUnit.SECONDS))
                        FoxTunnel.resetTransport()
                        assertTrue("reset did not close pending relay at $iteration", gate.closed.await(2, TimeUnit.SECONDS))
                        opener.join(5000)
                        assertFalse("old opener did not exit at $iteration", opener.isAlive)
                        assertTrue(FoxTunnel.pooledSessionCount() == 0)
                        assertTrue("pending relay leaked at $iteration", FoxTunnel.pendingRelayCount() == 0)
                    } finally {
                        opener.join(7000)
                    }
                }
            } finally {
                FoxUpstream.hops = previous
                FoxTunnel.resetTransport()
            }
        }
    }

    @Test
    fun resetClosesPendingProxyChainAcrossFiftyRaces() = resetInterruptsPendingRelay(false)

    @Test
    fun resetClosesPendingTlsHandshakeAcrossFiftyRaces() = resetInterruptsPendingRelay(true)

}
