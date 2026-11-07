package com.gozar

import android.app.Application
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Context
import android.os.ParcelFileDescriptor
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

    private fun login(input: java.io.InputStream, output: java.io.OutputStream, credential: String) {
        val raw = credential.toByteArray()
        output.write(byteArrayOf(1, raw.size.toByte()) + raw + byteArrayOf(raw.size.toByte()) + raw)
        assertTrue(input.readNBytes(2).contentEquals(byteArrayOf(1, 0)))
    }
    @After
    fun clean() {
        RuntimeEnvironment.getApplication().getSharedPreferences("store", Context.MODE_PRIVATE).edit().clear().commit()
        FoxRoute.apply(false, "")
    }

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun linkStopDisablesItsTransportAcrossFiftyTransitions() {
        try {
            repeat(50) { iteration ->
                val ticket = FoxTunnel.startTransport()
                assertTrue(FoxTunnel.transportIsCurrent(ticket))
                FoxLink.stop()
                assertNull("transport remained enabled at iteration $iteration", FoxTunnel.currentTransport())
                assertFalse(FoxTunnel.transportIsCurrent(ticket))
            }
        } finally {
            FoxTunnel.startTransport()
        }
    }

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
            val proxy = FoxProxy(port, directAllowed = { true }, directAddress = { host, _ ->
                resolved.offer(host)
                "127.0.0.1"
            }, tunnelScope = { FoxLink.TransportScope(FoxLocation("", "", "", "local-test.invalid", 443, false), 1L) },
                scopeCurrent = { true }) { null }
            assertTrue(proxy.start())
            try {
                Socket("127.0.0.1", port).use { client ->
                    client.soTimeout = 3000
                    val input = client.getInputStream()
                    val output = client.getOutputStream()
                    output.write(byteArrayOf(5, 1, 2))
                    assertTrue(input.readNBytes(2).contentEquals(byteArrayOf(5, 2)))
                    login(input, output, proxy.sessionCredential())
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
    fun delayedDirectLookupCannotUseNewAttemptAcrossFiftyRaces() {
        data class Gate(val entered: CountDownLatch, val release: CountDownLatch)
        val epoch = AtomicLong(1)
        val gates = LinkedBlockingQueue<Gate>()
        val accepted = AtomicInteger()
        val target = ServerSocket(0)
        val server = thread(isDaemon = true) {
            try {
                while (!target.isClosed) target.accept().use { accepted.incrementAndGet() }
            } catch (_: java.net.SocketException) { }
        }
        FoxRoute.apply(false, "direct.example")
        val place = FoxLocation("", "", "", "local-test.invalid", 443, false)
        val proxy = FoxProxy(0, directAllowed = { true }, directAddress = { _, _ ->
            val gate = gates.poll(5, TimeUnit.SECONDS) ?: error("missing gate")
            gate.entered.countDown()
            if (!gate.release.await(5, TimeUnit.SECONDS)) error("lookup timed out")
            "127.0.0.1"
        }, tunnelScope = { FoxLink.TransportScope(place, epoch.get()) }, scopeCurrent = { it == epoch.get() }) { null }
        assertTrue(proxy.start())
        try {
            repeat(50) { iteration ->
                val gate = Gate(CountDownLatch(1), CountDownLatch(1))
                gates.put(gate)
                Socket("127.0.0.1", proxy.boundPort()).use { client ->
                    client.soTimeout = 5000
                    client.getOutputStream().write("CONNECT direct.example:${target.localPort} HTTP/1.1\r\nHost: direct.example\r\n\r\n".toByteArray())
                    assertTrue("lookup did not enter at iteration $iteration", gate.entered.await(5, TimeUnit.SECONDS))
                    epoch.incrementAndGet()
                    gate.release.countDown()
                    assertEquals("HTTP/1.1 502 Bad Gateway", client.getInputStream().bufferedReader().readLine())
                }
            }
            assertEquals("stale direct lookup reached the network", 0, accepted.get())
        } finally {
            proxy.stop()
            target.close()
            server.join(5000)
        }
    }

    @Test
    fun pausedProxyKeepsItsPortAndRejectsStaleHandlersAcrossFiftyRaces() {
        data class Gate(val entered: CountDownLatch, val release: CountDownLatch, val worker: AtomicReference<Thread?>)
        val gates = LinkedBlockingQueue<Gate>()
        val connectCalls = AtomicInteger()
        FoxRoute.apply(false, "direct.example")
        val place = FoxLocation("", "", "", "local-test.invalid", 443, false)
        val proxy = FoxProxy(0, directAllowed = { true }, directAddress = { _, _ ->
            val gate = gates.poll(5, TimeUnit.SECONDS) ?: error("missing gate")
            gate.worker.set(Thread.currentThread())
            gate.entered.countDown()
            if (!gate.release.await(5, TimeUnit.SECONDS)) error("lookup timed out")
            "127.0.0.1"
        }, directConnect = { _, _ -> connectCalls.incrementAndGet(); null },
            tunnelScope = { FoxLink.TransportScope(place, 1L) }, scopeCurrent = { true }) { null }
        assertTrue(proxy.start())
        val port = proxy.boundPort()
        try {
            repeat(50) { iteration ->
                val gate = Gate(CountDownLatch(1), CountDownLatch(1), AtomicReference(null))
                gates.put(gate)
                Socket("127.0.0.1", port).use { client ->
                    client.soTimeout = 5000
                    client.getOutputStream().write("CONNECT direct.example:443 HTTP/1.1\r\nHost: direct.example\r\n\r\n".toByteArray())
                    assertTrue("lookup did not enter at iteration $iteration", gate.entered.await(5, TimeUnit.SECONDS))
                    proxy.pause()
                    assertEquals(port, proxy.boundPort())
                    val denied = try {
                        Socket("127.0.0.1", port).use { pausedClient ->
                            pausedClient.soTimeout = 3000
                            pausedClient.getOutputStream().write("CONNECT direct.example:443 HTTP/1.1\r\n\r\n".toByteArray())
                            pausedClient.getInputStream().read() < 0
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        false
                    } catch (_: java.net.SocketException) {
                        true
                    }
                    assertTrue("paused listener accepted work at iteration $iteration", denied)
                    proxy.resume()
                    gate.release.countDown()
                    val worker = gate.worker.get() ?: error("missing worker")
                    worker.join(5000)
                    assertFalse("old handler did not exit at iteration $iteration", worker.isAlive)
                    assertEquals("old handler attempted a direct connection", 0, connectCalls.get())
                }
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun pausedProxyRejectsDelayedCredentialsAcrossFiftyRaces() {
        data class Gate(val entered: CountDownLatch, val release: CountDownLatch, val worker: AtomicReference<Thread?>)
        val gates = LinkedBlockingQueue<Gate>()
        val connectCalls = AtomicInteger()
        val place = FoxLocation("", "", "", "local-test.invalid", 443, false)
        val proxy = FoxProxy(0, forceTunnel = true,
            tunnelConnect = { _, _, _, _, _ -> connectCalls.incrementAndGet(); null },
            tunnelScope = { FoxLink.TransportScope(place, 1L) }, scopeCurrent = { true }) {
            val gate = gates.poll(5, TimeUnit.SECONDS) ?: error("missing gate")
            gate.worker.set(Thread.currentThread())
            gate.entered.countDown()
            if (!gate.release.await(5, TimeUnit.SECONDS)) error("credential lookup timed out")
            "synthetic-token"
        }
        assertTrue(proxy.start())
        try {
            repeat(50) { iteration ->
                val gate = Gate(CountDownLatch(1), CountDownLatch(1), AtomicReference(null))
                gates.put(gate)
                Socket("127.0.0.1", proxy.boundPort()).use { client ->
                    client.soTimeout = 5000
                    client.getOutputStream().write("CONNECT example.invalid:443 HTTP/1.1\r\nHost: example.invalid\r\n\r\n".toByteArray())
                    assertTrue("credentials did not enter at $iteration", gate.entered.await(5, TimeUnit.SECONDS))
                    proxy.pause()
                    proxy.resume()
                    gate.release.countDown()
                    val worker = gate.worker.get() ?: error("missing worker")
                    worker.join(5000)
                    assertFalse("old handler did not exit at $iteration", worker.isAlive)
                    assertEquals("old handler attempted a tunnel connection", 0, connectCalls.get())
                }
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun inPlaceStopPreservesAdvertisedPortsWhileInterfaceExists() {
        FoxLink.stop()
        val controller = Robolectric.buildService(FoxVpnService::class.java).create()
        val service = controller.get()
        val descriptors = ParcelFileDescriptor.createPipe()
        val field = FoxVpnService::class.java.getDeclaredField("descriptor").apply { isAccessible = true }
        field.set(service, descriptors[0])
        try {
            assertTrue(FoxVpnService.hasInterface())
            assertTrue(FoxLink.prepare(RuntimeEnvironment.getApplication()))
            val expected = listOf(FoxLink.socksPort, FoxLink.webPort, FoxLink.dnsPort)
            assertTrue(expected.all { it > 0 })
            repeat(50) { iteration ->
                FoxLink.stop()
                assertEquals("proxy ports changed at transition $iteration", expected,
                    listOf(FoxLink.socksPort, FoxLink.webPort, FoxLink.dnsPort))
                assertTrue(FoxLink.prepare(RuntimeEnvironment.getApplication()))
                assertEquals(expected, listOf(FoxLink.socksPort, FoxLink.webPort, FoxLink.dnsPort))
            }
        } finally {
            controller.destroy()
            descriptors[1].close()
            FoxLink.stop()
            FoxTunnel.startTransport()
        }
        assertEquals(0, FoxLink.dnsPort)
    }

    @Test
    fun oldServiceDestroyCannotResetReplacementAcrossFiftyTransitions() {
        val context = RuntimeEnvironment.getApplication()
        val descriptorField = FoxVpnService::class.java.getDeclaredField("descriptor").apply { isAccessible = true }
        val failureField = FoxVpnService::class.java.getDeclaredField("startFailure").apply { isAccessible = true }
        try {
            repeat(50) { iteration ->
                FoxLink.stop()
                val oldController = Robolectric.buildService(FoxVpnService::class.java).create()
                val oldDescriptors = ParcelFileDescriptor.createPipe()
                descriptorField.set(oldController.get(), oldDescriptors[0])
                failureField.set(oldController.get(), "stale service failure")
                assertTrue(FoxLink.prepare(context))
                val ports = listOf(FoxLink.socksPort, FoxLink.webPort, FoxLink.dnsPort)
                val newController = Robolectric.buildService(FoxVpnService::class.java).create()
                val newDescriptors = ParcelFileDescriptor.createPipe()
                descriptorField.set(newController.get(), newDescriptors[0])
                val ticket = FoxTunnel.startTransport()
                FoxLink.error = "replacement state"
                val closed = AtomicBoolean(false)
                val direct = DirectPipe(object : FoxPipe {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
                    override fun close() { closed.set(true) }
                }) { true }
                var oldDestroyed = false
                var newDestroyed = false
                try {
                    oldController.destroy()
                    oldDestroyed = true
                    assertTrue("replacement lost liveness at iteration $iteration", FoxVpnService.isAlive())
                    assertTrue("replacement lost interface at iteration $iteration", FoxVpnService.hasInterface())
                    assertTrue("replacement transport reset at iteration $iteration", FoxTunnel.transportIsCurrent(ticket))
                    assertEquals("stale failure replaced current state", "replacement state", FoxLink.error)
                    assertEquals("replacement ports changed at iteration $iteration", ports,
                        listOf(FoxLink.socksPort, FoxLink.webPort, FoxLink.dnsPort))
                    assertFalse("replacement direct pipe closed at iteration $iteration", closed.get())
                    newController.destroy()
                    newDestroyed = true
                    assertTrue("current service did not close direct pipe", closed.get())
                    assertNull("current service teardown did not stop transport", FoxTunnel.currentTransport())
                    assertEquals(0, FoxLink.dnsPort)
                } finally {
                    if (!oldDestroyed) oldController.destroy()
                    if (!newDestroyed) newController.destroy()
                    direct.close()
                    oldDescriptors[1].close()
                    newDescriptors[1].close()
                }
            }
        } finally {
            FoxLink.stop()
            FoxTunnel.startTransport()
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
                client.getOutputStream().write(byteArrayOf(5, 1, 0))
                assertTrue(client.getInputStream().readNBytes(2).contentEquals(byteArrayOf(5, 0xFF.toByte())))
            }
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write(byteArrayOf(5, 1, 2))
                assertTrue(client.getInputStream().readNBytes(2).contentEquals(byteArrayOf(5, 2)))
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun socksRejectsAWrongSessionPassword() {
        val port = availablePort()
        val proxy = FoxProxy(port) { null }
        assertTrue(proxy.start())
        try {
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 3000
                val input = client.getInputStream()
                val output = client.getOutputStream()
                output.write(byteArrayOf(5, 1, 2))
                assertTrue(input.readNBytes(2).contentEquals(byteArrayOf(5, 2)))
                val wrong = "wrong".toByteArray()
                output.write(byteArrayOf(1, wrong.size.toByte()) + wrong + byteArrayOf(wrong.size.toByte()) + wrong)
                assertTrue(input.readNBytes(2).contentEquals(byteArrayOf(1, 1)))
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
