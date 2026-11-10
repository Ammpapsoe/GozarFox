package com.gozar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoxAccountInstrumentedTest {
    private lateinit var app: Context

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        FoxLink.stop()
        FoxStore.clear(app)
        Fox.cancelLogin()
        FoxTorRuntime.clean(app)
    }

    @After
    fun tearDown() {
        FoxLink.stop()
        FoxVpnService.stop(app)
        FoxStore.clear(app)
        Fox.cancelLogin()
        FoxTorRuntime.clean(app)
    }

    @Test(timeout = 1_200_000)
    fun realLoginTwiceAndOpenDirectTunnel() {
        val email = argument("email")
        val password = argument("password")
        repeat(2) { attempt ->
            Fox.signIn(app, email, password)
            completeAuthentication("", "LOGIN")
            assertTrue("Login attempt ${attempt + 1} did not persist an authenticated account", Fox.signedIn(app))
            assertPassAndOpenTunnel("LOGIN", attempt + 1)
            FoxLink.stop()
            FoxStore.clear(app)
            Fox.cancelLogin()
            assertFalse(Fox.signedIn(app))
        }
    }

    @Test(timeout = 900_000)
    fun realSignupReceivesCodeAndOpensTunnel() {
        val email = argument("email")
        val password = argument("password")
        val code = InstrumentationRegistry.getArguments().getString("code").orEmpty()
        Fox.signUp(app, email, password)
        completeAuthentication(code, "SIGNUP")
        assertTrue(Fox.signedIn(app))
        assertPassAndOpenTunnel("SIGNUP", 1)
    }

    private fun argument(name: String): String {
        val value = InstrumentationRegistry.getArguments().getString(name).orEmpty()
        check(value.isNotEmpty()) { "Missing required instrumentation argument: $name" }
        return value
    }

    private fun completeAuthentication(code: String, label: String) {
        awaitIdle(label)
        if (Fox.needsCode()) {
            if (code.isEmpty()) throw AssertionError(label + "_CODE_REQUIRED")
            Fox.submitCode(app, code)
            awaitIdle(label)
        }
        if (Fox.isLinkStep()) throw AssertionError(label + "_EMAIL_LINK_REQUIRED")
        if (!Fox.signedIn(app)) {
            throw AssertionError(label + "_FAILED: " + Fox.message.ifBlank { "unknown account failure" })
        }
    }

    private fun awaitIdle(label: String) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(7)
        while (Fox.busy && System.nanoTime() < deadline) Thread.sleep(250)
        if (Fox.busy) throw AssertionError(label + "_TIMED_OUT_WAITING_FOR_ACCOUNT_FLOW")
    }

    private fun assertPassAndOpenTunnel(label: String, attempt: Int) {
        assertTrue(label + " pass is empty", FoxStore.pass(app).isNotEmpty())
        FoxLink.start(app)
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4)
        while (!FoxLink.connected && System.nanoTime() < deadline) {
            if (!FoxLink.connecting && FoxLink.error.isNotBlank()) {
                throw AssertionError(label + "_TUNNEL_FAILED_ATTEMPT_" + attempt + ": " + FoxLink.error)
            }
            Thread.sleep(250)
        }
        if (!FoxLink.connected) {
            throw AssertionError(label + "_TUNNEL_TIMEOUT_ATTEMPT_" + attempt + ": " + FoxLink.error)
        }
        assertTrue(FoxLink.connected)
        FoxLink.stop()
        assertFalse(FoxLink.connected)
    }
}
