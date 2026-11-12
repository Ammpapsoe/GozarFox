package com.gozar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FoxTokenManagerTest {

    private var clock = 1_000_000L
    private val now: () -> Long = { clock }

    private class MemoryVault(
        private val now: () -> Long,
        initial: FoxTokenManager.Entry?
    ) : FoxTokenManager.Vault {
        @Volatile var entry: FoxTokenManager.Entry? = initial
        @Volatile var acceptWrites = true
        val writes = AtomicInteger()
        val forgotten = AtomicInteger()

        override fun read(): FoxTokenManager.Entry? = entry

        override fun write(previous: FoxTokenManager.Entry, tokens: FoxTokens): Boolean {
            if (!acceptWrites) return false
            val current = entry
            // Optimistic concurrency: the account may have been replaced or
            // removed while the refresh was in flight.
            if (current == null || current.label != previous.label || current.revision != previous.revision) return false
            writes.incrementAndGet()
            entry = FoxTokenManager.Entry(
                previous.label,
                previous.revision + 1,
                tokens.access,
                tokens.refresh,
                now() + tokens.expiresIn * 1000L
            )
            return true
        }

        override fun forget(previous: FoxTokenManager.Entry) {
            forgotten.incrementAndGet()
            entry = null
        }
    }

    private fun entry(expiresAt: Long, access: String = "old-access") =
        FoxTokenManager.Entry("account-1", 7L, access, "refresh-1", expiresAt)

    private fun manager(
        vault: FoxTokenManager.Vault,
        exchange: (String) -> FoxTokens = { FoxTokens("new-access", "new-refresh", 3_600) }
    ) = FoxTokenManager(vault, exchange, now)

    private fun failure(block: () -> Unit): FoxError = try {
        block()
        fail("expected a failure")
        throw IllegalStateException("unreachable")
    } catch (error: FoxError) {
        error
    }

    @Test
    fun aTokenThatIsStillGoodIsUsedWithoutAnExchange() {
        val exchanges = AtomicInteger()
        val vault = MemoryVault(now, entry(clock + 600_000L))
        val grant = manager(vault) { exchanges.incrementAndGet(); FoxTokens("new-access", "new-refresh", 3_600) }.grant()
        assertEquals(0, exchanges.get())
        assertEquals(0, vault.writes.get())
        assertEquals("old-access", grant.access)
        assertEquals("account-1", grant.label)
        assertEquals(7L, grant.revision)
    }

    @Test
    fun anExpiredTokenIsRefreshedAndStored() {
        val vault = MemoryVault(now, entry(clock - 1L))
        val grant = manager(vault).grant()
        assertEquals("new-access", grant.access)
        assertEquals(1, vault.writes.get())
        val stored = vault.entry
        assertNotNull(stored)
        assertEquals("new-refresh", stored?.refreshToken)
        assertEquals(clock + 3_600_000L, stored?.expiresAt)
    }

    @Test
    fun aTokenInsideTheSkewIsRefreshedBeforeItExpires() {
        val justInside = MemoryVault(now, entry(clock + 60_000L))
        assertEquals("new-access", manager(justInside).grant().access)

        val justOutside = MemoryVault(now, entry(clock + 180_000L))
        assertEquals("old-access", manager(justOutside).grant().access)
        assertEquals(0, justOutside.writes.get())
    }

    @Test
    fun aTokenWithNoKnownExpiryIsTreatedAsExpired() {
        val vault = MemoryVault(now, entry(0L))
        assertEquals("new-access", manager(vault).grant().access)
    }

    @Test
    fun concurrentCallersShareASingleExchange() {
        val exchanges = AtomicInteger()
        val vault = MemoryVault(now, entry(clock - 1L))
        val manager = FoxTokenManager(vault, {
            exchanges.incrementAndGet()
            // Hold the lock long enough that every other caller has already
            // arrived and is waiting on it.
            Thread.sleep(40)
            FoxTokens("new-access", "new-refresh", 3_600)
        }, now)
        val callers = 16
        val pool = Executors.newFixedThreadPool(callers)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(callers)
        val accesses = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val labels = java.util.concurrent.ConcurrentLinkedQueue<String>()
        try {
            repeat(callers) {
                pool.execute {
                    try {
                        start.await()
                        val grant = manager.grant()
                        accesses.add(grant.access)
                        labels.add(grant.label)
                    } catch (_: Exception) {
                        accesses.add("failed")
                    } finally {
                        finished.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(finished.await(20, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        // Mozilla rotates the refresh token, so a second exchange could
        // invalidate the first one and log the user out.
        assertEquals(1, exchanges.get())
        assertEquals(1, vault.writes.get())
        assertEquals(callers, accesses.size)
        assertTrue(accesses.all { it == "new-access" })
        assertTrue(labels.all { it == "account-1" })
    }

    @Test
    fun aRefreshTokenMozillaRefusesIsForgotten() {
        for (dead in listOf(
            FoxError("invalid token", errno = 108),
            FoxError("invalid parameter", errno = 109),
            FoxError("bad request", status = 400),
            FoxError("unauthorized", status = 401)
        )) {
            val vault = MemoryVault(now, entry(clock - 1L))
            val error = failure { manager(vault) { throw dead }.grant() }
            assertEquals(dead.errno, error.errno)
            assertEquals(1, vault.forgotten.get())
            assertNull(vault.entry)
        }
    }

    @Test
    fun aTransientFailureKeepsTheCredential() {
        val vault = MemoryVault(now, entry(clock - 1L))
        failure { manager(vault) { throw FoxError("upstream", status = 503) }.grant() }
        assertEquals(0, vault.forgotten.get())
        assertNotNull(vault.entry)
        assertEquals(0, vault.writes.get())
    }

    @Test
    fun noStoredCredentialMeansSignInAgain() {
        val vault = MemoryVault(now, null)
        assertEquals("Sign in again", failure { manager(vault).grant() }.message)

        val blank = MemoryVault(now, FoxTokenManager.Entry("", 0L, "a", "r", clock - 1L))
        assertEquals("Sign in again", failure { manager(blank).grant() }.message)

        val noRefresh = MemoryVault(now, FoxTokenManager.Entry("account-1", 1L, "a", "", clock - 1L))
        assertEquals("Sign in again", failure { manager(noRefresh).grant() }.message)
    }

    @Test
    fun anAccountChangedUnderneathTheRefreshIsNotOverwritten() {
        val vault = MemoryVault(now, entry(clock - 1L))
        vault.acceptWrites = false
        assertEquals("Account changed", failure { manager(vault).grant() }.message)
    }

    @Test
    fun anIncompleteTokenResponseIsRefused() {
        val vault = MemoryVault(now, entry(clock - 1L))
        assertEquals(
            "Mozilla returned an incomplete token",
            failure { manager(vault) { FoxTokens("", "new-refresh", 3_600) }.grant() }.message
        )
        assertEquals(0, vault.writes.get())
    }
}
