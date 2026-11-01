package com.gozar

import android.app.Application
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Context
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ProtectionTest {
    @After
    fun clean() {
        RuntimeEnvironment.getApplication().getSharedPreferences("store", Context.MODE_PRIVATE).edit().clear().commit()
        FoxRoute.apply(false, "")
    }

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun securityPreferencesPersistWithoutChangingTheChosenResolver() {
        val context = RuntimeEnvironment.getApplication()
        Store.setCustomDns(context, "https://dns.example.test/dns-query")
        Store.setDnsProfile(context, DnsPolicy.CUSTOM)
        Store.setBlockAds(context, true)
        assertEquals(DnsPolicy.CUSTOM, Store.dnsProfile(context))
        assertEquals(listOf(DnsPolicy.ADGUARD), DnsPolicy.endpoints(Store.dnsProfile(context), Store.customDns(context), Store.blockAds(context)))
        Store.setBlockAds(context, false)
        assertEquals(listOf("https://dns.example.test/dns-query"), DnsPolicy.endpoints(Store.dnsProfile(context), Store.customDns(context), Store.blockAds(context)))
    }

    @Test
    fun forcedDnsProxyDoesNotHonorDirectHostBypass() {
        assertTrue(FoxRoute.direct("localhost"))
        val port = availablePort()
        val proxy = FoxProxy(port, forceTunnel = true) { null }
        assertTrue(proxy.start())
        try {
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write("CONNECT localhost:8443 HTTP/1.1\r\nHost: localhost:8443\r\n\r\n".toByteArray())
                val status = client.getInputStream().bufferedReader().readLine()
                assertEquals("HTTP/1.1 502 Bad Gateway", status)
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun socksGreetingAndDirectRelayComplete() {
        ServerSocket(0).use { target ->
            target.soTimeout = 4000
            val served = CountDownLatch(1)
            val worker = thread(isDaemon = true) {
                try {
                    target.accept().use { socket ->
                        socket.soTimeout = 3000
                        val request = socket.getInputStream().readBytes()
                        if (String(request) == "ping") {
                            socket.getOutputStream().write("pong".toByteArray())
                        }
                    }
                } finally {
                    served.countDown()
                }
            }
            val port = availablePort()
            FoxRoute.apply(false, "direct.example")
            val resolved = LinkedBlockingQueue<String>()
            val proxy = FoxProxy(port, directAllowed = { true }, directAddress = { host ->
                resolved.offer(host)
                "127.0.0.1"
            }) { null }
            assertTrue(proxy.start())
            try {
                Socket("127.0.0.1", port).use { client ->
                    client.soTimeout = 3000
                    val input = client.getInputStream()
                    val output = client.getOutputStream()
                    output.write(byteArrayOf(5, 1, 0))
                    assertTrue(input.readNBytes(2).contentEquals(byteArrayOf(5, 0)))
                    val targetPort = target.localPort
                    output.write(byteArrayOf(5, 1, 0, 3, 14) + "direct.example".toByteArray() +
                        byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte()))
                    val response = input.readNBytes(10)
                    assertEquals(0, response[1].toInt())
                    output.write("ping".toByteArray())
                    client.shutdownOutput()
                    assertEquals("pong", String(input.readNBytes(4)))
                }
                assertTrue(served.await(4, TimeUnit.SECONDS))
                assertEquals("direct.example", resolved.poll(1, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
                worker.join(4000)
            }
        }
    }

    @Test
    fun socksRejectsUnsupportedAuthenticationMethods() {
        val port = availablePort()
        val proxy = FoxProxy(port) { null }
        assertTrue(proxy.start())
        try {
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write(byteArrayOf(5, 1, 2))
                assertTrue(client.getInputStream().readNBytes(2).contentEquals(byteArrayOf(5, 0xFF.toByte())))
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun stoppingProxyClosesAnInFlightClient() {
        val port = availablePort()
        val proxy = FoxProxy(port) { null }
        assertTrue(proxy.start())
        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 3000
            client.getOutputStream().write("CONNECT ".toByteArray())
            Thread.sleep(100)
            proxy.stop()
            val closed = try { client.getInputStream().read() < 0 } catch (_: java.net.SocketException) { true }
            assertTrue(closed)
        }
    }

    @Test
    fun simultaneousTrafficUpdatesDoNotLoseCounts() {
        val beforeDown = FoxLink.down
        val beforeUp = FoxLink.up
        val done = CountDownLatch(16)
        repeat(16) { index ->
            thread(isDaemon = true) {
                repeat(2000) { FoxLink.count(index % 2 == 0, 1) }
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(16000L, FoxLink.down - beforeDown)
        assertEquals(16000L, FoxLink.up - beforeUp)
    }

    @Test
    fun focusExtrasAreOptionalAndUseTheDocumentedIslandEnvelope() {
        val context = RuntimeEnvironment.getApplication()
        val notification = Notification.Builder(context, "vpn").setSmallIcon(R.drawable.ic_fox_note)
            .setContentTitle("Gozar").build()
        assertEquals(0, FocusIsland.permittedProtocol(context))
        FocusIsland.decorate(context, notification, 0, "Connected", "↓ 1 KB/s")
        assertNull(notification.extras.getString("miui.focus.param"))
        FocusIsland.decorate(context, notification, 3, "Connected", "↓ 1 KB/s")
        val params = JSONObject(notification.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
        assertEquals("vpn", params.getString("business"))
        assertTrue(params.getBoolean("updatable"))
        assertNotNull(params.getJSONObject("param_island").getJSONObject("bigIslandArea"))
        assertNotNull(params.getJSONObject("param_island").getJSONObject("smallIslandArea"))
        assertNotNull(notification.extras.getBundle("miui.focus.pics"))
    }

    @Test
    fun trafficChartAcceptsEmptyAndChangingSamples() {
        val context = RuntimeEnvironment.getApplication()
        val chart = TrafficChartView(context)
        chart.layout(0, 0, 320, 100)
        val canvas = Canvas(Bitmap.createBitmap(320, 100, Bitmap.Config.ARGB_8888))
        chart.setSamples(emptyList())
        chart.draw(canvas)
        chart.setSamples((0 until 80).map { it * 2048f to it * 1024f })
        chart.draw(canvas)
    }

    @Test
    fun ongoingNotificationFallsBackNormallyAndRespectsAlwaysOn() {
        val controller = Robolectric.buildService(FoxVpnService::class.java).create()
        try {
            val service = controller.get()
            val method = FoxVpnService::class.java.getDeclaredMethod("makeNotification")
            method.isAccessible = true
            val ordinary = method.invoke(service) as Notification
            assertTrue(ordinary.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertNotNull(ordinary.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertNull(ordinary.extras.getString("miui.focus.param"))
            assertEquals(1, ordinary.actions.size)
            val field = FoxVpnService::class.java.getDeclaredField("systemStarted")
            field.isAccessible = true
            field.setBoolean(service, true)
            val managed = method.invoke(service) as Notification
            assertTrue(managed.actions.isNullOrEmpty())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun legacyManuallyStartedVpnAlsoGuardsDirectRoutesWhenLockdownIsUnknown() {
        val controller = Robolectric.buildService(FoxVpnService::class.java).create()
        try {
            assertFalse(FoxVpnService.alwaysOn())
            assertEquals(0, FoxVpnService.lockdownState())
            assertTrue(FoxVpnService.lockdownActive())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun legacySystemStartedVpnUsesConservativeBypassProtection() {
        val controller = Robolectric.buildService(FoxVpnService::class.java).create()
        try {
            val field = FoxVpnService::class.java.getDeclaredField("systemStarted")
            field.isAccessible = true
            field.setBoolean(controller.get(), true)
            assertTrue(FoxVpnService.alwaysOn())
            assertTrue(FoxVpnService.lockdownActive())
            assertEquals(0, FoxVpnService.lockdownState())
            val port = availablePort()
            val proxy = FoxProxy(port) { null }
            assertTrue(proxy.start())
            try {
                Socket("127.0.0.1", port).use { client ->
                    client.soTimeout = 3000
                    client.getOutputStream().write("CONNECT localhost:8443 HTTP/1.1\r\nHost: localhost:8443\r\n\r\n".toByteArray())
                    assertEquals("HTTP/1.1 502 Bad Gateway", client.getInputStream().bufferedReader().readLine())
                }
            } finally {
                proxy.stop()
            }
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Config(sdk = [29])
    fun lockdownIsUnknownUntilAnInterfaceIsEstablished() {
        val controller = Robolectric.buildService(FoxVpnService::class.java).create()
        try {
            assertEquals(0, FoxVpnService.lockdownState())
            assertTrue(FoxVpnService.lockdownActive())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun lockdownStateIsUnknownWithoutAnActiveVpnService() {
        assertEquals(0, FoxVpnService.lockdownState())
    }
}
