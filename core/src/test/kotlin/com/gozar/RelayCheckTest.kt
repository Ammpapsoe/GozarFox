package com.gozar

import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCheckTest {
    private class Pipe : FoxPipe {
        var closed = false
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        override fun close() { closed = true }
    }

    @Test
    fun usesAnotherTargetWhenOneDestinationIsUnavailable() {
        val seen = mutableListOf<String>()
        val pipe = Pipe()
        RelayCheck.verify { host ->
            seen.add(host)
            if (seen.size == 1) throw IOException("destination unavailable")
            pipe
        }
        assertEquals(listOf("www.mozilla.org", "www.cloudflare.com"), seen)
        assertTrue(pipe.closed)
    }

    @Test
    fun reportsFailureWhenEveryRelayAttemptFails() {
        var attempts = 0
        val error = assertThrows(IOException::class.java) {
            RelayCheck.verify { attempts++; throw IOException("relay unreachable") }
        }
        assertEquals(2, attempts)
        assertEquals("relay unreachable", error.message)
    }

    @Test
    fun authenticationAndTlsFailuresStopImmediately() {
        for (error in listOf(FoxProxyRefusal(407, "the proxy pass was refused"), SSLHandshakeException("certificate"))) {
            var attempts = 0
            assertThrows(error.javaClass) { RelayCheck.verify { attempts++; throw error } }
            assertEquals(1, attempts)
        }
        assertTrue(RelayCheck.passRejected(FoxProxyRefusal(401, "refused")))
        assertTrue(RelayCheck.passRejected(FoxProxyRefusal(500, "the pass was rejected (auth)")))
        assertFalse(RelayCheck.passRejected(FoxProxyRefusal(403, "no access")))
    }
}
