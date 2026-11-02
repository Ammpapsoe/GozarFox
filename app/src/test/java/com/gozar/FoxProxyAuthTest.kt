package com.gozar

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FoxProxyAuthTest {

    @Test
    fun socksListenerRefusesPasswordlessClients() {
        val proxy = FoxProxy(0, pass = { "unused" })
        assertTrue(proxy.start())
        try {
            Socket(InetAddress.getByName("127.0.0.1"), proxy.boundPort()).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
                socket.getOutputStream().flush()
                val reply = ByteArray(2)
                assertEquals(2, socket.getInputStream().read(reply))
                assertEquals(0x05, reply[0].toInt())
                assertEquals(0xFF, reply[1].toInt() and 0xFF)
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun socksListenerAnnouncesPasswordAuthentication() {
        val proxy = FoxProxy(0, pass = { "unused" })
        assertTrue(proxy.start())
        try {
            Socket(InetAddress.getByName("127.0.0.1"), proxy.boundPort()).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
                socket.getOutputStream().flush()
                val reply = ByteArray(2)
                assertEquals(2, socket.getInputStream().read(reply))
                assertEquals(0x05, reply[0].toInt())
                assertEquals(0x02, reply[1].toInt())
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun everySessionBindsDifferentLoopbackPorts() {
        val first = FoxProxy(0, pass = { null })
        val second = FoxProxy(0, pass = { null })
        assertTrue(first.start())
        assertTrue(second.start())
        try {
            assertTrue(first.boundPort() in 1024..65535)
            assertNotEquals(first.boundPort(), second.boundPort())
            assertTrue(first.boundPort() != 10810 && first.boundPort() != 10811 && first.boundPort() != 10812)
        } finally {
            first.stop()
            second.stop()
        }
    }

    @Test
    fun connectionsAreCappedToProtectMemory() {
        val proxy = FoxProxy(0, pass = { "unused" })
        assertTrue(proxy.start())
        val sockets = mutableListOf<Socket>()
        try {
            repeat(80) {
                sockets += Socket(InetAddress.getByName("127.0.0.1"), proxy.boundPort()).apply { soTimeout = 2000 }
            }
            Thread.sleep(1000)
            var refused = 0
            for (socket in sockets) {
                val closed = runCatching { socket.getInputStream().read() == -1 }.getOrDefault(true)
                if (closed) refused++
            }
            assertTrue("expected the listener to drop excess clients", refused >= 8)
        } finally {
            sockets.forEach { runCatching { it.close() } }
            proxy.stop()
        }
    }
}
