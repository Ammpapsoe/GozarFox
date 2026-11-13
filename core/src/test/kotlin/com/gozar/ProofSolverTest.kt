package com.gozar

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProofSolverTest {

    private fun digestHex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `finds the exact two-character suffix`() {
        assertEquals("Qz", ProofSolver.findPowSuffix("fs-base", digestHex("fs-baseQz")))
        assertEquals("aA", ProofSolver.findPowSuffix("x", digestHex("xaA")))
        assertEquals("99", ProofSolver.findPowSuffix("y", digestHex("y99")))
    }

    @Test
    fun `refuses malformed targets`() {
        assertNull(ProofSolver.findPowSuffix("b", "zz"))            // non-hex
        assertNull(ProofSolver.findPowSuffix("b", "abc"))           // odd length
        assertNull(ProofSolver.findPowSuffix("b", "abcd"))          // not 32 bytes
        assertNull(ProofSolver.findPowSuffix("b", ""))              // empty
    }

    @Test
    fun `gives up when no suffix matches`() {
        assertNull(
            ProofSolver.findPowSuffix(
                "b",
                "0000000000000000000000000000000000000000000000000000000000000000",
            ),
        )
    }

    @Test
    fun `cancels mid-search through keepGoing`() {
        var polls = 0
        val suffix = ProofSolver.findPowSuffix("b", digestHex("bzz")) {
            polls++
            polls < 10 // stop before reaching the 'zz' corner
        }
        assertNull(suffix)
        assertTrue("keepGoing must be polled inside the loop", polls >= 10)
    }
}
