package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxUpdateFlightTest {
    @Test
    fun subscribersShareARequestAndPublicationFailureDoesNotStrandThem() {
        val flight = FoxUpdateFlight<String>()
        val seen = mutableListOf<String>()
        assertTrue(flight.join(false) { seen.add(it) })
        assertFalse(flight.join(true) { seen.add(it) })
        val done = flight.complete("available", "failed") { _, notify ->
            assertTrue(notify)
            throw IllegalStateException("storage unavailable")
        }
        assertEquals("failed", done.result)
        done.callbacks.forEach { it(done.result) }
        assertEquals(listOf("failed", "failed"), seen)
        assertTrue(flight.join(false))
        assertEquals("current", flight.complete("current", "failed") { _, notify -> assertFalse(notify) }.result)
    }
}
