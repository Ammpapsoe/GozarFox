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
        val monitor = instrumentation.addMonitor(BrowserFlowActivity::class.java.name, null, false)
        val scenario = androidx.test.core.app.ActivityScenario.launch(LoginActivity::class.java)
        scenario.onActivity { activity ->
            activity.findViewById<android.widget.EditText>(R.id.loginMail).setText(argument("email"))
            // The login screen has two entry paths: the native form is the
            // default tab, and only the web tab hands over to Mozilla's own
            // page. This test is about the web path, so select it explicitly;
            // clicking the primary action on the form tab signs in natively
            // and no WebView is ever created.
            activity.findViewById<android.view.View>(R.id.loginTabWeb).performClick()
            activity.findViewById<android.widget.Button>(R.id.loginPrimary).performClick()
        }
        val auth = instrumentation.waitForMonitorWithTimeout(monitor, 30_000) as? BrowserFlowActivity
            ?: throw AssertionError("WEBVIEW_DID_NOT_OPEN (login screen did not hand over to the web tab)")
        val deadline = System.currentTimeMillis() + 150_000
        var previousState = ""
        while (System.currentTimeMillis() < deadline && !Fox.signedIn(context)) {
            val state = evaluate(auth)
            val marker = when {
                state.contains("EMAIL_") -> state.removePrefix("\"").removeSuffix("\"").take(160)
                state.contains("PASSWORD_") -> state.removePrefix("\"").removeSuffix("\"").take(160)
                state.contains("CODE_REQUIRED") -> "CODE_REQUIRED"
                state.contains("CAPTCHA_REQUIRED") -> "CAPTCHA_REQUIRED"
                state.contains("MOZILLA_ERROR") -> "MOZILLA_ERROR"
                state.contains("SUBMIT_PENDING") -> "SUBMIT_PENDING"
                state.contains("WAIT_INPUTS_") -> state.removePrefix("\"").removeSuffix("\"").take(160)
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
            if (state.contains("NO_SUBMIT") || state.contains("SUBMIT_DISABLED")) {
                throw AssertionError("MOZILLA_SUBMIT_UNAVAILABLE at " + marker + " dom=" + describe(auth))
            }
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

    private fun evaluate(activity: BrowserFlowActivity): String {
        val script = """
            (() => {
              const visible = e => !!e && !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
              const fill = (e, value) => {
                const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                setter.call(e, value);
                e.dispatchEvent(new Event('input', {bubbles:true}));
                e.dispatchEvent(new Event('change', {bubbles:true}));
              };
              // Mozilla's pages are server-rendered and only become interactive
              // after hydration; a submit button that is disabled right now is
              // usually enabled a moment later, so poll instead of giving up.
              const submit = e => {
                const form = e.closest('form');
                const root = form || document;
                const pick = () => [...root.querySelectorAll('button[type=submit],input[type=submit]')].find(visible) ||
                  [...root.querySelectorAll('button')].find(visible) ||
                  [...root.querySelectorAll('[role=button]')].find(visible);
                const usable = b => b && !b.disabled && b.getAttribute('aria-disabled') !== 'true';
                let tries = 0;
                const attempt = () => {
                  const button = pick();
                  if (usable(button)) { button.click(); return; }
                  if (tries++ < 12) { setTimeout(attempt, 250); return; }
                  if (form) { form.requestSubmit(); return; }
                  const password = document.querySelector('input[type=password]');
                  const field = password || document.querySelector('input[type=email],input[name=email]');
                  if (field && field.form) field.form.requestSubmit();
                };
                setTimeout(attempt, 150);
                return pick() ? 'SUBMIT_PENDING' : (form ? 'SUBMIT_PENDING' : 'NO_SUBMIT');
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

    private fun describe(activity: BrowserFlowActivity): String {
        val script = """
            (() => {
              const visible = e => !!e && !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
              const buttons = [...document.querySelectorAll('button,input[type=submit],[role=button]')]
                .filter(visible)
                .map(b => (b.tagName + ' ' + (b.type || '') + ' disabled=' + !!b.disabled +
                  ' aria=' + (b.getAttribute('aria-disabled') || '-') +
                  ' text="' + (b.innerText || b.value || '').trim().slice(0, 40) + '"'));
              const inputs = [...document.querySelectorAll('input')].filter(visible)
                .map(i => 'input type=' + (i.type || 'text') + ' name=' + (i.name || '-'));
              return 'path=' + location.pathname + ' buttons=[' + buttons.join(' | ') +
                '] inputs=[' + inputs.join(' | ') + ']';
            })();
        """.trimIndent()
        val latch = CountDownLatch(1)
        var snapshot = ""
        activity.runOnUiThread {
            activity.findViewById<WebView>(R.id.webSignInPage).evaluateJavascript(script) { value ->
                snapshot = value.orEmpty()
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return snapshot
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
