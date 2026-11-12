package com.gozar

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.FileOutputStream
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screen evidence for the sign-in flow, without credentials.
 *
 * This does not test Mozilla and makes no claim about it. From a CI runner the
 * sign-in page is normally refused at the edge, so what this can prove is the
 * part that is ours: the three screens render, the WebView is configured
 * without crashing, and the platform really does support the two WebMessage
 * features the credential path depends on. The screenshots plus the capability
 * report are the artifact; the navigation trail is read from logcat afterwards.
 */
@RunWith(AndroidJUnit4::class)
class LoginScreensInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val out: File = File(instrumentation.targetContext.getExternalFilesDir(null), "screens")
        .apply { mkdirs() }

    private val notes = mutableListOf<String>()

    @Test
    fun captureSignInScreens() {
        try {
            reportCapabilities()
            capture("01-home", MainActivity::class.java, listOf("first" to 12_000L))
            capture("02-login", LoginActivity::class.java, listOf("first" to 8_000L))
            // Two shots of one instance: the overlay while the page loads, and
            // whatever the screen settled into once the network answered.
            capture(
                "03-webview",
                MozillaWebViewActivity::class.java,
                listOf("loading" to 4_000L, "settled" to 40_000L)
            )
        } finally {
            writeNotes()
        }
    }

    /**
     * Answers the one question a desktop cannot: does the WebView on a real
     * system image support a message listener and document-start injection?
     * If either is false the activity refuses to show a form that could never
     * complete, and says so on screen.
     */
    private fun reportCapabilities() {
        val context = instrumentation.targetContext
        val packageInfo = WebViewCompat.getCurrentWebViewPackage(context)
        val lines = listOf(
            "package=${context.packageName}",
            "versionName=${BuildConfig.VERSION_NAME}",
            "versionCode=${BuildConfig.VERSION_CODE}",
            "deviceSdk=${Build.VERSION.SDK_INT}",
            "webViewPackage=${packageInfo?.packageName ?: "unknown"}",
            "webViewVersion=${packageInfo?.versionName ?: "unknown"}",
            "WEB_MESSAGE_LISTENER=${WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)}",
            "DOCUMENT_START_SCRIPT=${WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)}",
            "SAFE_BROWSING_ENABLE=${WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)}"
        )
        File(out, "00-capabilities.txt").writeText(lines.joinToString("\n") + "\n")
        lines.forEach { note(it) }
    }

    private fun capture(name: String, activity: Class<out Activity>, shots: List<Pair<String, Long>>) {
        val scenario = try {
            ActivityScenario.launch(activity)
        } catch (error: Throwable) {
            note("$name launch-failed ${error.javaClass.simpleName}")
            return
        }
        try {
            for ((label, settle) in shots) {
                Thread.sleep(settle)
                screenshot("$name-$label")
                scenario.onActivity { current ->
                    note("$name-$label finishing=${current.isFinishing} destroyed=${current.isDestroyed}")
                }
            }
        } catch (error: Throwable) {
            note("$name interrupted ${error.javaClass.simpleName}")
        } finally {
            try {
                scenario.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun screenshot(name: String) {
        val bitmap = try {
            instrumentation.uiAutomation.takeScreenshot()
        } catch (error: Throwable) {
            note("$name capture-failed ${error.javaClass.simpleName}")
            return
        }
        val file = File(out, "$name.png")
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            note("$name saved ${file.length()} bytes")
        } catch (error: Throwable) {
            note("$name write-failed ${error.javaClass.simpleName}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun note(line: String) {
        synchronized(notes) { notes.add(line) }
        // Printed so the instrumentation output carries the trail even if the
        // external directory cannot be pulled.
        println("GOZAR_UI $line")
    }

    private fun writeNotes() {
        val text = synchronized(notes) { notes.joinToString("\n") }
        try {
            File(out, "99-notes.txt").writeText(text + "\n")
        } catch (_: Throwable) {
        }
    }
}
