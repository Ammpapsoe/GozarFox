package com.gozar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TamperCheckTest {

    private val official = "bc9d3368f2671175f81be047a1ff72bca0cfc58e8b1b8f9f87e24694ba45f5b1"

    @Test
    fun onlyTheOfficialSigningDigestIsTrusted() {
        assertTrue(TamperCheck.trusted(listOf(official)))
        assertTrue(TamperCheck.trusted(listOf(official.uppercase())))
        assertTrue(TamperCheck.trusted(listOf("deadbeef", official)))
        assertFalse(TamperCheck.trusted(listOf("deadbeef")))
        assertFalse(TamperCheck.trusted(emptyList()))
    }

    @Test
    fun debugBuildsSkipTheCertificateCheck() {
        // Unit tests run the debug variant, whose debug key is not the pinned
        // release key; verify() must still pass so the app is testable.
        val context = RuntimeEnvironment.getApplication()
        assertTrue(TamperCheck.verify(context))
    }
}
