package com.gozar

import android.app.Application
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FoxBusyLifecycleTest {
    private lateinit var context: Context
    private val releases = mutableListOf<CountDownLatch>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
        Fox.busy = false
        Fox.message = ""
        Fox.stage = ""
        await { activeCount() == 0 }
    }

    @After
    fun tearDown() {
        releases.forEach { it.countDown() }
        await { activeCount() == 0 }
        Fox.busy = false
        Fox.message = ""
        Fox.stage = ""
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("timed out waiting for a synthetic operation", condition())
    }

    private fun activeCount(): Int {
        val counterField = Fox::class.java.getDeclaredField("operations").apply { isAccessible = true }
        val counter = counterField.get(Fox)
        val activeField = FoxBusyCounter::class.java.getDeclaredField("active").apply { isAccessible = true }
        return (activeField.get(counter) as AtomicInteger).get()
    }

    private fun launch(onComplete: () -> Unit = {}, body: () -> Unit) {
        val method = Fox::class.java.getDeclaredMethod(
            "work", Context::class.java, Int::class.javaPrimitiveType!!, java.lang.Long::class.java,
            kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function0::class.java
        ).apply { isAccessible = true }
        method.invoke(Fox, context, 0, null, onComplete, body)
    }

    @Test
    fun busyRemainsSetThroughCompletionCleanup() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases.add(it) }
        launch(onComplete = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }) { }
        assertTrue("cleanup did not start", entered.await(5, TimeUnit.SECONDS))
        assertEquals(1, activeCount())
        assertTrue("busy cleared before completion cleanup", Fox.busy)
        release.countDown()
        await { activeCount() == 0 }
        assertFalse(Fox.busy)
    }

    @Test
    fun failedWorkerReleasesBusyAndReportsItsFailure() {
        launch { throw IllegalStateException("synthetic failure") }
        await { activeCount() == 0 && Fox.message == "synthetic failure" }
        assertFalse(Fox.busy)
    }

    @Test
    fun fiftyOverlappingOperationsRemainBusyUntilTheLastWorkerCompletes() {
        repeat(50) { iteration ->
            val entered = CountDownLatch(2)
            val first = CountDownLatch(1).also { releases.add(it) }
            val second = CountDownLatch(1).also { releases.add(it) }
            launch { entered.countDown(); check(first.await(5, TimeUnit.SECONDS)) }
            launch { entered.countDown(); check(second.await(5, TimeUnit.SECONDS)) }
            assertTrue("workers did not start at $iteration", entered.await(5, TimeUnit.SECONDS))
            assertEquals(2, activeCount())
            assertTrue(Fox.busy)
            first.countDown()
            await { activeCount() == 1 }
            assertTrue("busy cleared while another worker remained at $iteration", Fox.busy)
            second.countDown()
            await { activeCount() == 0 }
            assertFalse(Fox.busy)
        }
    }
}
