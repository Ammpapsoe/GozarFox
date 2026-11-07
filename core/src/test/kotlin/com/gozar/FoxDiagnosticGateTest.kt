package com.gozar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxDiagnosticGateTest {
    @Test
    fun newerProbeSupersedesAnOlderProbe() {
        val gate = FoxDiagnosticGate<String>()
        assertFalse(gate.publish(0, "never-started"))
        val old = gate.begin()
        val newer = gate.begin()
        assertFalse(gate.publish(old, "old"))
        assertTrue(gate.publish(newer, "new"))
        assertFalse(gate.publish(newer, "duplicate"))
        assertEquals("new", gate.current())
        gate.invalidate()
        assertNull(gate.current())
    }

    @Test
    fun disconnectAndReconnectNeverPublishStaleResultsFiftyTimes() {
        repeat(50) { round ->
            val gate = FoxDiagnosticGate<Int>()
            val old = gate.begin()
            val waiting = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val stalePublished = AtomicReference<Boolean>()
            val worker = thread(isDaemon = true, name = "old-diagnostic-$round") {
                waiting.countDown()
                if (finish.await(5, TimeUnit.SECONDS)) stalePublished.set(gate.publish(old, -1))
            }
            try {
                assertTrue("old probe did not start", waiting.await(3, TimeUnit.SECONDS))
                gate.invalidate()
                val current = gate.begin()
                assertTrue(gate.publish(current, round))
            } finally {
                finish.countDown()
                worker.join(5000)
            }
            assertFalse("old probe did not terminate", worker.isAlive)
            assertEquals(false, stalePublished.get())
            assertEquals(round, gate.current())
        }
    }
}
