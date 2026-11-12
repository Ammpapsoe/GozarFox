package com.gozar

import android.content.Intent
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoxAccountInstrumentedTest {
    @Test(timeout = 240_000)
    fun realLoginReceivesPassThroughWebView() {
        complete(false)
    }

    @Test(timeout = 240_000)
    fun realSignupReceivesCodeThroughWebView() {
        complete(true)
    }

    private fun complete(create: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val monitor = instrumentation.addMonitor(MozillaWebViewActivity::class.java.name, null, false)
        val scenario = androidx.test.core.app.ActivityScenario.launch(LoginActivity::class.java)
        scenario.onActivity { activity ->
            activity.findViewById<android.widget.EditText>(R.id.loginMail).setText(argument("email"))
            activity.findViewById<android.widget.Button>(R.id.loginPrimary).performClick()
        }
        val auth = instrumentation.waitForMonitorWithTimeout(monitor, 20_000) as? MozillaWebViewActivity
            ?: throw AssertionError("WEBVIEW_DID_NOT_OPEN")
        val deadline = System.currentTimeMillis() + 150_000
        var previousState = ""
        while (System.currentTimeMillis() < deadline && !Fox.signedIn(context)) {
            val state = evaluate(auth)
            val marker = when {
                state.contains("EMAIL_") -> state.removePrefix("\"").removeSuffix("\"").take(100)
                state.contains("PASSWORD_") -> state.removePrefix("\"").removeSuffix("\"").take(100)
                state.contains("CODE_REQUIRED") -> "CODE_REQUIRED"
                state.contains("CAPTCHA_REQUIRED") -> "CAPTCHA_REQUIRED"
                state.contains("MOZILLA_ERROR") -> "MOZILLA_ERROR"
                state.contains("WAIT_INPUTS_") -> state.removePrefix("\"").removeSuffix("\"").take(100)
                state.contains("WAIT") -> "WAIT"
                else -> "EMPTY"
            }
            if (marker != previousState) {
                report("WEBVIEW_STATE=$marker")
                previousState = marker
            }
            if (auth.isFinishing && !Fox.busy) throw AssertionError("WEBVIEW_CLOSED_BEFORE_ACCOUNT_COMPLETION")
            if (state.contains("CODE_REQUIRED")) {
                if (!create) throw AssertionError("LOGIN_CODE_REQUIRED")
                report("STATE=SIGNUP_CODE_REQUIRED")
                scenario.close()
                return
            }
            if (state.contains("CAPTCHA_REQUIRED")) throw AssertionError("MOZILLA_CAPTCHA_REQUIRED")
            if (state.contains("NO_SUBMIT") || state.contains("SUBMIT_DISABLED")) throw AssertionError("MOZILLA_SUBMIT_UNAVAILABLE")
            if (state.contains("MOZILLA_ERROR")) throw AssertionError("MOZILLA_WEBVIEW_ERROR")
            if (Fox.message.isNotEmpty() && !Fox.busy && Fox.message != "code") throw AssertionError("ACCOUNT_FLOW_FAILED")
            Thread.sleep(750)
        }
        assertTrue("ACCOUNT_NOT_SIGNED_IN", Fox.signedIn(context))
        assertTrue("OAUTH_REFRESH_EMPTY", FoxStore.refreshToken(context).isNotEmpty())
        assertTrue("LOGIN_PASS_EMPTY", FoxStore.pass(context).isNotEmpty())
        val device = UiDevice.getInstance(instrumentation)
        repeat(20) {
            val allow = listOf("OK", "Allow", "Continue").firstNotNullOfOrNull { label ->
                device.findObject(By.text(label))
            }
            if (allow != null) {
                allow.click()
                return@repeat
            }
            if (FoxVpnService.isAlive()) return@repeat
            Thread.sleep(500)
        }
        val tunnelDeadline = System.currentTimeMillis() + 30_000
        while (!FoxVpnService.isAlive() && System.currentTimeMillis() < tunnelDeadline) Thread.sleep(250)
        assertTrue("TUNNEL_DID_NOT_START", FoxVpnService.isAlive())
        report("STATE=LOGIN_PASS_OK")
        report("STATE=TUNNEL_STARTED")
        scenario.close()
    }

    private fun evaluate(activity: MozillaWebViewActivity): String {
        val script = """
            (() => {
              const visible = e => !!e && !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
              const fill = (e, value) => {
                const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                setter.call(e, value);
                e.dispatchEvent(new Event('input', {bubbles:true}));
                e.dispatchEvent(new Event('change', {bubbles:true}));
              };
              const submit = e => {
                const form = e.closest('form');
                const root = form || document;
                const button = [...root.querySelectorAll('button[type=submit]')].find(visible) ||
                  [...root.querySelectorAll('button')].find(visible);
                if (button) {
                  if (button.disabled || button.getAttribute('aria-disabled') === 'true') return 'SUBMIT_DISABLED';
                  setTimeout(() => button.click(), 100);
                  return 'SUBMIT_CLICKED';
                }
                if (form) {
                  setTimeout(() => form.requestSubmit(), 100);
                  return 'SUBMIT_REQUESTED';
                }
                return 'NO_SUBMIT';
              };
              const text = document.body ? document.body.innerText : '';
              const captchaInput = [...document.querySelectorAll('#capInput,input[name=answer]')].find(visible);
              const frame = [...document.querySelectorAll('iframe')].find(e => visible(e) && /captcha|challenge/i.test(e.src + ' ' + e.title));
              if (captchaInput || frame || /characters seen in the image|audio captcha/i.test(text)) return 'CAPTCHA_REQUIRED';
              const passwords = [...document.querySelectorAll('input[type=password]')].filter(visible);
              if (passwords.length) {
                passwords.forEach(e => fill(e, ${JSONObject.quote(argument("password"))}));
                return 'PASSWORD_' + submit(passwords[0]);
              }
              const code = [...document.querySelectorAll('input[name=code],input[inputmode=numeric],input[autocomplete=one-time-code]')].find(visible);
              if (code) return 'CODE_REQUIRED';
              const email = [...document.querySelectorAll('input[type=email],input[name=email],input[name=login],input[autocomplete=username],#email')].find(visible) ||
                [...document.querySelectorAll('input')].find(e => visible(e) &&
                  !['hidden','password','checkbox','radio','submit','button'].includes(e.type) &&
                  e.inputMode !== 'numeric' && e.autocomplete !== 'one-time-code');
              if (email) {
                fill(email, ${JSONObject.quote(argument("email"))});
                return 'EMAIL_' + submit(email);
              }
              if (/not supported|try again later|unexpected error|something went wrong|invalid or expired|blocked/i.test(text)) return 'MOZILLA_ERROR';
              return 'WAIT_INPUTS_' + document.querySelectorAll('input').length + '_PATH_' + location.pathname;
            })();
        """.trimIndent()
        val latch = CountDownLatch(1)
        var state = ""
        activity.runOnUiThread {
            activity.findViewById<WebView>(R.id.webSignInPage).evaluateJavascript(script) { value ->
                state = value.orEmpty()
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return state
    }

    private fun report(marker: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("STATE", marker) })
    }

    private fun argument(name: String): String {
        val value = InstrumentationRegistry.getArguments().getString(name).orEmpty()
        check(value.isNotEmpty()) { "Missing required instrumentation argument: $name" }
        return value
    }
}
