package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxServerCacheTest {

    private val now = 1_800_000_000_000L
    private fun place(host: String = "nl.mozilla.example", port: Int = 2499, city: String = "Amsterdam") =
        FoxLocation("NL", "Netherlands", city, host, port, false)

    @Test fun roundTripKeepsLocationsAndBoundedExpiry() {
        val text = FoxServerCache.encode(listOf(place()), now)
        assertNotNull(text)
        val snap = FoxServerCache.decode(text!!, now + 1_000L)
        assertNotNull(snap)
        assertEquals("nl.mozilla.example", snap!!.locations.single().host)
        assertEquals(now, snap.fetchedAt)
        assertEquals(now + FoxServerCache.LIFETIME_MS, snap.expiresAt)
    }

    @Test fun expiredSnapshotIsRejectedAtTheBoundary() {
        val text = FoxServerCache.encode(listOf(place()), now)!!
        assertNull(FoxServerCache.decode(text, now + FoxServerCache.LIFETIME_MS))
        assertNotNull(FoxServerCache.decode(text, now + FoxServerCache.LIFETIME_MS - 1))
    }

    @Test fun reuseDoesNotExtendTimestamp() {
        val text = FoxServerCache.encode(listOf(place()), now)!!
        val first = FoxServerCache.decode(text, now + 60_000L)!!
        val later = FoxServerCache.decode(text, now + 3_600_000L)!!
        // Decoding is pure: the same stored text always yields the original expiry.
        assertEquals(first.expiresAt, later.expiresAt)
        assertEquals(now + FoxServerCache.LIFETIME_MS, later.expiresAt)
    }

    @Test fun futureDatedBeyondSkewIsRejectedButSmallSkewIsAccepted() {
        val future = FoxServerCache.encode(listOf(place()), now + FoxServerCache.MAX_SKEW_MS + 60_000L)!!
        assertNull(FoxServerCache.decode(future, now))
        val near = FoxServerCache.encode(listOf(place()), now + FoxServerCache.MAX_SKEW_MS - 1_000L)!!
        assertNotNull(FoxServerCache.decode(near, now))
    }

    @Test fun malformedOrWrongVersionIsRejected() {
        assertNull(FoxServerCache.decode("", now))
        assertNull(FoxServerCache.decode("not json", now))
        assertNull(FoxServerCache.decode("""{"version":2,"fetched_at":$now,"locations":[]}""", now))
        assertNull(FoxServerCache.decode("""{"version":1,"fetched_at":$now,"locations":[]}""", now))
        assertNull(FoxServerCache.decode("""{"version":1,"fetched_at":0,"locations":[{"code":"NL","host":"a.b","port":1}]}""", now))
    }

    @Test fun invalidStoredRecordInvalidatesWholeSnapshot() {
        val bad = """{"version":1,"fetched_at":$now,"locations":[""" +
            """{"code":"NL","country":"x","city":"y","host":"nl.mozilla.example","port":2499,"recommended":false},""" +
            """{"code":"NL","country":"x","city":"y","host":"evil\r\nHost: x","port":443,"recommended":false}]}"""
        assertNull(FoxServerCache.decode(bad, now))
    }

    @Test fun encodeDropsInvalidRecordsAndRefusesEmptyResults() {
        val mixed = listOf(
            place(),
            place(host = "127.0.0.1"),
            place(host = "-bad.example"),
            place(host = "single"),
            place(port = 0),
            place(city = "bad\u0000city")
        )
        val text = FoxServerCache.encode(mixed, now)
        assertNotNull(text)
        assertEquals(1, FoxServerCache.decode(text!!, now)!!.locations.size)
        assertNull(FoxServerCache.encode(listOf(place(host = "127.0.0.1")), now))
        assertNull(FoxServerCache.encode(emptyList(), now))
    }

    @Test fun boundsRejectOversizedLists() {
        val many = List(FoxServerCache.MAX_ENTRIES + 1) { place() }
        assertNull(FoxServerCache.encode(many, now))
        assertTrue(FoxServerCache.encode(List(FoxServerCache.MAX_ENTRIES) { place() }, now) != null)
    }
}
