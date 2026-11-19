package com.gozar

import android.app.Application
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual local SOCKS parser, not the native packet engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FoxProxyPipelineTest {
    private fun request(credential: String): ByteArray {
        val auth = credential.toByteArray(Charsets.UTF_8)
        val host = "pipeline-test.invalid".toByteArray(Charsets.US_ASCII)
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(5, 1, 2))
            write(1)
            write(auth.size)
            write(auth)
            write(auth.size)
            write(auth)
            write(byteArrayOf(5, 1, 0, 3, host.size.toByte()))
            write(host)
            write(byteArrayOf(1, 0xbb.toByte())) // port 443
        }.toByteArray()
    }

    @Test
    fun pipelinedAuthenticatedConnectIsParsedInOrder() {
        val calls = AtomicInteger()
        val target = AtomicReference<String>()
        val place = FoxLocation("", "", "", "local-test.invalid", 443, false)
        val proxy = FoxProxy(0, forceTunnel = true, countTraffic = false,
            nameOf = { null },
            tunnelScope = { FoxLink.TransportScope(place, 1L) }, scopeCurrent = { true },
            tunnelConnect = { _, host, port, _, _ ->
                target.set("$host:$port")
                calls.incrementAndGet()
                null // Deliberate failed dial; parsing and authentication are the subject.
            }, pass = { "test-token" })
        assertTrue(proxy.start())
        try {
            Socket("127.0.0.1", proxy.boundPort()).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(request(proxy.sessionCredential()))
                socket.getOutputStream().flush()
                val responses = ByteArray(14)
                DataInputStream(socket.getInputStream()).readFully(responses)
                assertArrayEquals(byteArrayOf(5, 2, 1, 0, 5, 5, 0, 1, 0, 0, 0, 0, 0, 0), responses)
                assertEquals(1, calls.get())
                assertEquals("pipeline-test.invalid:443", target.get())
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun pipelinedRequestWithBadCredentialsNeverDials() {
        val calls = AtomicInteger()
        val place = FoxLocation("", "", "", "local-test.invalid", 443, false)
        val proxy = FoxProxy(0, forceTunnel = true,
            tunnelScope = { FoxLink.TransportScope(place, 1L) }, scopeCurrent = { true },
            tunnelConnect = { _, _, _, _, _ -> calls.incrementAndGet(); null }, pass = { "test-token" })
        assertTrue(proxy.start())
        try {
            Socket("127.0.0.1", proxy.boundPort()).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(request("wrong-credential"))
                socket.getOutputStream().flush()
                val responses = ByteArray(4)
                DataInputStream(socket.getInputStream()).readFully(responses)
                assertArrayEquals(byteArrayOf(5, 2, 1, 1), responses)
                assertEquals(0, calls.get())
            }
        } finally {
            proxy.stop()
        }
    }
}
