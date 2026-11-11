package com.gozar

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoxTorInstrumentedTest {
    @Test(timeout = 900_000)
    fun embeddedEscalationRetainsMozillaTunnelAndStopsOnlyOnFinish() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val originalFactory = FoxTorRuntime.attemptFactory
        val originalReachability = FoxTorRuntime.reachability
        val originalManual = FoxTorRuntime.manualProvider
        val originalAutomatic = FoxTorRuntime.automaticProvider
        val modes = mutableListOf<FoxTorPlan.Mode>()
        var current = FoxTorPlan.Mode.OBFS4
        var port = 0
        val obfs4 = "obfs4 8.8.8.8:443 ${"A".repeat(40)} cert=${"A".repeat(70)} iat-mode=0"
        val webtunnel = "webtunnel 1.1.1.1:443 ${"B".repeat(40)} url=https://bridge.example.com/a1B2 ver=0.0.1"
        try {
            FoxBridgeStore.manual = ""
            FoxTorRuntime.manualProvider = { null }
            FoxTorRuntime.automaticProvider = { listOfNotNull(FoxBridge.parse(obfs4), FoxBridge.parse(webtunnel)) }
            FoxTorRuntime.attemptFactory = { context, mode, _, budget ->
                current = mode
                modes += mode
                if (mode != FoxTorPlan.Mode.MEEK) {
                    object : FoxTorAttempt {
                        override val port: Int = 0
                        override fun start(): Int = throw IOException("synthetic pre-circuit failure")
                        override fun close() = Unit
                    }
                } else {
                    val attempt = originalFactory(context, mode, null, budget)
                    object : FoxTorAttempt {
                        override val port: Int get() = attempt.port
                        override fun start(): Int = attempt.start()
                        override fun diagnostics(): String = attempt.diagnostics()
                        override fun close() = attempt.close()
                    }
                }
            }
            repeat(2) { attempt ->
                try {
                    FoxTorRuntime.run(app) {
                        assertEquals(FoxTorPlan.Mode.MEEK, current)
                        port = FoxNet.loginSocksPort() ?: 0
                        assertTrue(port in 1..65535)
                    }
                } catch (error: FoxError) {
                    throw AssertionError("attempt=$attempt; " + error.message)
                }
                assertEquals(FoxTorPlan.Mode.MEEK, modes.last())
                assertTrue(FoxTorRuntime.isActive())
                assertNull(FoxNet.loginSocksPort())
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2_000) }
                if (attempt == 0) killBoundTorProcess(app)
                FoxTorRuntime.finish(app)
                assertStopped(app, port, attempt)
            }
            try {
                FoxTorRuntime.run(app) {
                    port = FoxNet.loginSocksPort() ?: 0
                    assertTrue(port in 1..65535)
                    throw IOException("synthetic account failure")
                }
                fail("account failure was hidden")
            } catch (_: IOException) {
            }
            assertTrue(FoxTorRuntime.isActive())
            assertEquals(FoxTorPlan.order + FoxTorPlan.order + FoxTorPlan.order, modes)
            val established = modes.size
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2_000) }
            FoxTorRuntime.run(app) { }
            assertEquals(established, modes.size)
            FoxTorRuntime.finish(app)
            assertStopped(app, port, 2)
            assertEquals(FoxTorPlan.order + FoxTorPlan.order + FoxTorPlan.order, modes)
        } finally {
            FoxTorRuntime.stop(app)
            FoxTorRuntime.attemptFactory = originalFactory
            FoxTorRuntime.reachability = originalReachability
            FoxTorRuntime.manualProvider = originalManual
            FoxTorRuntime.automaticProvider = originalAutomatic
            FoxBridgeStore.manual = ""
        }
    }

    private fun killBoundTorProcess(app: Context) {
        val manager = app.getSystemService(ActivityManager::class.java)
        val process = manager.runningAppProcesses.orEmpty().singleOrNull {
            it.processName == app.packageName + ":tor"
        } ?: throw AssertionError("Bound TorService process was not visible")
        Process.killProcess(process.pid)
        val deadline = System.nanoTime() + 15_000_000_000L
        while (FoxTorRuntime.orbotStatus != org.torproject.jni.TorService.STATUS_OFF && System.nanoTime() < deadline) {
            Thread.sleep(100)
        }
        assertEquals(org.torproject.jni.TorService.STATUS_OFF, FoxTorRuntime.orbotStatus)
    }

    private fun assertStopped(app: Context, port: Int, attempt: Int) {
        assertFalse(FoxTorRuntime.isActive())
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2_000) }
            fail("Tor SOCKS listener remained open after attempt $attempt")
        } catch (_: IOException) {
        }
        assertFalse(File(app.cacheDir, "gozar-login-transport").exists())
        assertFalse(File(app.filesDir.parentFile, "app_TorService").exists())
        assertFalse(File(app.cacheDir, "gozar-login-transport/tor.log").exists())
    }
}
