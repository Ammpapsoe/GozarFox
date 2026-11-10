package com.gozar

import android.app.Application
import android.content.Context
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FoxTorRuntimeTest {
    private lateinit var context: Context
    private lateinit var originalFactory: (Context, FoxTorPlan.Mode) -> FoxTorAttempt
    private lateinit var originalReachability: (Int) -> Unit
    private val created = mutableListOf<FoxTorPlan.Mode>()
    private val closed = mutableListOf<FoxTorPlan.Mode>()

    private inner class FakeAttempt(private val mode: FoxTorPlan.Mode, private val socksPort: Int) : FoxTorAttempt {
        override val port: Int = socksPort
        override fun start(): Int {
            if (mode == FoxTorPlan.Mode.DIRECT) throw IOException("synthetic direct failure")
            return port
        }

        override fun close() {
            closed += mode
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        created.clear()
        closed.clear()
        FoxTorRuntime.attemptFactory = { _, mode ->
            created += mode
            FakeAttempt(mode, 19000 + mode.ordinal)
        }
        FoxTorRuntime.reachability = { }
    }

    @After
    fun tearDown() {
        FoxTorRuntime.attemptFactory = { app, mode -> throw IllegalStateException("native backend was not restored") }
        FoxTorRuntime.reachability = { }
    }

    @Test
    fun directSuccessRunsBodyOnceAndStopsBeforeReturning() {
        FoxTorRuntime.attemptFactory = { _, mode ->
            created += mode
            object : FoxTorAttempt {
                override val port: Int = 19050
                override fun start(): Int = port
                override fun close() {
                    closed += mode
                }
            }
        }
        var calls = 0
        FoxTorRuntime.run(context) {
            calls++
            assertEquals(19050, FoxNet.loginSocksPort())
            assertTrue(closed.isEmpty())
        }
        assertEquals(1, calls)
        assertEquals(listOf(FoxTorPlan.Mode.DIRECT), created)
        assertEquals(listOf(FoxTorPlan.Mode.DIRECT), closed)
        assertNull(FoxNet.loginSocksPort())
    }

    @Test
    fun failedDirectAndUnreachableSnowflakeEscalateToMeek() {
        FoxTorRuntime.reachability = { port ->
            if (port == 19001) throw IOException("synthetic probe failure")
        }
        var calls = 0
        FoxTorRuntime.run(context) {
            calls++
            assertEquals(19002, FoxNet.loginSocksPort())
        }
        assertEquals(1, calls)
        assertEquals(FoxTorPlan.order, created)
        assertEquals(FoxTorPlan.order, closed)
        assertNull(FoxNet.loginSocksPort())
    }

    @Test
    fun accountFailureDoesNotRetryCredentialsOnAnotherTransport() {
        FoxTorRuntime.attemptFactory = { _, mode ->
            created += mode
            object : FoxTorAttempt {
                override val port: Int = 19050
                override fun start(): Int = port
                override fun close() {
                    closed += mode
                }
            }
        }
        val failure = FoxError("synthetic account rejection")
        try {
            FoxTorRuntime.run(context) { throw failure }
            fail("account failure was hidden")
        } catch (error: FoxError) {
            assertSame(failure, error)
        }
        assertEquals(listOf(FoxTorPlan.Mode.DIRECT), created)
        assertEquals(listOf(FoxTorPlan.Mode.DIRECT), closed)
        assertNull(FoxNet.loginSocksPort())
    }

    @Test
    fun exhaustedTransportsFailClosedAndCloseEveryAttempt() {
        FoxTorRuntime.attemptFactory = { _, mode ->
            created += mode
            object : FoxTorAttempt {
                override val port: Int = 0
                override fun start(): Int = throw IOException("synthetic transport failure")
                override fun close() {
                    closed += mode
                }
            }
        }
        var calls = 0
        try {
            FoxTorRuntime.run(context) { calls++ }
            fail("exhausted transports unexpectedly succeeded")
        } catch (error: FoxError) {
            assertEquals("Secure connection could not be established", error.message)
        }
        assertEquals(0, calls)
        assertEquals(FoxTorPlan.order, created)
        assertEquals(FoxTorPlan.order, closed)
        assertNull(FoxNet.loginSocksPort())
    }
}
