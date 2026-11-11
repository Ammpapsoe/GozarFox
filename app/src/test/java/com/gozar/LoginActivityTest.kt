package com.gozar

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
        controller = Robolectric.buildActivity(LoginActivity::class.java).setup()
        activity = controller.get()
        FoxStore.clear(activity)
    }

    @After
    fun stop() {
        Fox.busy = false
        Fox.message = ""
        Fox.stage = ""
        FoxStore.clear(activity)
        controller.pause().stop().destroy()
    }

    private fun render() {
        val method = LoginActivity::class.java.getDeclaredMethod("render")
        method.isAccessible = true
        method.invoke(activity)
    }

    @Test
    fun busyKeepsFieldsLocksControlsAndShowsOnlyTheButtonSpinner() {
        val mail = activity.findViewById<EditText>(R.id.loginMail)
        val secret = activity.findViewById<EditText>(R.id.loginSecret)
        val primary = activity.findViewById<MaterialButton>(R.id.loginPrimary)
        val progress = activity.findViewById<CircularProgressIndicator>(R.id.loginProgress)
        mail.setText("test@example.invalid")
        secret.setText("sample-password")
        assertTrue(secret.transformationMethod is PasswordTransformationMethod)

        Fox.busy = true
        Fox.stage = "request status that should not appear"
        Fox.message = "hidden while working"
        render()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginMailBox).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginSecretBox).visibility)
        assertEquals("test@example.invalid", mail.text.toString())
        assertEquals("sample-password", secret.text.toString())
        assertEquals("", primary.text.toString())
        assertEquals(View.VISIBLE, progress.visibility)
        assertSame(primary.parent, progress.parent)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.loginError).visibility)
        for (id in intArrayOf(R.id.loginPrimary, R.id.loginMail, R.id.loginSecret, R.id.loginConfirm,
            R.id.loginCode, R.id.loginReveal, R.id.loginForgot, R.id.loginSwitch, R.id.loginLanguage)) {
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
            R.id.loginCode, R.id.loginReveal, R.id.loginForgot, R.id.loginSwitch, R.id.loginLanguage)) {
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
}
