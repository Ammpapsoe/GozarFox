package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quota snapshot is the only place Mozilla tells a client how much of the
 * month is left. During an open (unmetered) period Guardian sends
 * `X-Quota-Unlimited: true`, either alone or beside the numeric allowance.
 *
 * Treating that flag as "no quota headers" is what made the account screen
 * say the usage was unavailable while the numbers were in the same response,
 * so these cases are pinned here.
 */
class FoxPassQuotaTest {

    private fun quotaOf(headers: Map<String, String>): FoxPass? =
        FoxApi.quota(FoxReply(200, "{}", headers))

    @Test
    fun unlimitedFlagAloneIsAKnownSnapshot() {
        val pass = quotaOf(mapOf("X-Quota-Unlimited" to "true", "X-Quota-Reset" to "1798761600000"))
        assertTrue(pass!!.unlimited)
        assertTrue(pass.usageKnown)
        assertEquals(0L, pass.limit)
        assertEquals("1798761600000", pass.reset)
    }

    @Test
    fun unlimitedFlagBesideNumbersKeepsTheNumbers() {
        // This is the shape Mozilla actually sends: remaining == limit.
        val pass = quotaOf(
            mapOf(
                "X-Quota-Unlimited" to "true",
                "X-Quota-Limit" to "53687091200",
                "X-Quota-Remaining" to "53687091200",
                "X-Quota-Reset" to "1798761600000"
            )
        )
        assertTrue(pass!!.unlimited)
        assertEquals(53687091200L, pass.limit)
        assertEquals(53687091200L, pass.remaining)
        // An unmetered month must never read as "fully used up": the flag,
        // not the arithmetic, decides.
        assertTrue(pass.unlimited && pass.remaining == pass.limit)
    }

    @Test
    fun meteredSnapshotIsUnchanged() {
        val pass = quotaOf(
            mapOf(
                "X-Quota-Limit" to "53687091200",
                "X-Quota-Remaining" to "10737418240",
                "X-Quota-Reset" to "1798761600000"
            )
        )
        assertFalse(pass!!.unlimited)
        assertTrue(pass.usageKnown)
        assertEquals(10737418240L, pass.remaining)
    }

    @Test
    fun missingHeadersStayUnknown() {
        // Nothing to show: the UI must keep saying the usage is unavailable.
        assertNull(quotaOf(emptyMap()))
        assertNull(quotaOf(mapOf("X-Quota-Limit" to "53687091200")))
        assertNull(quotaOf(mapOf("X-Quota-Remaining" to "1")))
    }

    @Test
    fun zeroOrNegativeAllowanceIsNotAKnownSnapshot() {
        // A zero limit is what an unactivated account sends; rendering it as
        // a real meter would divide by zero in the UI.
        assertNull(quotaOf(mapOf("X-Quota-Limit" to "0", "X-Quota-Remaining" to "0")))
        assertNull(quotaOf(mapOf("X-Quota-Limit" to "10", "X-Quota-Remaining" to "-1")))
    }

    @Test
    fun flagParsingIsCaseInsensitiveAndOnlyTrueCounts() {
        assertTrue(quotaOf(mapOf("x-quota-unlimited" to "TRUE"))!!.unlimited)
        // `false` means a metered month, and with no numbers there is still
        // nothing to show.
        assertNull(quotaOf(mapOf("X-Quota-Unlimited" to "false")))
    }
}
