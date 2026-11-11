package com.gozar

import android.app.Application
import android.content.Context
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FoxTorRuntimeTest {
    private data class Plan(val mode: FoxTorPlan.Mode, val bridge: String?, val budget: Long)

    private lateinit var context: Context
    private lateinit var originalFactory: (Context, FoxTorPlan.Mode, String?, Long) -> FoxTorAttempt
    private lateinit var originalReachability: (Int, Int) -> Unit
    private lateinit var originalStatusCallback: (String) -> Unit
    private lateinit var originalManual: () -> FoxBridge.Candidate?
    private lateinit var originalAutomatic: (Context) -> List<FoxBridge.Candidate>
    private val created = mutableListOf<Plan>()
    private val closed = mutableListOf<FoxTorPlan.Mode>()
    private val obfs4 = "obfs4 8.8.8.8:443 ${"A".repeat(40)} cert=${"A".repeat(70)} iat-mode=0"
    private val webtunnel = "webtunnel 1.1.1.1:443 ${"B".repeat(40)} url=https://bridge.example.com/a1B2 ver=0.0.1"

    private inner class FakeAttempt(private val mode: FoxTorPlan.Mode, private val socksPort: Int, private val failing: Set<FoxTorPlan.Mode>) : FoxTorAttempt {
        override val port: Int = socksPort
        override fun start(): Int {
            if (mode in failing) throw IOException("synthetic private address 8.8.8.8 must not escape")
            return port
        }

        override fun diagnostics(): String = "stage=bootstrap"
        override fun close() {
            closed += mode
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FoxTorRuntime.stop(context)
        originalFactory = FoxTorRuntime.attemptFactory
        originalReachability = FoxTorRuntime.reachability
        originalStatusCallback = FoxTorRuntime.statusCallback
        originalManual = FoxTorRuntime.manualProvider
        originalAutomatic = FoxTorRuntime.automaticProvider
        created.clear()
        closed.clear()
        FoxBridgeStore.manual = ""
        FoxTorRuntime.statusCallback = { }
        FoxTorRuntime.manualProvider = { null }
        FoxTorRuntime.automaticProvider = { emptyList() }
        FoxTorRuntime.reachability = { _, timeout -> assertTrue(timeout > 0) }
    }

    @After
    fun tearDown() {
        FoxTorRuntime.stop(context)
        FoxTorRuntime.attemptFactory = originalFactory
        FoxTorRuntime.reachability = originalReachability
        FoxTorRuntime.statusCallback = originalStatusCallback
        FoxTorRuntime.manualProvider = originalManual
        FoxTorRuntime.automaticProvider = originalAutomatic
        FoxBridgeStore.manual = ""
    }

    private fun factory(failing: Set<FoxTorPlan.Mode> = emptySet()) {
        FoxTorRuntime.attemptFactory = { _, mode, bridge, budget ->
            created += Plan(mode, bridge, budget)
            FakeAttempt(mode, 19000 + created.size, failing)
        }
    }

    @Test
    fun manualSuccessRetainsTunnelUntilFinish() {
        factory()
        FoxTorRuntime.manualProvider = { FoxBridge.parse(obfs4) }
        FoxTorRuntime.automaticProvider = { throw AssertionError("feed must not be downloaded") }
        var calls = 0
        FoxTorRuntime.run(context) {
            calls++
            assertTrue(FoxNet.loginSocksPort() != null)
            assertTrue(closed.isEmpty())
        }
        assertEquals(1, calls)
        assertEquals(listOf(FoxTorPlan.Mode.OBFS4), created.map { it.mode })
        assertEquals(obfs4, created.single().bridge)
        assertEquals(15_000L, created.single().budget)
        assertTrue(closed.isEmpty())
        assertTrue(FoxTorRuntime.isActive())
        assertNull(FoxNet.loginSocksPort())
        FoxTorRuntime.finish(context)
        assertEquals(created.map { it.mode }, closed)
        assertFalse(FoxTorRuntime.isActive())
    }

    @Test
    fun failedManualAndObfs4RotateToWebTunnelBeforeCircuitStage() {
        factory(setOf(FoxTorPlan.Mode.OBFS4))
        FoxTorRuntime.manualProvider = { FoxBridge.parse(obfs4) }
        FoxTorRuntime.automaticProvider = { listOfNotNull(FoxBridge.parse(obfs4), FoxBridge.parse(webtunnel)) }
        var calls = 0
        FoxTorRuntime.run(context) { calls++ }
        assertEquals(1, calls)
        assertEquals(listOf(FoxTorPlan.Mode.OBFS4, FoxTorPlan.Mode.OBFS4, FoxTorPlan.Mode.WEBTUNNEL), created.map { it.mode })
        assertEquals(listOf(FoxTorPlan.Mode.OBFS4, FoxTorPlan.Mode.OBFS4), closed)
        assertTrue(FoxBridgeStore.manual.isEmpty())
        FoxTorRuntime.finish(context)
        assertEquals(created.map { it.mode }, closed)
        assertNull(FoxNet.loginSocksPort())
    }

    @Test
    fun accountFailureKeepsTunnelAndNeverRetriesOnAnotherTransport() {
        factory()
        FoxTorRuntime.manualProvider = { FoxBridge.parse(obfs4) }
        val failure = FoxError("synthetic account rejection")
        try {
            FoxTorRuntime.run(context) { throw failure }
            fail("account failure was hidden")
        } catch (error: FoxError) {
            assertSame(failure, error)
        }
        assertEquals(1, created.size)
        assertTrue(closed.isEmpty())
        assertTrue(FoxTorRuntime.isActive())
        var retries = 0
        FoxTorRuntime.run(context) { retries++ }
        assertEquals(1, retries)
        assertEquals(1, created.size)
        FoxTorRuntime.finish(context)
        assertEquals(created.map { it.mode }, closed)
        assertNull(FoxNet.loginSocksPort())
    }

    @Test
    fun mozillaFailureAfterBootstrapKeepsSameTunnelForRetry() {
        factory()
        FoxTorRuntime.manualProvider = { FoxBridge.parse(obfs4) }
        FoxTorRuntime.reachability = { _, _ -> throw IOException("synthetic Mozilla timeout") }
        try {
            FoxTorRuntime.run(context) { fail("body must wait for reachability") }
            fail("Mozilla failure was hidden")
        } catch (error: FoxError) {
            assertTrue(error.message.orEmpty().contains("same tunnel"))
        }
        assertEquals(1, created.size)
        assertTrue(closed.isEmpty())
        assertTrue(FoxTorRuntime.isActive())
        FoxTorRuntime.reachability = { _, _ -> }
        var calls = 0
        FoxTorRuntime.run(context) { calls++ }
        assertEquals(1, calls)
        assertEquals(1, created.size)
        FoxTorRuntime.finish(context)
        assertEquals(created.map { it.mode }, closed)
    }

    @Test
    fun exhaustedRotationStopsAtTenAndFallbacksWithoutLeakingBridgeData() {
        factory(FoxTorPlan.order.toSet())
        FoxTorRuntime.automaticProvider = {
            (1..12).map { index ->
                FoxBridge.parse(obfs4.replace("8.8.8.8", "8.8.8.$index").replace("A".repeat(40), index.toString(16).padStart(40, '0')))!!
            }
        }
        var calls = 0
        try {
            FoxTorRuntime.run(context) { calls++ }
            fail("exhausted transports unexpectedly succeeded")
        } catch (error: FoxError) {
            val detail = error.message.orEmpty()
            assertTrue(detail.startsWith("Secure connection could not be established"))
            assertTrue(detail.contains("OBFS4"))
            assertTrue(detail.contains("SNOWFLAKE"))
            assertTrue(detail.contains("MEEK"))
            assertFalse(detail.contains("8.8.8."))
            assertTrue(detail.contains("[ip]"))
        }
        assertEquals(0, calls)
        assertEquals(12, created.size)
        assertEquals(10, created.count { it.mode == FoxTorPlan.Mode.OBFS4 })
        assertEquals(listOf(FoxTorPlan.Mode.SNOWFLAKE, FoxTorPlan.Mode.MEEK), created.takeLast(2).map { it.mode })
        assertTrue(created.all { it.budget in 1..90_000L })
        assertEquals(created.map { it.mode }, closed)
        assertFalse(FoxTorRuntime.isActive())
        assertNull(FoxNet.loginSocksPort())
    }

    @Test
    fun unavailableFeedFallsBackToSnowflakeAndRetainsIt() {
        factory(setOf(FoxTorPlan.Mode.MEEK))
        FoxTorRuntime.automaticProvider = { throw IOException("feed unavailable") }
        var calls = 0
        FoxTorRuntime.run(context) { calls++ }
        assertEquals(1, calls)
        assertEquals(listOf(FoxTorPlan.Mode.SNOWFLAKE), created.map { it.mode })
        assertTrue(closed.isEmpty())
        FoxTorRuntime.finish(context)
        assertEquals(created.map { it.mode }, closed)
    }

    @Test
    fun deadRetainedBindingIsClosedAndNeverReused() {
        var alive = true
        FoxTorRuntime.manualProvider = { FoxBridge.parse(obfs4) }
        FoxTorRuntime.attemptFactory = { _, mode, bridge, budget ->
            created += Plan(mode, bridge, budget)
            object : FoxTorAttempt {
                override val port: Int = 19000 + created.size
                override fun start(): Int = port
                override fun isAlive(): Boolean = alive
                override fun close() {
                    closed += mode
                }
            }
        }
        FoxTorRuntime.run(context) { }
        assertTrue(FoxTorRuntime.isActive())
        alive = false
        try {
            FoxTorRuntime.run(context) { throw IOException("synthetic dead binding") }
            fail("dead binding was reused")
        } catch (error: FoxError) {
            assertTrue(error.message.orEmpty().contains("fresh connection"))
        }
        assertFalse(FoxTorRuntime.isActive())
        assertEquals(1, closed.size)
        alive = true
        FoxTorRuntime.run(context) { }
        assertEquals(2, created.size)
        FoxTorRuntime.finish(context)
        assertEquals(2, closed.size)
    }

    @Test
    fun stopInterruptsBootstrapAndCompletesCleanupBeforeReturning() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        FoxTorRuntime.manualProvider = { FoxBridge.parse(obfs4) }
        FoxTorRuntime.attemptFactory = { _, mode, bridge, budget ->
            created += Plan(mode, bridge, budget)
            object : FoxTorAttempt {
                override val port: Int = 19000
                override fun start(): Int {
                    entered.countDown()
                    check(released.await(5, TimeUnit.SECONDS))
                    throw IOException("synthetic bootstrap stopped")
                }
                override fun close() {
                    if (mode !in closed) closed += mode
                    released.countDown()
                }
            }
        }
        val worker = thread {
            try {
                FoxTorRuntime.run(context) { }
            } catch (failure: Throwable) {
                error.set(failure)
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        FoxTorRuntime.stop(context)
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertTrue(error.get() is FoxError)
        assertEquals(created.map { it.mode }, closed)
        assertFalse(FoxTorRuntime.isActive())
    }
}
