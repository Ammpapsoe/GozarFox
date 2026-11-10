package com.gozar

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Server-list cache states through the real sealed preferences: valid, expired,
 * tampered or unsealed, and empty. Each non-valid state must fall through to a live
 * official fetch and must never replace a good snapshot with an invalid one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FoxServerCacheStoreTest {

    private lateinit var context: Context
    private val now = System.currentTimeMillis()
    private val list = listOf(FoxLocation("NL", "Netherlands", "Amsterdam", "nl.mozilla.example", 2499, false))
    private val fresh = listOf(FoxLocation("DE", "Germany", "Berlin", "de.mozilla.example", 2499, false))

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
        Fox.servers = emptyList()
    }

    @After fun tearDown() {
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
        Fox.servers = emptyList()
    }

    private fun rawStored(): String = context.getSharedPreferences("fox", Context.MODE_PRIVATE).getString("server_cache", "").orEmpty()
    private fun writeRaw(value: String) = context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().putString("server_cache", value).commit()

    private fun failingFetch(): () -> List<FoxLocation> = { fail("live fetch must not run"); emptyList() }

    @Test fun validSealedSnapshotIsUsedWithoutNetworkAndNotRewritten() {
        FoxStore.saveServerCache(context, FoxServerCache.encode(list, now)!!)
        val before = rawStored()
        val got = Fox.obtainServers(context, forceFresh = false, fetch = failingFetch())
        assertEquals("nl.mozilla.example", got.single().host)
        assertEquals(before, rawStored())
    }

    @Test fun expiredSnapshotFallsThroughToLiveFetchAndIsReplaced() {
        FoxStore.saveServerCache(context, FoxServerCache.encode(list, now - FoxServerCache.LIFETIME_MS - 60_000L)!!)
        var calls = 0
        val got = Fox.obtainServers(context, forceFresh = false) { calls++; fresh }
        assertEquals(1, calls)
        assertEquals("de.mozilla.example", got.single().host)
        assertEquals(fresh.single().host, FoxServerCache.decode(FoxStore.serverCache(context), System.currentTimeMillis())!!.locations.single().host)
    }

    @Test fun tamperedSealedSnapshotIsRejectedAndFallsThrough() {
        FoxStore.saveServerCache(context, FoxServerCache.encode(list, now)!!)
        val stored = rawStored()
        val mid = stored.length / 2
        val flipped = if (stored[mid] == 'A') 'B' else 'A'
        writeRaw(stored.substring(0, mid) + flipped + stored.substring(mid + 1))
        assertEquals("", FoxStore.serverCache(context))
        var calls = 0
        Fox.obtainServers(context, forceFresh = false) { calls++; fresh }
        assertEquals(1, calls)
    }

    @Test fun unsealedPlaintextIsIgnoredEvenWhenWellFormed() {
        writeRaw(FoxServerCache.encode(list, now)!!)
        assertEquals("", FoxStore.serverCache(context))
        var calls = 0
        Fox.obtainServers(context, forceFresh = false) { calls++; fresh }
        assertEquals(1, calls)
    }

    @Test fun emptyOrMissingCacheFallsThroughToLiveFetch() {
        assertEquals("", FoxStore.serverCache(context))
        var calls = 0
        Fox.obtainServers(context, forceFresh = false) { calls++; fresh }
        assertEquals(1, calls)
    }

    @Test fun emptyLiveResultNeverReplacesAGoodSnapshot() {
        FoxStore.saveServerCache(context, FoxServerCache.encode(list, now - FoxServerCache.LIFETIME_MS - 60_000L)!!)
        val before = rawStored()
        try {
            Fox.obtainServers(context, forceFresh = false) { emptyList() }
            fail("empty list accepted")
        } catch (expected: FoxError) {
            // expected
        }
        assertEquals(before, rawStored())
    }

    @Test fun failedForcedRefreshKeepsTheListInUse() {
        Fox.obtainServers(context, forceFresh = false) { list }
        try {
            Fox.obtainServers(context, forceFresh = true) { throw FoxError("simulated refusal") }
        } catch (_: FoxError) {
        }
        assertEquals("nl.mozilla.example", Fox.servers.single().host)
    }

    @Test fun concurrentCallersShareOneFetch() {
        val calls = AtomicInteger(0)
        val slow: () -> List<FoxLocation> = { calls.incrementAndGet(); Thread.sleep(150); fresh }
        val threads = List(4) { Thread { Fox.obtainServers(context, forceFresh = false, fetch = slow) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, calls.get())
        assertTrue(Fox.servers.isNotEmpty())
        assertNull(FoxServerCache.decode("", now))
    }
}
