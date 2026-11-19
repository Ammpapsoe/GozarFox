package com.gozar

import android.app.Application
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FoxProxyHandshakeDeadlineTest {
    private fun assertClosed(socket: Socket) {
        socket.soTimeout = 4000
        try {
            assertEquals("proxy should close incomplete handshake", -1, socket.getInputStream().read())
        } catch (e: SocketTimeoutException) {
            fail("incomplete handshake remained open")
        } catch (_: SocketException) {
            // A reset also means that the listener closed this connection.
        }
    }

    @Test
    fun idleClientIsClosed() {
        val proxy = FoxProxy(0, handshakeTimeoutMillis = 400, pass = { null })
        assertTrue(proxy.start())
        try {
            Socket("127.0.0.1", proxy.boundPort()).use { assertClosed(it) }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun slowTrickleCannotExtendEntireHandshakeIndefinitely() {
        val proxy = FoxProxy(0, handshakeTimeoutMillis = 900, pass = { null })
        assertTrue(proxy.start())
        try {
            Socket("127.0.0.1", proxy.boundPort()).use { socket ->
                socket.soTimeout = 3000
                val output = socket.getOutputStream()
                output.write(byteArrayOf(5, 1, 2))
                output.flush()
                val method = ByteArray(2)
                DataInputStream(socket.getInputStream()).readFully(method)
                assertArrayEquals(byteArrayOf(5, 2), method)
                output.write(1) // auth version
                output.flush()
                Thread.sleep(500)
                output.write(24) // username length, but no username yet
                output.flush()
                Thread.sleep(500)
                assertClosed(socket)
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun completedTcpTunnelDoesNotInheritHandshakeDeadline() {
        val closed = CountDownLatch(1)
        val writes = LinkedBlockingQueue<ByteArray>()
        val tunnel = object : FoxPipe {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                closed.await(10, TimeUnit.SECONDS)
                return -1
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writes.offer(buffer.copyOfRange(offset, offset + length))
            }
            override fun close() { closed.countDown() }
        }
        val place = FoxLocation("", "", "", "local-test.invalid", 443, false)
        val proxy = FoxProxy(0, forceTunnel = true, countTraffic = false,
            nameOf = { null }, tunnelScope = { FoxLink.TransportScope(place, 1L) },
            scopeCurrent = { true }, tunnelConnect = { _, _, _, _, _ -> tunnel },
            handshakeTimeoutMillis = 1000, pass = { "test-token" })
        assertTrue(proxy.start())
        try {
            Socket("127.0.0.1", proxy.boundPort()).use { socket ->
                socket.soTimeout = 3000
                val auth = proxy.sessionCredential().toByteArray(Charsets.UTF_8)
                val host = "test.invalid".toByteArray(Charsets.US_ASCII)
                val request = ByteArrayOutputStream().apply {
                    write(byteArrayOf(5, 1, 2))
                    write(1)
                    write(auth.size)
                    write(auth)
                    write(auth.size)
                    write(auth)
                    write(byteArrayOf(5, 1, 0, 3, host.size.toByte()))
                    write(host)
                    write(byteArrayOf(1, 0xbb.toByte()))
                }.toByteArray()
                socket.getOutputStream().write(request)
                socket.getOutputStream().flush()
                val reply = ByteArray(14)
                DataInputStream(socket.getInputStream()).readFully(reply)
                assertArrayEquals(byteArrayOf(5, 2, 1, 0, 5, 0), reply.copyOfRange(0, 6))
                Thread.sleep(1500)
                val payload = byteArrayOf(1, 2, 3)
                socket.getOutputStream().write(payload)
                socket.getOutputStream().flush()
                assertArrayEquals(payload, writes.poll(3, TimeUnit.SECONDS))
            }
        } finally {
            proxy.stop()
            closed.countDown()
        }
    }

    @Test
    fun completedUdpAssociationDoesNotInheritHandshakeDeadline() {
        val proxy = FoxProxy(0, handshakeTimeoutMillis = 1000, pass = { null })
        assertTrue(proxy.start())
        try {
            Socket("127.0.0.1", proxy.boundPort()).use { socket ->
                socket.soTimeout = 3000
                val auth = proxy.sessionCredential().toByteArray(Charsets.UTF_8)
                val request = ByteArrayOutputStream().apply {
                    write(byteArrayOf(5, 1, 2))
                    write(1)
                    write(auth.size)
                    write(auth)
                    write(auth.size)
                    write(auth)
                    write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                }.toByteArray()
                socket.getOutputStream().write(request)
                socket.getOutputStream().flush()
                val reply = ByteArray(14)
                DataInputStream(socket.getInputStream()).readFully(reply)
                assertArrayEquals(byteArrayOf(5, 2, 1, 0, 5, 0), reply.copyOfRange(0, 6))
                Thread.sleep(1500)
                socket.soTimeout = 250
                try {
                    val value = socket.getInputStream().read()
                    fail("association closed or sent unexpected data: $value")
                } catch (_: SocketTimeoutException) {
                    // Expected: a live idle association sends no TCP data.
                }
            }
        } finally {
            proxy.stop()
        }
    }
}
