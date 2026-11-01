package com.gozar

import android.app.Application
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h720dp")
class SettingsScreenTest {
    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("store", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
        Store.setLang(context, "en")
        FoxStore.saveAccount(context, "person@example.test", "test-id", FoxTokens("access", "refresh", 3600), FoxPass("pass", 100, 80, ""))
        Fox.servers = listOf(FoxLocation("NL", "Netherlands", "Amsterdam", "127.0.0.1", 9, false))
        Fox.busy = true
    }

    @After
    fun clean() {
        Fox.busy = false
        Fox.servers = emptyList()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("store", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun openSettings(activity: MainActivity) {
        activity.findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_settings
        activity.supportFragmentManager.executePendingTransactions()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        Fox.busy = false
    }

    @Test
    fun securityIsASeparateSectionAndShowsUnverifiedLockdown() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsOverview).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsSecurity).visibility)
            activity.findViewById<View>(R.id.sectionSecurityButton).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsSecurity).visibility)
            assertEquals(activity.getString(R.string.kill_unknown), activity.findViewById<TextView>(R.id.tvKillSwitch).text.toString())
            assertFalse(activity.findViewById<MaterialSwitch>(R.id.swAds).isChecked)
            activity.findViewById<View>(R.id.rowAds).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(Store.blockAds(activity))
            assertTrue(activity.findViewById<MaterialSwitch>(R.id.swAds).isChecked)
            activity.findViewById<View>(R.id.btnBack).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsOverview).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun signingOutFromTheAccountSectionReturnsToLogin() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            activity.findViewById<View>(R.id.sectionAccountButton).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsAccount).visibility)
            activity.findViewById<View>(R.id.btnSignOut).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val next = shadowOf(activity).nextStartedActivity
            assertEquals(LoginActivity::class.java.name, next.component?.className)
            assertFalse(Fox.signedIn(activity))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
