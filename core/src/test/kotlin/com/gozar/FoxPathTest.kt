package com.gozar

import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxPathTest {

    @Test
    fun rankPrefersLowerScoreAndKeepsFailuresLast() {
        val failed = FoxPathSample("bad", 443, 0, 0, 100, false)
        val slow = FoxPathSample("slow", 443, 80, 10, 0, true)
        val fast = FoxPathSample("fast", 443, 30, 5, 0, true)
        val jittery = FoxPathSample("jittery", 443, 20, 40, 0, true)
        val lossy = FoxPathSample("lossy", 443, 10, 0, 33, true)
        val ranked = FoxPath.rank(listOf(failed, slow, lossy, jittery, fast))
        assertEquals(listOf("fast", "slow", "jittery", "lossy", "bad"), ranked.map { it.host })
        assertEquals(Long.MAX_VALUE, FoxPath.score(failed))
        assertEquals(100L, FoxPath.score(jittery))
        assertEquals(340L, FoxPath.score(lossy))
    }

    @Test
    fun probeMeasuresAReachableListenerAndFailsAClosedPort() {
        ServerSocket(0).use { listener ->
            val sample = FoxPath.probe("127.0.0.1", listener.localPort, samples = 3, timeoutMs = 1000)
            assertTrue(sample.ok)
            assertEquals(0, sample.lossPercent)
            assertTrue(sample.averageMs >= 0)
            assertTrue(sample.jitterMs >= 0)
        }
        val closed = ServerSocket(0)
        val port = closed.localPort
        closed.close()
        val sample = FoxPath.probe("127.0.0.1", port, samples = 3, timeoutMs = 300)
        assertFalse(sample.ok)
        assertEquals(100, sample.lossPercent)
    }

    @Test
    fun measureRanksReachableRelaysBeforeFailures() {
        ServerSocket(0).use { listener ->
            val closed = ServerSocket(0)
            val deadPort = closed.localPort
            closed.close()
            val locations = listOf(
                FoxLocation("BAD", "Bad", "", "127.0.0.1", deadPort, false),
                FoxLocation("OK", "Ok", "", "127.0.0.1", listener.localPort, false)
            )
            val samples = FoxPath.measure(locations, concurrency = 2, samples = 3, timeoutMs = 500)
            val ranked = FoxPath.rank(samples)
            assertTrue(ranked.first().ok)
            assertEquals(listener.localPort, ranked.first().port)
            assertFalse(ranked.last().ok)
        }
    }
}
