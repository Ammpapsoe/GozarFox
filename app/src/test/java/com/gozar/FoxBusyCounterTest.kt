package com.gozar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxBusyCounterTest {
    @Test
    fun testOverrideCannotHideAnActuallyActiveOperation() {
        val counter = FoxBusyCounter()
        counter.busy = true
        assertTrue(counter.busy)
        counter.begin()
        counter.busy = false
        assertTrue(counter.busy)
        counter.end()
        assertFalse(counter.busy)
    }

    @Test
    fun finishingAnOldOperationCannotHideANewActiveOperation() {
        repeat(50) { iteration ->
            val afterDecrement = CountDownLatch(1)
            val publish = CountDownLatch(1)
            val counter = FoxBusyCounter {
                afterDecrement.countDown()
                check(publish.await(5, TimeUnit.SECONDS))
            }
            counter.begin()
            val finisher = thread { counter.end() }
            try {
                assertTrue("old operation did not reach the test barrier: $iteration", afterDecrement.await(5, TimeUnit.SECONDS))
                counter.begin()
                assertTrue("new operation should be busy: $iteration", counter.busy)
                publish.countDown()
                finisher.join(5_000)
                assertFalse("old operation did not finish: $iteration", finisher.isAlive)
                assertTrue("old completion hid the new operation: $iteration", counter.busy)
                counter.end()
                assertFalse("counter remained busy after both operations: $iteration", counter.busy)
            } finally {
                publish.countDown()
                finisher.join(5_000)
            }
        }
    }
}
