package com.gozar

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MozillaAuthActivityTest {
    @Test
    fun webViewUsesFirefoxCookiesAndValidatedOAuthCallback() {
        val intent = Intent(Intent.ACTION_VIEW).putExtra(MozillaAuthActivity.EXTRA_CREATE, false)
        val controller = Robolectric.buildActivity(MozillaAuthActivity::class.java, intent).setup()
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.mozillaWebView)
        assertTrue(webView.settings.javaScriptEnabled)
        assertTrue(CookieManager.getInstance().acceptCookie())
        assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(webView))
        assertEquals(FoxNet.UA, webView.settings.userAgentString)
        val field = MozillaAuthActivity::class.java.getDeclaredField("expectedState")
        field.isAccessible = true
        val state = field.get(activity) as String
        val capture = MozillaAuthActivity::class.java.getDeclaredMethod("capture", String::class.java)
        capture.isAccessible = true
        assertFalse(capture.invoke(activity, "https://example.invalid/callback?code=abcdef123456&state=$state") as Boolean)
        assertTrue(capture.invoke(activity, "https://vpn.mozilla.org/oauth/success?code=abcdef123456&state=$state") as Boolean)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals("abcdef123456", shadowOf(activity).resultIntent.getStringExtra(MozillaAuthActivity.EXTRA_CODE))
        controller.pause().stop().destroy()
    }
}
