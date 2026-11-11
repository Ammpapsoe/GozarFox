package com.gozar

import android.accounts.AccountManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AccountUiTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
        Fox.busy = false
        Fox.cancelLogin()
    }

    @After
    fun clean() {
        Fox.busy = false
        Fox.cancelLogin()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun save(email: String, uid: String) {
        FoxStore.saveAccount(context, email, uid, FoxTokens("access-$uid", "refresh-$uid", 3600), FoxPass("pass-$uid", 100, 90, ""))
    }

    private fun render(activity: LoginActivity) {
        val method = LoginActivity::class.java.getDeclaredMethod("render")
        method.isAccessible = true
        method.invoke(activity)
    }

    @Test
    fun addModeStaysOnLoginUntilSuccessAndCancelPreservesTheFirstAccount() {
        save("first@example.test", "first")
        val intent = Intent(context, LoginActivity::class.java).putExtra(LoginActivity.ADD_ACCOUNT, true)
        val controller = Robolectric.buildActivity(LoginActivity::class.java, intent).setup()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginCancelAdd).visibility)
        activity.findViewById<View>(R.id.loginCancelAdd).performClick()
        assertTrue(activity.isFinishing)
        assertEquals("first@example.test", FoxStore.email(context))
        assertEquals(1, FoxStore.accounts(context).size)
        controller.pause().stop().destroy()
    }

    @Test
    fun successfulSecondAccountFinishesAddModeWithoutDeletingTheFirst() {
        save("first@example.test", "first")
        val intent = Intent(context, LoginActivity::class.java).putExtra(LoginActivity.ADD_ACCOUNT, true)
        val controller = Robolectric.buildActivity(LoginActivity::class.java, intent).setup()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        save("second@example.test", "second")
        render(activity)
        assertTrue(activity.isFinishing)
        assertEquals("second@example.test", FoxStore.email(context))
        assertEquals(setOf("first@example.test", "second@example.test"), FoxStore.accounts(context).map { it.email }.toSet())
        controller.pause().stop().destroy()
    }

    @Test
    fun savedSessionButtonIsSeparateFromTheSystemAccountPicker() {
        save("first@example.test", "first")
        save("second@example.test", "second")
        FoxStore.clear(context)
        val controller = Robolectric.buildActivity(LoginActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginSavedAccounts).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginSystemAccounts).visibility)
        activity.findViewById<View>(R.id.loginSystemAccounts).performClick()
        val launched = shadowOf(activity).nextStartedActivityForResult ?: error("system picker was not launched")
        assertNotNull(launched.intent.component)
        assertEquals("android", launched.intent.component?.packageName)
        assertEquals("", activity.findViewById<EditText>(R.id.loginMail).text.toString())
        controller.pause().stop().destroy()
    }

    @Test
    fun accountSheetMarksTheActiveAccountAndOffersSwitchAndAdd() {
        val controller = Robolectric.buildActivity(LoginActivity::class.java).setup()
        val activity = controller.get()
        var selected = ""
        var adding = false
        val sheet = AccountSheet(activity, { account, _ -> selected = account.id }, { adding = true }).show(listOf(
            SavedFoxAccount("one", "first@example.test", true),
            SavedFoxAccount("two", "second@example.test", false)
        ))
        val rows = sheet.findViewById<android.widget.LinearLayout>(R.id.accountRows)!!
        assertEquals(2, rows.childCount)
        val current = rows.getChildAt(0)
        val other = rows.getChildAt(1)
        assertEquals(View.VISIBLE, current.findViewById<View>(R.id.accountCheck).visibility)
        assertEquals(View.INVISIBLE, other.findViewById<View>(R.id.accountCheck).visibility)
        current.performClick()
        assertEquals("", selected)
        other.performClick()
        assertEquals("two", selected)
        sheet.findViewById<View>(R.id.accountAdd)!!.performClick()
        assertTrue(adding)
        sheet.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun systemSelectionAcceptsOnlyEmailNamesAndHandlesMissingResults() {
        assertEquals("system@example.test", LoginActivity.systemEmail(Intent().putExtra(AccountManager.KEY_ACCOUNT_NAME, "  system@example.test  ")))
        assertNull(LoginActivity.systemEmail(Intent().putExtra(AccountManager.KEY_ACCOUNT_NAME, "not-an-email")))
        assertNull(LoginActivity.systemEmail(Intent().putExtra("email", "saved@example.test")))
        assertNull(LoginActivity.systemEmail(null))
    }
}
