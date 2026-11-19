package com.gozar

import android.app.Application
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Local simulated load: does not exercise a real device, WARP or a WAN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FoxUdpSlotLoadTest {
    private fun associate(proxy: FoxProxy): Pair<Socket, Int> {
        val socket = Socket("127.0.0.1", proxy.boundPort())
        try {
            socket.soTimeout = 3000
            val auth = proxy.sessionCredential().toByteArray(Charsets.UTF_8)
            val request = ByteArrayOutputStream().apply {
                write(byteArrayOf(5, 1, 2))
                write(1)
                write(auth.size); write(auth); write(auth.size); write(auth)
                write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
            }.toByteArray()
            socket.getOutputStream().write(request)
            socket.getOutputStream().flush()
            val reply = ByteArray(14)
            DataInputStream(socket.getInputStream()).readFully(reply)
            assertEquals(0, reply[5].toInt())
            return socket to (((reply[12].toInt() and 255) shl 8) or (reply[13].toInt() and 255))
        } catch (e: Throwable) {
            socket.close()
            throw e
        }
    }

    @Test
    fun sixtyFourUdpControlConnectionsFillClientSlotsAndClosingOneFreesIt() {
        val proxy = FoxProxy(0, countTraffic = false, tunnelScope = { null }, pass = { null })
        assertTrue(proxy.start())
        val controls = mutableListOf<Socket>()
        val udp = DatagramSocket()
        try {
            repeat(64) {
                val (control, relayPort) = associate(proxy)
                controls.add(control)
                val packet = byteArrayOf(0, 0, 0, 1, 203.toByte(), 0, 113, 1, 1, 0xbb.toByte(), 42)
                udp.send(DatagramPacket(packet, packet.size, InetAddress.getByName("127.0.0.1"), relayPort))
            }
            // No authentication request is processed while all slots are in
            // use: the TCP socket is accepted then immediately closed.
            Socket("127.0.0.1", proxy.boundPort()).use { excess ->
                excess.soTimeout = 3000
                try {
                    assertEquals("65th client should be refused", -1, excess.getInputStream().read())
                } catch (e: SocketTimeoutException) {
                    fail("65th client was not promptly refused")
                } catch (_: SocketException) {
                    // A reset is also an immediate refusal.
                }
            }
            controls.removeAt(0).close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var replacement: Socket? = null
            while (replacement == null && System.nanoTime() < deadline) {
                try {
                    replacement = associate(proxy).first
                } catch (_: Exception) {
                    Thread.sleep(25)
                }
            }
            assertTrue("slot was not released after control EOF", replacement != null)
            replacement?.let { controls.add(it) }
        } finally {
            controls.forEach { runCatching { it.close() } }
            udp.close()
            proxy.stop()
        }
    }
}
