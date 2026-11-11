package com.gozar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoxAccountInstrumentedTest {

    @Test(timeout = 120_000)
    fun realLoginReceivesPassThroughAppsScript() {
        val session = FoxApi.signIn(argument("email"), argument("password"))
        if (session.needsCode) throw AssertionError("LOGIN_CODE_REQUIRED")
        val tokens = FoxApi.tokens(session.token)
        val pass = FoxApi.pass(tokens.access)
        assertTrue("LOGIN_PASS_EMPTY", pass.token.isNotEmpty())
        println("STATE=LOGIN_PASS_OK")
    }

    @Test(timeout = 120_000)
    fun realSignupReceivesCodeThroughAppsScript() {
        val session = FoxApi.register(argument("email"), argument("password"))
        assertTrue("SIGNUP_CODE_NOT_REQUIRED", session.needsCode)
        assertTrue("SIGNUP_SESSION_EMPTY", session.token.isNotEmpty())
        println("STATE=SIGNUP_CODE_REQUIRED")
    }

    private fun argument(name: String): String {
        val value = InstrumentationRegistry.getArguments().getString(name).orEmpty()
        check(value.isNotEmpty()) { "Missing required instrumentation argument: $name" }
        return value
    }
}
