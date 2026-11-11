package com.gozar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView

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
    private var completed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mozilla_auth)
        webView = findViewById(R.id.mozillaWebView)
        progress = findViewById(R.id.mozillaProgress)
        error = findViewById(R.id.mozillaError)
        verifier = savedInstanceState?.getString(STATE_VERIFIER).orEmpty()
        expectedState = savedInstanceState?.getString(STATE_EXPECTED).orEmpty()
        val target = if (verifier.isNotEmpty() && expectedState.isNotEmpty()) {
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
                if (capture(raw)) return true
                return request.url.scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (capture(url)) return
                progress.visibility = View.VISIBLE
                error.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!completed) progress.visibility = View.GONE
            }
        }
        webView.loadUrl(target)
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

    private fun capture(raw: String): Boolean {
        if (completed || !isCallback(raw)) return false
        val code = FoxOAuth.callbackCode(raw, expectedState)
        if (code == null) {
            finishWithError("Mozilla returned an invalid or expired authentication response")
        } else {
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
