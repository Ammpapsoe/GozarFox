package com.gozar

import android.app.Application
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
class FoxVerifiedLoginTest {
    private lateinit var context: Context
    private lateinit var provider: FakeProvider

    private class FakeProvider : FoxLoginProvider {
        val verifies = AtomicInteger()
        val tokenRequests = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var blockVerification = false
        @Volatile var failFirstToken = true

        override fun signIn(email: String, password: String) = FoxSession("fake-uid", "fake-session", true, false)
        override fun verifySession(sessionToken: String, code: String) {
            val call = verifies.incrementAndGet()
            entered.countDown()
            if (blockVerification) check(release.await(10, TimeUnit.SECONDS))
            if (call > 1) throw FoxError("Synthetic code already used")
        }
        override fun tokens(sessionToken: String): FoxTokens {
            if (tokenRequests.incrementAndGet() == 1 && failFirstToken) {
                throw FoxError("Synthetic OAuth failure", status = 406)
            }
            return FoxTokens("fake-access", "fake-refresh", 3600)
        }
        override fun pass(access: String) = FoxPass("fake-pass", 100, 50, "")
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Fox.busy = false
        Fox.cancelLogin()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
        provider = FakeProvider()
        Fox.loginProvider = provider
    }

    @After
    fun tearDown() {
        provider.release.countDown()
        await { !Fox.busy }
        Fox.cancelLogin()
        Fox.loginProvider = MozillaLoginProvider
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("timed out waiting for synthetic login", condition())
    }

    private fun start() {
        Fox.signIn(context, "fake@example.invalid", "fake-password")
        await { !Fox.busy && Fox.needsCode() }
    }

    @Test
    fun afterOauthFailureRetryDoesNotReverifyTheConsumedCode() {
        start()
        Fox.submitCode(context, "123456")
        await { !Fox.busy && Fox.message == "Synthetic OAuth failure" }
        assertEquals(1, provider.verifies.get())
        assertFalse(Fox.signedIn(context))

        Fox.submitCode(context, "123456")
        await { !Fox.busy && (Fox.signedIn(context) || Fox.message == "Synthetic code already used") }
        assertEquals("a verified session must not consume the same code again", 1, provider.verifies.get())
        assertEquals(2, provider.tokenRequests.get())
        assertTrue(Fox.signedIn(context))
    }

    @Test
    fun simultaneousSubmissionsCannotVerifyTheSameSessionTwice() {
        provider.blockVerification = true
        provider.failFirstToken = false
        start()
        Fox.submitCode(context, "123456")
        assertTrue("first verification did not start", provider.entered.await(5, TimeUnit.SECONDS))
        Fox.submitCode(context, "123456")
        // Give an unguarded second worker a chance to enter before releasing the first.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
        while (provider.verifies.get() < 2 && System.nanoTime() < deadline) Thread.sleep(5)
        provider.release.countDown()
        await { !Fox.busy }
        assertEquals("one login attempt must have only one in-flight verification", 1, provider.verifies.get())
        assertTrue(Fox.signedIn(context))
    }
}
