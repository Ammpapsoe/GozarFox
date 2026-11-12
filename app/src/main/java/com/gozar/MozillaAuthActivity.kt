package com.gozar

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MozillaAuthActivity : BaseActivity() {
    companion object {
        const val EXTRA_CREATE = "com.gozar.MOZILLA_CREATE"
        const val EXTRA_EMAIL = "com.gozar.MOZILLA_EMAIL"
        const val EXTRA_CODE = "com.gozar.MOZILLA_CODE"
        const val EXTRA_VERIFIER = "com.gozar.MOZILLA_VERIFIER"
        const val EXTRA_ERROR = "com.gozar.MOZILLA_ERROR"
        private const val STATE_URL = "mozilla_url"
        private const val STATE_VERIFIER = "mozilla_verifier"
    }

    private lateinit var progress: ProgressBar
    private lateinit var error: TextView
    private lateinit var verifier: String
    private var target = ""
    private var launched = false
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mozilla_auth)
        progress = findViewById(R.id.mozillaProgress)
        error = findViewById(R.id.mozillaError)
        findViewById<ExtendedFloatingActionButton>(R.id.mozillaLogs).setOnClickListener { MozillaDiagnostics.show(this) }
        if (savedInstanceState == null && intent.data == null) MozillaDiagnostics.clear()
        verifier = savedInstanceState?.getString(STATE_VERIFIER).orEmpty()
        target = savedInstanceState?.getString(STATE_URL).orEmpty()
        val restoring = verifier.isNotEmpty() && target.isNotEmpty()
        if (!restoring && intent.data != null) {
            recordSafeLog("OAUTH callback rejected")
            finishWithError("Mozilla authentication session could not be restored")
            return
        }
        if (!restoring) {
            val request = FoxOAuth.customTabRequest(intent.getStringExtra(EXTRA_EMAIL).orEmpty())
            verifier = request.verifier
            target = request.url
        }
        recordNavigation("authorization", target)
        val callback = intent.data?.toString()
        if (callback != null) {
            recordNavigation("deep-link", callback)
            capture(callback)
        } else if (savedInstanceState == null) {
            launchCustomTab()
        } else {
            recordSafeLog("CUSTOM_TAB restored")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val raw = intent.data?.toString()
        recordSafeLog("CUSTOM_TAB intent received")
        if (raw == null) {
            recordSafeLog("OAUTH callback rejected")
            finishWithError("Mozilla returned an empty authentication response")
            return
        }
        recordNavigation("deep-link", raw)
        capture(raw)
    }

    override fun onResume() {
        super.onResume()
        if (launched && !completed) recordSafeLog("CUSTOM_TAB resumed")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_URL, target)
        outState.putString(STATE_VERIFIER, verifier)
        super.onSaveInstanceState(outState)
    }

    private fun launchCustomTab() {
        progress.visibility = View.VISIBLE
        error.visibility = View.GONE
        recordSafeLog("CUSTOM_TAB launch")
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
                .launchUrl(this, Uri.parse(target))
            launched = true
            recordSafeLog("CUSTOM_TAB launched")
        } catch (e: Exception) {
            recordSafeLog("CUSTOM_TAB error ${e::class.java.simpleName}")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                launched = true
                recordSafeLog("CUSTOM_TAB browser fallback")
            } catch (fallback: Exception) {
                recordSafeLog("CUSTOM_TAB fallback error ${fallback::class.java.simpleName}")
                recordSafeLog("CUSTOM_TAB fallback unavailable")
                finishWithError("No browser is available for Mozilla authentication")
            }
        }
    }

    private fun recordNavigation(kind: String, raw: String) {
        recordSafeLog("NAV $kind ${navigationLabel(raw)}")
    }

    private fun recordSafeLog(event: String) {
        MozillaDiagnostics.record(event)
    }

    private fun navigationLabel(raw: String): String = try {
        val uri = Uri.parse(raw)
        val parameters = uri.queryParameterNames.sorted().joinToString(",")
        val scheme = uri.scheme.orEmpty()
        val host = uri.host.orEmpty()
        val destination = if (scheme.isNotEmpty() && host.isNotEmpty()) {
            "$scheme://$host${uri.path.orEmpty()}"
        } else {
            scheme
        }
        "$destination params=[$parameters]"
    } catch (_: Exception) {
        "[invalid-url]"
    }

    private fun capture(raw: String): Boolean {
        if (completed) return true
        val code = FoxOAuth.callbackCode(raw, verifier)
        if (code == null) {
            recordSafeLog("OAUTH callback rejected")
            finishWithError("Mozilla returned an invalid or expired authentication response")
        } else {
            recordSafeLog("OAUTH callback accepted codeLength=${code.length}")
            completed = true
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CODE, code).putExtra(EXTRA_VERIFIER, verifier))
            finish()
        }
        return true
    }

    private fun finishWithError(message: String) {
        completed = true
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }
}
