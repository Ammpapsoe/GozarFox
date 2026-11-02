package com.gozar

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SecretBoxTest {

    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
        context.noBackupFilesDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun clean() {
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun sealedValuesNeverExposeThePlainToken() {
        val sealed = SecretBox.seal(context, "refresh-token-value")
        assertTrue(SecretBox.isSealed(sealed))
        assertFalse(sealed.contains("refresh-token-value"))
        assertEquals("refresh-token-value", SecretBox.open(context, sealed))
    }

    @Test
    fun sealingIsRandomisedPerCall() {
        val first = SecretBox.seal(context, "same-input")
        val second = SecretBox.seal(context, "same-input")
        assertNotEquals(first, second)
        assertEquals(SecretBox.open(context, first), SecretBox.open(context, second))
    }

    @Test
    fun tamperedCiphertextIsRejectedInsteadOfDecoded() {
        val sealed = SecretBox.seal(context, "session")
        val broken = sealed.dropLast(4) + "AAAA"
        assertEquals("", SecretBox.open(context, broken))
    }

    @Test
    fun legacyPlaintextStaysReadableWhileNewWritesAreEncrypted() {
        assertEquals("legacy", SecretBox.open(context, "legacy"))
        assertFalse(SecretBox.isSealed("legacy"))
    }

    @Test
    fun storedCredentialsAreEncryptedAtRest() {
        FoxStore.saveAccount(
            context,
            "person@example.test",
            "uid-1",
            FoxTokens("access-value", "refresh-value", 3600),
            FoxPass("pass-value", 100, 90, "")
        )
        val prefs = context.getSharedPreferences("fox", Context.MODE_PRIVATE)
        val raw = prefs.all.values.joinToString(" ") { it.toString() }
        assertFalse(raw.contains("refresh-value"))
        assertFalse(raw.contains("access-value"))
        assertFalse(raw.contains("pass-value"))
        assertEquals("refresh-value", FoxStore.refreshToken(context))
        assertEquals("access-value", FoxStore.access(context))
        assertEquals("pass-value", FoxStore.pass(context))
        assertEquals(listOf("person@example.test"), FoxStore.accounts(context).map { it.email })
    }

    @Test
    fun existingPlaintextSessionsMigrateWithoutSigningOut() {
        val prefs = context.getSharedPreferences("fox", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("email", "old@example.test")
            .putString("uid", "uid-old")
            .putString("access", "plain-access")
            .putString("refresh", "plain-refresh")
            .putString("pass", "plain-pass")
            .putLong("expires_at", System.currentTimeMillis() + 60_000L)
            .commit()
        assertEquals("plain-refresh", FoxStore.refreshToken(context))
        assertEquals("old@example.test", FoxStore.email(context))
        FoxStore.saveUsage(context, FoxPass("fresh-pass", 100, 80, ""), FoxStore.activeId(context), 0L)
        val stored = prefs.getString("accounts", "").orEmpty()
        assertTrue(stored.isEmpty() || SecretBox.isSealed(stored))
        assertEquals("fresh-pass", FoxStore.pass(context))
    }
}
