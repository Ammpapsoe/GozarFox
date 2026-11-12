package com.gozar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        private const val STATE_EXPECTED = "mozilla_expected"
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView
    private lateinit var verifier: String
    private lateinit var expectedState: String
    private val safeLogLock = Any()
    private val safeLogs = mutableListOf<String>()
    private var completed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mozilla_auth)
        webView = findViewById(R.id.mozillaWebView)
        progress = findViewById(R.id.mozillaProgress)
        error = findViewById(R.id.mozillaError)
        findViewById<ExtendedFloatingActionButton>(R.id.mozillaLogs).setOnClickListener { showLogs() }
        verifier = savedInstanceState?.getString(STATE_VERIFIER).orEmpty()
        expectedState = savedInstanceState?.getString(STATE_EXPECTED).orEmpty()
        val restoring = verifier.isNotEmpty() && expectedState.isNotEmpty()
        val target = if (restoring) {
            savedInstanceState?.getString(STATE_URL).orEmpty()
        } else {
            val request = FoxOAuth.request(
                intent.getBooleanExtra(EXTRA_CREATE, false),
                intent.getStringExtra(EXTRA_EMAIL).orEmpty()
            )
            verifier = request.verifier
            expectedState = request.state
            request.url
        }
        if (target.isEmpty()) {
            finishWithError("Mozilla authentication could not be restored")
            return
        }
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            userAgentString = FoxNet.UA
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val raw = request.url.toString()
                recordNavigation("override", raw)
                if (capture(raw)) return true
                return request.url.scheme != "https"
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) recordNavigation("main-resource", request.url.toString())
                return super.shouldInterceptRequest(view, request)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                recordNavigation("history", url)
                if (capture(url)) return
                super.doUpdateVisitedHistory(view, url, isReload)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                recordNavigation("started", url)
                if (capture(url)) return
                progress.visibility = View.VISIBLE
                error.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                recordNavigation("finished", url)
                if (capture(url)) return
                if (!completed) progress.visibility = View.GONE
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val level = consoleMessage.messageLevel().name
                val message = safeConsole(consoleMessage.message())
                val source = safeConsole(consoleMessage.sourceId())
                Log.d("MozillaWebView", "$level $message ($source:${consoleMessage.lineNumber()})")
                return true
            }
        }
        if (restoring) {
            webView.loadUrl(target)
        } else {
            progress.visibility = View.VISIBLE
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                if (!completed && !isFinishing && !isDestroyed) webView.loadUrl(target)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_URL, webView.url)
        outState.putString(STATE_VERIFIER, verifier)
        outState.putString(STATE_EXPECTED, expectedState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun recordNavigation(kind: String, raw: String) {
        recordSafeLog("NAV $kind ${navigationLabel(raw)}")
    }

    private fun recordSafeLog(event: String) {
        synchronized(safeLogLock) { safeLogs.add(event) }
        Log.d("MozillaWebView", event)
    }

    private fun showLogs() {
        val snapshot = synchronized(safeLogLock) { safeLogs.joinToString("\n") }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            text = snapshot.ifEmpty { getString(R.string.mozilla_logs_empty) }
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mozilla_logs)
            .setView(scroll)
            .setPositiveButton(R.string.mozilla_logs_copy) { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.mozilla_logs), snapshot))
                Toast.makeText(this, R.string.mozilla_logs_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
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

    private fun safeConsole(raw: String?): String = raw.orEmpty()
        .replace(Regex("https?://\\S+"), "[url]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
        .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+"), "$1[redacted]")
        .replace(Regex("(?i)(\"?(?:access_token|refresh_token|code|state|code_verifier|password|authorization)\"?\\s*[:=]\\s*\"?)[A-Za-z0-9._~+/-]+"), "$1[redacted]")
        .replace(Regex("\\b\\d{6}\\b"), "[code]")
        .take(2048)

    private fun capture(raw: String): Boolean {
        if (completed || !isCallback(raw)) return false
        val code = FoxOAuth.callbackCode(raw, expectedState)
        if (code == null) {
            recordSafeLog("OAUTH callback rejected")
            finishWithError("Mozilla returned an invalid or expired authentication response")
        } else {
            recordSafeLog("OAUTH callback accepted")
            completed = true
            webView.stopLoading()
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CODE, code).putExtra(EXTRA_VERIFIER, verifier))
            finish()
        }
        return true
    }

    private fun isCallback(raw: String): Boolean = try {
        val uri = Uri.parse(raw)
        uri.scheme == "https" && uri.host == "vpn.mozilla.org" && uri.path == "/oauth/success"
    } catch (_: Exception) {
        false
    }

    private fun finishWithError(message: String) {
        completed = true
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }
}
