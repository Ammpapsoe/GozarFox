package com.gozar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxUpdateFlightConcurrencyTest {
    @Test
    fun publicationCannotRaceASecondRequestAcrossFiftyInterleavings() {
        repeat(50) {
            val flight = FoxUpdateFlight<String>()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val attempting = CountDownLatch(1)
            val joined = CountDownLatch(1)
            val published = AtomicBoolean(false)
            val secondStarts = AtomicBoolean(false)
            val secondSawPublication = AtomicBoolean(false)
            val completion = AtomicReference<FoxUpdateFlight.Completion<String>>()
            val firstCalls = mutableListOf<String>()
            assertTrue(flight.join(false) { firstCalls.add(it) })
            assertFalse(flight.join(true))
            val first = thread(isDaemon = true) {
                completion.set(flight.complete("older", "failed") { _, shouldNotify ->
                    check(shouldNotify)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    published.set(true)
                })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = thread(isDaemon = true) {
                attempting.countDown()
                secondStarts.set(flight.join(false))
                secondSawPublication.set(published.get())
                joined.countDown()
            }
            try {
                assertTrue(attempting.await(5, TimeUnit.SECONDS))
                assertFalse(joined.await(10, TimeUnit.MILLISECONDS))
            } finally {
                release.countDown()
                first.join(10_000)
                second.join(10_000)
            }
            assertFalse(first.isAlive)
            assertFalse(second.isAlive)
            assertTrue(secondStarts.get())
            assertTrue(secondSawPublication.get())
            val done = completion.get()
            assertNotNull(done)
            assertEquals("older", done.result)
            done.callbacks.forEach { it(done.result) }
            assertEquals(listOf("older"), firstCalls)
            assertEquals("newer", flight.complete("newer", "failed") { _, _ -> }.result)
        }
    }
}
