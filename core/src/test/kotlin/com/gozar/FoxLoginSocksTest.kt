package com.gozar

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FoxLoginSocksTest {
    @Test
    fun loginAllowlistIsExactAndRejectsArbitraryDestinations() {
        assertTrue(FoxNet.loginAllowed("https://oauth.accounts.firefox.com/v1/account/login", "POST"))
        assertTrue(FoxNet.loginAllowed("https://oauth.accounts.firefox.com/v1/recovery_email/status", "GET"))
        assertTrue(FoxNet.loginAllowed("https://vpn.mozilla.org/api/v1/fpn/token", "GET"))
        assertTrue(FoxNet.loginAllowed(FoxNet.SERVERS, "GET"))
        assertTrue(!FoxNet.loginAllowed("https://oauth.accounts.firefox.com/v1/account/login", "GET"))
        assertTrue(!FoxNet.loginAllowed("https://oauth.accounts.firefox.com/v1/account/login?next=other", "POST"))
        assertTrue(!FoxNet.loginAllowed("https://oauth.accounts.firefox.com.evil.invalid/v1/account/login", "POST"))
        assertTrue(!FoxNet.loginAllowed("http://vpn.mozilla.org/api/v1/fpn/token", "GET"))
        assertTrue(!FoxNet.loginAllowed("https://example.invalid/", "GET"))
        assertTrue(!FoxNet.loginAllowed("https://raw.githubusercontent.com/bardia049b-stack/Gozar/main/packaging/relay-endpoints.json", "GET"))
    }

    @Test
    fun socksScopeIsConfinedToItsThreadAndAlwaysRemoved() {
        assertNull(FoxNet.loginSocksPort())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val otherThreadValue = AtomicReference<Int?>(-1)
        val worker = thread {
            entered.await(5, TimeUnit.SECONDS)
            otherThreadValue.set(FoxNet.loginSocksPort())
            release.countDown()
        }
        FoxNet.throughLoginSocks(19051) {
            assertEquals(19051, FoxNet.loginSocksPort())
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            try {
                FoxNet.throughLoginSocks(19052) { }
                fail("nested SOCKS scope accepted")
            } catch (_: IllegalArgumentException) {
            }
            try {
                FoxNet.throughControlTunnel(19053) { }
                fail("mixed proxy scopes accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        worker.join(5000)
        assertNull(otherThreadValue.get())
        assertNull(FoxNet.loginSocksPort())
        try {
            FoxNet.throughLoginSocks(19051) { throw IllegalStateException("synthetic") }
        } catch (_: IllegalStateException) {
        }
        assertNull(FoxNet.loginSocksPort())
    }

    @Test
    fun httpsSocksRequestSendsUnresolvedMozillaHostname() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 10_000
            val hostname = AtomicReference<String>()
            val addressType = AtomicReference<Int>()
            val failure = AtomicReference<Throwable>()
            val accepted = CountDownLatch(1)
            val worker = thread {
                try {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        assertEquals(5, input.readUnsignedByte())
                        val methods = input.readUnsignedByte()
                        input.skipBytes(methods)
                        output.write(byteArrayOf(5, 0))
                        output.flush()
                        assertEquals(5, input.readUnsignedByte())
                        assertEquals(1, input.readUnsignedByte())
                        input.readUnsignedByte()
                        val type = input.readUnsignedByte()
                        addressType.set(type)
                        if (type == 3) {
                            val length = input.readUnsignedByte()
                            val bytes = ByteArray(length)
                            input.readFully(bytes)
                            hostname.set(String(bytes, Charsets.US_ASCII))
                        }
                        input.readUnsignedShort()
                        output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                        output.flush()
                        accepted.countDown()
                        input.read()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                    accepted.countDown()
                }
            }
            try {
                FoxNet.throughLoginSocks(server.localPort) {
                    FoxNet.call(FoxNet.SERVERS, "GET", timeout = 5000)
                }
            } catch (_: Exception) {
            }
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            worker.join(5000)
            failure.get()?.let { throw it }
            assertEquals(3, addressType.get())
            assertEquals("firefox.settings.services.mozilla.com", hostname.get())
        }
    }
}
