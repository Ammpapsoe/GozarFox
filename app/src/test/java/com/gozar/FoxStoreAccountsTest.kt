package com.gozar

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FoxStoreAccountsTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun clean() {
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun save(email: String, uid: String, suffix: String, remaining: Long) {
        FoxStore.saveAccount(
            context, email, uid, FoxTokens("access-$suffix", "refresh-$suffix", 3600),
            FoxPass("pass-$suffix", 1000, remaining, "reset-$suffix")
        )
    }

    @Test
    fun legacyAccountMigratesAndSwitchingRestoresSeparateSessions() {
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit()
            .putString("email", "first@example.test")
            .putString("uid", "first")
            .putString("access", "legacy-access")
            .putString("refresh", "legacy-refresh")
            .putString("pass", "legacy-pass")
            .putLong("limit", 1000)
            .putLong("remaining", 800)
            .putString("selected", "DE")
            .commit()
        assertEquals(listOf("first@example.test"), FoxStore.accounts(context).map { it.email })
        save("second@example.test", "second", "second", 300)
        val accounts = FoxStore.accounts(context)
        assertEquals(2, accounts.size)
        assertEquals("second@example.test", accounts.first { it.active }.email)
        assertTrue(FoxStore.switchAccount(context, accounts.first { it.email.startsWith("first") }.id))
        assertEquals("legacy-refresh", FoxStore.refreshToken(context))
        assertEquals("legacy-pass", FoxStore.pass(context))
        assertEquals(800L, FoxStore.remaining(context))
        assertEquals("DE", FoxStore.selected(context))
        assertTrue(FoxStore.switchAccount(context, accounts.first { it.email.startsWith("second") }.id))
        assertEquals("refresh-second", FoxStore.refreshToken(context))
        assertEquals("pass-second", FoxStore.pass(context))
        assertEquals(300L, FoxStore.remaining(context))
        assertEquals(2, FoxStore.accounts(context).size)
    }

    @Test
    fun staleRefreshAndUsageCannotCrossSwitchesOrReauthentication() {
        save("first@example.test", "first", "first", 800)
        val first = FoxStore.credentials(context)
        save("second@example.test", "second", "second", 300)
        assertFalse(FoxStore.saveAccess(context, FoxTokens("late", "late", 3600), first.id, first.revision))
        assertFalse(FoxStore.saveUsage(context, FoxPass("late", 10, 1, ""), first.id, first.revision))
        assertEquals("refresh-second", FoxStore.refreshToken(context))
        assertEquals(300L, FoxStore.remaining(context))
        assertTrue(FoxStore.switchAccount(context, first.id))
        assertFalse(FoxStore.saveAccess(context, FoxTokens("late", "late", 3600), first.id, first.revision))
        val beforeReauth = FoxStore.credentials(context)
        save("first@example.test", "first", "new", 700)
        assertFalse(FoxStore.saveUsage(context, FoxPass("late", 10, 1, ""), beforeReauth.id, beforeReauth.revision))
        assertEquals("refresh-new", FoxStore.refreshToken(context))
        assertEquals(700L, FoxStore.remaining(context))
        assertEquals(2, FoxStore.accounts(context).size)
    }

    @Test
    fun duplicateEmailMigratesToOneEntryAndLogoutOnlyRemovesActiveAccount() {
        save("first@example.test", "", "old", 800)
        save("FIRST@example.test", "first-uid", "new", 700)
        assertEquals(1, FoxStore.accounts(context).size)
        save("second@example.test", "second", "second", 300)
        FoxStore.clear(context)
        assertEquals("", FoxStore.email(context))
        assertEquals("", FoxStore.refreshToken(context))
        assertEquals(listOf("FIRST@example.test"), FoxStore.accounts(context).map { it.email })
        assertTrue(FoxStore.switchAccount(context, FoxStore.accounts(context).single().id))
        assertEquals("refresh-new", FoxStore.refreshToken(context))
        assertFalse(context.getSharedPreferences("fox", Context.MODE_PRIVATE).all.keys.any { it.contains("password") })
    }

    @Test
    fun proxyPassExpiryUsesBoundedRenewalAndFallback() {
        val now = 1_800_000_000_000L
        val payload = android.util.Base64.encodeToString(
            "{\"exp\":1800000180}".toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        val expiry = FoxStore.passExpiry("header.$payload.signature", now)
        assertEquals(now + 180_000L, expiry)
        assertEquals(90_000L, FoxStore.renewalDelay(expiry, now))
        assertEquals(300_000L, FoxStore.renewalDelay(now + 600_000L, now))
        assertEquals(15_000L, FoxStore.renewalDelay(now - 1, now))
        assertEquals(240_000L, FoxStore.passExpiry("malformed", now) - now)
        assertEquals(120_000L, FoxStore.renewalDelay(FoxStore.passExpiry("malformed", now), now))
    }

    @Test
    fun unknownSwitchDoesNotAlterTheActiveSession() {
        save("first@example.test", "first", "first", 800)
        val revision = FoxStore.credentials(context).revision
        assertFalse(FoxStore.switchAccount(context, "not-a-saved-account"))
        assertEquals("refresh-first", FoxStore.refreshToken(context))
        assertEquals(revision, FoxStore.credentials(context).revision)
    }
}
