package com.gozar

import android.app.Application
import android.content.Context
import android.view.View
import com.google.android.material.button.MaterialButton
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
        val verified = AtomicBoolean()
        val tokenRequests = AtomicInteger()
        val passRequests = AtomicInteger()
        val logins = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loginEntered = CountDownLatch(1)
        val loginRelease = CountDownLatch(1)
        val linkEntered = CountDownLatch(1)
        val linkRelease = CountDownLatch(1)
        val signupVerifyEntered = CountDownLatch(1)
        val signupVerifyRelease = CountDownLatch(1)
        val registerEntered = CountDownLatch(1)
        val registerRelease = CountDownLatch(1)
        val tokenEntered = CountDownLatch(1)
        val tokenRelease = CountDownLatch(1)
        val passEntered = CountDownLatch(1)
        val passRelease = CountDownLatch(1)
        @Volatile var blockVerification = false
        @Volatile var blockLogin = false
        @Volatile var blockLink = false
        @Volatile var blockSignupVerification = false
        @Volatile var blockRegister = false
        @Volatile var blockTokens = false
        @Volatile var blockPass = false
        @Volatile var failFirstVerification = false
        @Volatile var failFirstToken = true
        @Volatile var needsCode = true
        @Volatile var byLink = false

        override fun register(email: String, password: String): FoxSession {
            registerEntered.countDown()
            if (blockRegister) check(registerRelease.await(10, TimeUnit.SECONDS))
            return FoxSession("fake-uid", "fake-session", needsCode, false)
        }
        override fun signIn(email: String, password: String): FoxSession {
            logins.incrementAndGet()
            loginEntered.countDown()
            if (blockLogin) check(loginRelease.await(10, TimeUnit.SECONDS))
            return FoxSession("fake-uid", "fake-session", needsCode, byLink)
        }
        override fun emailVerified(sessionToken: String): Boolean {
            linkEntered.countDown()
            if (blockLink) check(linkRelease.await(10, TimeUnit.SECONDS))
            return true
        }
        override fun verifyEmail(sessionToken: String, uid: String, code: String) {
            signupVerifyEntered.countDown()
            if (blockSignupVerification) check(signupVerifyRelease.await(10, TimeUnit.SECONDS))
        }
        override fun resendSignupCode(sessionToken: String) = Unit
        override fun verifySession(sessionToken: String, code: String) {
            val call = verifies.incrementAndGet()
            entered.countDown()
            if (blockVerification) check(release.await(10, TimeUnit.SECONDS))
            if (failFirstVerification && call == 1) throw FoxError("Synthetic code rejected")
            if (!verified.compareAndSet(false, true)) throw FoxError("Synthetic code already used")
        }
        override fun tokens(sessionToken: String): FoxTokens {
            tokenEntered.countDown()
            if (blockTokens) check(tokenRelease.await(10, TimeUnit.SECONDS))
            if (tokenRequests.incrementAndGet() == 1 && failFirstToken) {
                throw FoxError("Synthetic OAuth failure", status = 406)
            }
            return FoxTokens("fake-access", "fake-refresh", 3600)
        }
        override fun pass(access: String): FoxPass {
            passRequests.incrementAndGet()
            passEntered.countDown()
            if (blockPass) check(passRelease.await(10, TimeUnit.SECONDS))
            return FoxPass("fake-pass", 100, 50, "")
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Fox.busy = false
        Fox.cancelLogin()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
        provider = FakeProvider()
        Fox.loginProvider = provider
        Fox.loginTransport = FoxDirectLoginTransport
    }

    @After
    fun tearDown() {
        provider.release.countDown()
        provider.tokenRelease.countDown()
        provider.loginRelease.countDown()
        provider.linkRelease.countDown()
        provider.signupVerifyRelease.countDown()
        provider.registerRelease.countDown()
        provider.passRelease.countDown()
        await { !Fox.busy }
        Fox.cancelLogin()
        Fox.loginProvider = MozillaLoginProvider
        Fox.loginTransport = FoxTorRuntime
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
    fun freshSignInClearsCachedFailureAndStartsANewNetworkAttempt() {
        provider.needsCode = false
        provider.failFirstToken = true
        Fox.signIn(context, "fake@example.invalid", "fake-password")
        await { !Fox.busy && Fox.message == "Synthetic OAuth failure" }
        assertTrue(Fox.needsStep())
        provider.failFirstToken = false
        provider.blockLogin = true
        Fox.signIn(context, "fake@example.invalid", "fake-password")
        await { provider.logins.get() == 2 }
        assertEquals("", Fox.message)
        assertFalse(Fox.needsStep())
        assertTrue(Fox.busy)
        provider.loginRelease.countDown()
        await { !Fox.busy }
        assertEquals(2, provider.logins.get())
        assertTrue(Fox.signedIn(context))
    }

    @Test
    fun afterOauthFailureRetryDoesNotReverifyTheConsumedCode() {
        start()
        Fox.submitCode(context, "123456")
        await { !Fox.busy && Fox.message == "Synthetic OAuth failure" }
        assertEquals(1, provider.verifies.get())
        assertFalse(Fox.signedIn(context))
        assertTrue(Fox.canRetryLogin())
        assertFalse(Fox.needsCode())

        Fox.submitCode(context, "123456")
        await { !Fox.busy && (Fox.signedIn(context) || Fox.message == "Synthetic code already used") }
        assertEquals("a verified session must not consume the same code again", 1, provider.verifies.get())
        assertEquals(2, provider.tokenRequests.get())
        assertTrue(Fox.signedIn(context))
    }

    @Test
    fun signOutWhileOAuthIsPendingCannotRestoreTheSignedOutAccount() {
        provider.needsCode = false
        provider.failFirstToken = false
        provider.blockTokens = true
        Fox.signIn(context, "fake@example.invalid", "fake-password")
        assertTrue("token request did not start", provider.tokenEntered.await(5, TimeUnit.SECONDS))
        Fox.signOut(context)
        assertFalse(Fox.signedIn(context))
        provider.tokenRelease.countDown()
        await { !Fox.busy }
        assertFalse("late login must not recreate a signed-out account", Fox.signedIn(context))
        assertFalse("late login must not expose a pending retry", Fox.needsStep())
        assertEquals("revoked OAuth response must not start pass acquisition", 0, provider.passRequests.get())
    }

    @Test
    fun signOutDuringSignInDiscardsTheLateSessionAndVerificationPrompt() {
        provider.blockLogin = true
        Fox.signIn(context, "fake@example.invalid", "fake-password")
        assertTrue(provider.loginEntered.await(5, TimeUnit.SECONDS))
        Fox.signOut(context)
        provider.loginRelease.countDown()
        await { !Fox.busy }
        assertFalse(Fox.needsStep())
        assertFalse(Fox.signedIn(context))
        assertEquals(0, provider.tokenRequests.get())
    }

    @Test
    fun signOutDuringRegistrationDiscardsTheLateSession() {
        provider.blockRegister = true
        Fox.signUp(context, "fake@example.invalid", "fake-password")
        assertTrue(provider.registerEntered.await(5, TimeUnit.SECONDS))
        Fox.signOut(context)
        provider.registerRelease.countDown()
        await { !Fox.busy }
        assertFalse(Fox.needsStep())
        assertFalse(Fox.signedIn(context))
        assertEquals(0, provider.tokenRequests.get())
    }

    @Test
    fun signOutDuringLinkConfirmationCannotPublishAReauthenticatedSession() {
        provider.byLink = true
        provider.blockLink = true
        Fox.signIn(context, "fake@example.invalid", "fake-password")
        await { !Fox.busy && Fox.isLinkStep() }
        Fox.confirmLink(context)
        assertTrue(provider.linkEntered.await(5, TimeUnit.SECONDS))
        Fox.signOut(context)
        provider.linkRelease.countDown()
        await { !Fox.busy }
        assertFalse(Fox.needsStep())
        assertFalse(Fox.signedIn(context))
        assertEquals(0, provider.tokenRequests.get())
    }

    @Test
    fun signOutDuringSignupVerificationCannotPublishAnAccount() {
        provider.blockSignupVerification = true
        Fox.signUp(context, "fake@example.invalid", "fake-password")
        await { !Fox.busy && Fox.needsCode() }
        Fox.submitCode(context, "123456")
        assertTrue(provider.signupVerifyEntered.await(5, TimeUnit.SECONDS))
        Fox.signOut(context)
        provider.signupVerifyRelease.countDown()
        await { !Fox.busy }
        assertFalse(Fox.needsStep())
        assertFalse(Fox.signedIn(context))
        assertEquals(0, provider.tokenRequests.get())
    }

    @Test
    fun signOutDuringVerificationDoesNotPublishTheVerifiedSession() {
        provider.blockVerification = true
        start()
        Fox.submitCode(context, "123456")
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS))
        Fox.signOut(context)
        provider.release.countDown()
        await { !Fox.busy }
        assertFalse(Fox.needsStep())
        assertFalse(Fox.canRetryLogin())
        assertFalse(Fox.signedIn(context))
        assertEquals(0, provider.tokenRequests.get())
    }

    @Test
    fun signOutDuringRetryCannotRestoreAConsumedSession() {
        start()
        Fox.submitCode(context, "123456")
        await { !Fox.busy && Fox.canRetryLogin() && Fox.message == "Synthetic OAuth failure" }
        provider = FakeProvider().apply {
            failFirstToken = false
            blockTokens = true
        }
        Fox.loginProvider = provider
        Fox.retryLogin(context)
        assertTrue(provider.tokenEntered.await(5, TimeUnit.SECONDS))
        Fox.signOut(context)
        provider.tokenRelease.countDown()
        await { !Fox.busy }
        assertFalse(Fox.needsStep())
        assertFalse(Fox.signedIn(context))
        assertEquals(0, provider.passRequests.get())
    }

    @Test
    fun signOutDuringPassAcquisitionDoesNotPublishLateCredentials() {
        provider.needsCode = false
        provider.failFirstToken = false
        provider.blockPass = true
        Fox.signIn(context, "fake@example.invalid", "fake-password")
        assertTrue(provider.passEntered.await(5, TimeUnit.SECONDS))
        Fox.signOut(context)
        provider.passRelease.countDown()
        await { !Fox.busy }
        assertFalse(Fox.needsStep())
        assertFalse(Fox.signedIn(context))
    }

    @Test
    fun newLoginCanSucceedAfterTheRevokedWorkerFinishes() {
        provider.needsCode = false
        provider.failFirstToken = false
        provider.blockTokens = true
        Fox.signIn(context, "fake@example.invalid", "fake-password")
        assertTrue(provider.tokenEntered.await(5, TimeUnit.SECONDS))
        Fox.signOut(context)
        provider.tokenRelease.countDown()
        await { !Fox.busy }
        provider = FakeProvider().apply { needsCode = false; failFirstToken = false }
        Fox.loginProvider = provider
        Fox.signIn(context, "new-fake@example.invalid", "fake-password")
        await { !Fox.busy && Fox.signedIn(context) }
        assertEquals("new-fake@example.invalid", FoxStore.email(context))
    }

    @Test
    fun fiftyLogoutRacesDoNotRestoreLateAccounts() {
        repeat(50) { index ->
            provider = FakeProvider().apply {
                needsCode = false
                failFirstToken = false
                blockTokens = true
            }
            Fox.loginProvider = provider
            Fox.signIn(context, "fake@example.invalid", "fake-password")
            assertTrue("token request did not start at $index", provider.tokenEntered.await(5, TimeUnit.SECONDS))
            Fox.signOut(context)
            provider.tokenRelease.countDown()
            await { !Fox.busy }
            assertFalse("late account at $index", Fox.signedIn(context))
            assertFalse("late pending state at $index", Fox.needsStep())
            assertEquals("late pass request at $index", 0, provider.passRequests.get())
        }
    }

    @Test
    fun alreadyVerifiedSessionCanRetryOauthWithoutCode() {
        provider.needsCode = false
        Fox.signIn(context, "fake@example.invalid", "fake-password")
        await { !Fox.busy && Fox.canRetryLogin() && Fox.message == "Synthetic OAuth failure" }
        assertFalse(Fox.needsCode())
        Fox.retryLogin(context)
        await { !Fox.busy && Fox.signedIn(context) }
        assertEquals(0, provider.verifies.get())
        assertEquals(2, provider.tokenRequests.get())
    }

    @Test
    fun rejectedCodeDoesNotMarkTheSessionVerified() {
        provider.failFirstVerification = true
        provider.failFirstToken = false
        start()
        Fox.submitCode(context, "incorrect")
        await { !Fox.busy && Fox.message == "Synthetic code rejected" }
        assertFalse(Fox.canRetryLogin())
        assertTrue(Fox.needsCode())
        assertEquals(0, provider.tokenRequests.get())
        Fox.submitCode(context, "correct")
        await { !Fox.busy && Fox.signedIn(context) }
        assertEquals(2, provider.verifies.get())
        assertEquals(1, provider.tokenRequests.get())
    }

    @Test
    fun retryScreenHidesTheCodeFieldAndRetriesWithoutAnotherCode() {
        start()
        Fox.submitCode(context, "123456")
        await { !Fox.busy && Fox.canRetryLogin() && Fox.message == "Synthetic OAuth failure" }
        val controller = Robolectric.buildActivity(LoginActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.loginCodeBox).visibility)
            val primary = activity.findViewById<MaterialButton>(R.id.loginPrimary)
            assertEquals(activity.getString(R.string.login_retry_action), primary.text.toString())
            primary.performClick()
            await { !Fox.busy && Fox.signedIn(context) }
            assertEquals(1, provider.verifies.get())
            assertEquals(2, provider.tokenRequests.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun cancellingVerifiedAttemptClearsItsRetryState() {
        start()
        Fox.submitCode(context, "123456")
        await { !Fox.busy && Fox.canRetryLogin() }
        Fox.cancelLogin()
        assertFalse(Fox.needsStep())
        assertFalse(Fox.canRetryLogin())
        Fox.retryLogin(context)
        assertEquals(1, provider.tokenRequests.get())
    }

    @Test
    fun fiftyOverlappingSubmissionsVerifyOnlyOncePerAttempt() {
        repeat(50) { index ->
            Fox.cancelLogin()
            provider = FakeProvider().apply { blockVerification = true }
            Fox.loginProvider = provider
            start()
            Fox.submitCode(context, "123456")
            assertTrue("first verification did not start at $index", provider.entered.await(5, TimeUnit.SECONDS))
            Fox.submitCode(context, "123456")
            provider.release.countDown()
            await { !Fox.busy && Fox.canRetryLogin() }
            assertEquals("duplicate verification at $index", 1, provider.verifies.get())
            assertEquals("unexpected token request at $index", 1, provider.tokenRequests.get())
        }
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
