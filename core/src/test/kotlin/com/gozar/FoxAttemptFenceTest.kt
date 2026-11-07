package com.gozar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxAttemptFenceTest {
    @Test
    fun supersededSetupCannotResetNewAttemptAcrossFiftyRaces() {
        repeat(50) { iteration ->
            val fence = FoxAttemptFence()
            val old = fence.advance()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val resetByOld = AtomicBoolean(false)
            val generationSet = AtomicInteger(0)
            val worker = thread(isDaemon = true) {
                entered.countDown()
                if (release.await(5, TimeUnit.SECONDS)) {
                    fence.ifCurrent(old) {
                        resetByOld.set(true)
                        generationSet.set(1)
                    }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fence.advance() // stop
            val current = fence.advance() // restart
            assertEquals(Unit, fence.ifCurrent(current) { generationSet.set(2) })
            release.countDown()
            worker.join(5000)
            assertFalse("old worker stuck at iteration $iteration", worker.isAlive)
            assertFalse("old setup ran at iteration $iteration", resetByOld.get())
            assertEquals("new setup was overwritten at iteration $iteration", 2, generationSet.get())
        }
    }

    @Test
    fun stopWaitsForAtomicSetupBeforeAdvancingAcrossFiftyRaces() {
        repeat(50) { iteration ->
            val fence = FoxAttemptFence()
            val current = fence.advance()
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finishedSetup = AtomicBoolean(false)
            val stopped = CountDownLatch(1)
            val orderViolation = AtomicBoolean(false)
            val worker = thread(isDaemon = true) {
                fence.ifCurrent(current) {
                    inside.countDown()
                    if (release.await(5, TimeUnit.SECONDS)) finishedSetup.set(true)
                }
            }
            assertTrue(inside.await(5, TimeUnit.SECONDS))
            val stopper = thread(isDaemon = true) {
                fence.advance()
                if (!finishedSetup.get()) orderViolation.set(true)
                stopped.countDown()
            }
            assertFalse("stop advanced inside setup at iteration $iteration", stopped.await(20, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            worker.join(5000)
            stopper.join(5000)
            assertFalse(worker.isAlive || stopper.isAlive)
            assertFalse("stop overtook setup at iteration $iteration", orderViolation.get())
            assertTrue(finishedSetup.get())
            assertEquals(null, fence.ifCurrent(current) { true })
        }
    }
}
