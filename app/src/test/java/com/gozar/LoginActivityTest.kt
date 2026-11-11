package com.gozar

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LoginActivityTest {
    private lateinit var controller: ActivityController<LoginActivity>
    private lateinit var activity: LoginActivity

    @Before
    fun start() {
        Fox.busy = false
        Fox.message = ""
        Fox.stage = ""
        FoxBridgeStore.clear()
        controller = Robolectric.buildActivity(LoginActivity::class.java).setup()
        activity = controller.get()
        FoxStore.clear(activity)
    }

    @After
    fun stop() {
        Fox.busy = false
        Fox.message = ""
        Fox.stage = ""
        FoxBridgeStore.clear()
        FoxStore.clear(activity)
        controller.pause().stop().destroy()
    }

    private fun render() {
        val method = LoginActivity::class.java.getDeclaredMethod("render")
        method.isAccessible = true
        method.invoke(activity)
    }

    private fun awaitIdle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (Fox.busy && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(Fox.busy)
    }

    @Test
    fun busyKeepsFieldsLocksControlsAndShowsOnlySecureConnectionStatus() {
        val mail = activity.findViewById<EditText>(R.id.loginMail)
        val secret = activity.findViewById<EditText>(R.id.loginSecret)
        val primary = activity.findViewById<MaterialButton>(R.id.loginPrimary)
        val progress = activity.findViewById<CircularProgressIndicator>(R.id.loginProgress)
        mail.setText("test@example.invalid")
        secret.setText("sample-password")
        assertTrue(secret.transformationMethod is PasswordTransformationMethod)

        Fox.busy = true
        Fox.stage = "Direct Tor · Tor bootstrap 42%"
        Fox.message = "hidden while working"
        render()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginMailBox).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginSecretBox).visibility)
        assertEquals("test@example.invalid", mail.text.toString())
        assertEquals("sample-password", secret.text.toString())
        assertEquals("", primary.text.toString())
        assertEquals("Direct Tor · Tor bootstrap 42%", activity.findViewById<TextView>(R.id.loginSubtitle).text.toString())
        assertEquals("Direct Tor · Tor bootstrap 42%", primary.contentDescription.toString())
        assertEquals(View.VISIBLE, progress.visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginStop).visibility)
        assertTrue(activity.findViewById<View>(R.id.loginStop).isEnabled)
        assertEquals(activity.getString(R.string.login_wait_hint), activity.findViewById<TextView>(R.id.loginWaitHint).text.toString())
        assertSame(primary.parent, progress.parent)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.loginError).visibility)
        for (id in intArrayOf(R.id.loginPrimary, R.id.loginMail, R.id.loginSecret, R.id.loginConfirm,
            R.id.loginCode, R.id.loginBridge, R.id.loginBridgeHelp, R.id.loginReveal, R.id.loginForgot, R.id.loginSwitch, R.id.loginLanguage)) {
            assertFalse("control $id must be disabled", activity.findViewById<View>(id).isEnabled)
        }

        Fox.busy = false
        Fox.message = "The request failed"
        render()
        assertEquals("test@example.invalid", mail.text.toString())
        assertEquals("sample-password", secret.text.toString())
        assertEquals(View.GONE, progress.visibility)
        assertEquals(activity.getString(R.string.login_action), primary.text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginError).visibility)
        for (id in intArrayOf(R.id.loginPrimary, R.id.loginMail, R.id.loginSecret, R.id.loginConfirm,
            R.id.loginCode, R.id.loginBridge, R.id.loginBridgeHelp, R.id.loginReveal, R.id.loginForgot, R.id.loginSwitch, R.id.loginLanguage)) {
            assertTrue("control $id must be enabled", activity.findViewById<View>(id).isEnabled)
        }
    }

    @Test
    fun signupFieldsRemainVisibleAndLockedWhileBusy() {
        activity.findViewById<MaterialButton>(R.id.loginSwitch).performClick()
        val mail = activity.findViewById<EditText>(R.id.loginMail)
        val secret = activity.findViewById<EditText>(R.id.loginSecret)
        val confirm = activity.findViewById<EditText>(R.id.loginConfirm)
        mail.setText("test@example.invalid")
        secret.setText("sample-password")
        confirm.setText("sample-password")
        Fox.busy = true
        render()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginConfirmBox).visibility)
        assertEquals("test@example.invalid", mail.text.toString())
        assertEquals("sample-password", secret.text.toString())
        assertEquals("sample-password", confirm.text.toString())
        assertFalse(confirm.isEnabled)
        assertFalse(activity.findViewById<View>(R.id.loginSwitch).isEnabled)
    }

    @Test
    fun busyFieldsSurviveActivityRecreation() {
        activity.findViewById<EditText>(R.id.loginMail).setText("test@example.invalid")
        activity.findViewById<EditText>(R.id.loginSecret).setText("sample-password")
        Fox.busy = true
        render()
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        controller = Robolectric.buildActivity(LoginActivity::class.java)
            .create(state).start().restoreInstanceState(state).resume().visible()
        activity = controller.get()
        assertEquals("test@example.invalid", activity.findViewById<EditText>(R.id.loginMail).text.toString())
        assertEquals("sample-password", activity.findViewById<EditText>(R.id.loginSecret).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginProgress).visibility)
        assertFalse(activity.findViewById<View>(R.id.loginSecret).isEnabled)
    }

    @Test
    fun languageSelectionIsOnLoginAndSettingsButNotTheHomeHeader() {
        val inflater = LayoutInflater.from(activity)
        val home = inflater.inflate(R.layout.fragment_fox, null) as ViewGroup
        val settings = inflater.inflate(R.layout.activity_settings, null)
        assertNotNull(activity.findViewById<View>(R.id.loginLanguage))
        assertNotNull(settings.findViewById<View>(R.id.rowLang))
        assertNotNull(home.findViewById<View>(R.id.foxAccountButton))
        val header = home.getChildAt(0) as ViewGroup
        val descriptions = (0 until header.childCount).map { header.getChildAt(it).contentDescription?.toString() }
        assertFalse(descriptions.contains(activity.getString(R.string.language)))
    }

    @Test
    fun passwordIsClearedOnlyAfterSuccessfulSignIn() {
        val secret = activity.findViewById<EditText>(R.id.loginSecret)
        secret.setText("sample-password")
        activity.getSharedPreferences("fox", Context.MODE_PRIVATE).edit()
            .putString("email", "test@example.invalid")
            .putString("refresh", "fake-token")
            .commit()
        Fox.busy = true
        render()
        assertEquals("sample-password", secret.text.toString())
        assertFalse(activity.isFinishing)
        Fox.busy = false
        render()
        assertEquals("", secret.text.toString())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun stopButtonCancelsAuthenticationAndStopsOwnedTransport() {
        val original = Fox.loginTransport
        var stopped = false
        Fox.loginTransport = object : FoxLoginTransport {
            override fun run(context: Context, body: () -> Unit) = body()
            override fun isActive(): Boolean = true
            override fun stop(context: Context) {
                stopped = true
            }
        }
        try {
            Fox.busy = true
            render()
            activity.findViewById<MaterialButton>(R.id.loginStop).performClick()
            assertTrue(stopped)
            assertFalse(Fox.needsStep())
            assertFalse(Fox.canRetryLogin())
        } finally {
            Fox.loginTransport = original
            Fox.busy = false
        }
    }

    @Test
    fun manualBridgeIsNotSavedInViewStateOrPreferences() {
        val bridge = "obfs4 8.8.8.8:443 ${"A".repeat(40)} cert=${"A".repeat(70)} iat-mode=0"
        activity.findViewById<EditText>(R.id.loginBridge).setText(bridge)
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        controller = Robolectric.buildActivity(LoginActivity::class.java)
            .create(state).start().restoreInstanceState(state).resume().visible()
        activity = controller.get()
        assertEquals("", activity.findViewById<EditText>(R.id.loginBridge).text.toString())
        assertTrue(activity.getSharedPreferences("fox", Context.MODE_PRIVATE).all.none { (key, value) ->
            key.contains("bridge", true) || value == bridge
        })
    }

    @Test
    fun bridgeHelpShowsTheOfficialTelegramBot() {
        activity.findViewById<View>(com.google.android.material.R.id.text_input_end_icon).performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        val message = dialog.findViewById<TextView>(android.R.id.message)?.text?.toString().orEmpty()
        assertTrue(message.contains("@GetBridgesBot"))
        assertTrue(message.contains(activity.getString(R.string.login_bridge)))
        dialog.dismiss()
    }

    @Test
    fun exactReportedObfs4BridgeIsAcceptedByLoginAndKeptOnlyInRam() {
        val reported = "obfs4 217.182.93.225:62782 D4FC51091A3D23F098354F660F2D5B17B2F3432C cert=UDjYbRlpv/GEc19L1dPssudaWQE83uxgUKxtUDQ7COumf1ERpup7+mcOvshJdO4Luh/Udw iat-mode=0"
        val original = Fox.loginTransport
        var received: String? = null
        Fox.loginTransport = object : FoxLoginTransport {
            override fun run(context: Context, body: () -> Unit) {
                received = FoxBridgeStore.manualCandidate()?.line
            }
        }
        try {
            activity.findViewById<EditText>(R.id.loginMail).setText("test@example.invalid")
            activity.findViewById<EditText>(R.id.loginSecret).setText("sample-password")
            activity.findViewById<EditText>(R.id.loginBridge).setText("  " + reported.replace(" ", "\t") + "  ")
            activity.findViewById<MaterialButton>(R.id.loginPrimary).performClick()
            awaitIdle()
            assertEquals(reported, received)
            assertEquals(reported, FoxBridgeStore.manualCandidate()?.line)
            assertTrue(activity.findViewById<TextView>(R.id.loginError).text.toString() != activity.getString(R.string.login_bridge_invalid))
            assertTrue(activity.getSharedPreferences("fox", Context.MODE_PRIVATE).all.none { (key, value) ->
                key.contains("bridge", true) || value == reported
            })
        } finally {
            Fox.loginTransport = original
            Fox.cancelLogin(activity)
        }
    }

    @Test
    fun logViewerIsAvailableAndDoesNotExposeBridgeMaterial() {
        val reported = "obfs4 217.182.93.225:62782 D4FC51091A3D23F098354F660F2D5B17B2F3432C cert=UDjYbRlpv/GEc19L1dPssudaWQE83uxgUKxtUDQ7COumf1ERpup7+mcOvshJdO4Luh/Udw iat-mode=0"
        FoxTorLog.clear()
        FoxTorLog.record("ATTEMPT_FAILED", reported)
        activity.findViewById<MaterialButton>(R.id.loginLogs).performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        val texts = mutableListOf<String>()
        fun collect(view: android.view.View) {
            if (view is TextView) texts += view.text.toString()
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(dialog.window.decorView)
        val rendered = texts.joinToString("\n")
        assertTrue(rendered.contains("[bridge redacted]"))
        assertFalse(rendered.contains("217.182.93.225"))
        assertFalse(rendered.contains("D4FC51091A3D23F098354F660F2D5B17B2F3432C"))
        assertFalse(rendered.contains("UDjYbRlpv"))
        dialog.dismiss()
        FoxTorLog.clear()
    }

    @Test
    fun repeatedStopAndLogoutAlwaysReleaseOwnedTransport() {
        val original = Fox.loginTransport
        var stops = 0
        Fox.loginTransport = object : FoxLoginTransport {
            override fun run(context: Context, body: () -> Unit) = body()
            override fun isActive(): Boolean = false
            override fun stop(context: Context) {
                stops++
            }
        }
        try {
            Fox.stopLogin(activity)
            Fox.stopLogin(activity)
            Fox.signOut(activity)
            Fox.signOut(activity)
            assertEquals(4, stops)
            assertFalse(Fox.signedIn(activity))
            assertFalse(Fox.needsStep())
        } finally {
            Fox.loginTransport = original
        }
    }

    @Test
    fun materialLoginRetainsFirefoxArtworkAndSeparateLargeStop() {
        assertNotNull(activity.findViewById<View>(R.id.loginFox))
        Fox.busy = true
        render()
        val primary = activity.findViewById<MaterialButton>(R.id.loginPrimary)
        val stop = activity.findViewById<MaterialButton>(R.id.loginStop)
        assertEquals(View.VISIBLE, stop.visibility)
        assertTrue(stop.layoutParams.height > primary.layoutParams.height * 0.9)
        assertSame(primary.parent.parent, stop.parent)
    }

    @Test
    fun injectedBridgeIsRejectedBeforeAuthenticationStarts() {
        activity.findViewById<EditText>(R.id.loginMail).setText("test@example.invalid")
        activity.findViewById<EditText>(R.id.loginSecret).setText("sample-password")
        activity.findViewById<EditText>(R.id.loginBridge).setText("obfs4 127.0.0.1:443 invalid\nSocksPort 0.0.0.0:9050")
        activity.findViewById<MaterialButton>(R.id.loginPrimary).performClick()
        assertFalse(Fox.busy)
        assertEquals(activity.getString(R.string.login_bridge_invalid), activity.findViewById<TextView>(R.id.loginError).text.toString())
        assertTrue(FoxBridgeStore.manual.isEmpty())
    }
}
