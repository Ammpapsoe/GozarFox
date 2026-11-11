package com.gozar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TamperCheckTest {

    private val official = "800d24fa5cc38d2a63c216051d4710b950b0b3c4f457096ccfc11794a2b3bfd3"

    @Test
    fun onlyTheOfficialSigningDigestIsTrusted() {
        assertTrue(TamperCheck.trusted(listOf(official)))
        assertTrue(TamperCheck.trusted(listOf(official.uppercase())))
        assertTrue(TamperCheck.trusted(listOf("deadbeef", official)))
        assertFalse(TamperCheck.trusted(listOf("deadbeef")))
        assertFalse(TamperCheck.trusted(emptyList()))
        assertTrue(TamperCheck.trustedCurrentSigners(listOf(official)))
        assertFalse(TamperCheck.trustedCurrentSigners(listOf(official, "deadbeef")))
        assertFalse(TamperCheck.trustedCurrentSigners(emptyList()))
    }

    @Test
    fun debugBuildsSkipTheCertificateCheck() {
        // Unit tests run the debug variant, whose debug key is not the pinned
        // release key; verify() must still pass so the app is testable.
        val context = RuntimeEnvironment.getApplication()
        assertTrue(BuildConfig.DEBUG)
        assertTrue(TamperCheck.verify(context))
        // This debug-build exemption does not depend on the mutable manifest
        // debuggable flag, which must not exempt a release build.
        val flags = context.applicationInfo.flags
        try {
            context.applicationInfo.flags = flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
            assertTrue(TamperCheck.verify(context))
        } finally {
            context.applicationInfo.flags = flags
        }
    }
}
