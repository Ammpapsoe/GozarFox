package com.gozar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.button.MaterialButton
import java.net.URLEncoder

/**
 * Mozilla's sign-in page, hosted in a WebView we own.
 *
 * The reason this screen exists at all is that Firefox Accounts does not hand
 * an authorization code to a third-party app. It announces a session to
 * whatever is hosting the page, over the WebChannel, and it only does that for
 * an integration that looks like a browser. A Custom Tab cannot be injected
 * into, so it can never receive that announcement; a WebView can.
 *
 * What the page gives us is a session token, not an OAuth token. The exchange
 * happens natively in [Fox.webSignIn], over a Hawk-signed `fxa-credentials`
 * grant, and the credential is deliberately never returned through an Intent:
 * this screen tells its caller only whether it worked.
 */
class MozillaWebViewActivity : BaseActivity() {

    companion object {
        const val EXTRA_EMAIL = "email"
        const val EXTRA_CREATE = "create"
        const val EXTRA_ERROR = "error"

        private const val PAGE = "https://accounts.firefox.com/"
        private const val SIGN_UP = "https://accounts.firefox.com/signup"
        private const val PAGE_HOST = "accounts.firefox.com"

        // Claiming a desktop-browser integration is what makes the page speak
        // the WebChannel at all, and `service=sync` is the flow that ends in a
        // `fxaccounts:login` announcement carrying the session token.
        private const val INTEGRATION = "fx_desktop_v3"
        private const val SERVICE = "sync"
    }

    private lateinit var web: WebView
    private lateinit var header: View
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var done: View
    private lateinit var error: TextView
    private lateinit var connect: MaterialButton
    private lateinit var back: MaterialButton

    private var started = false
    private var pageReady = false
    private var handedOver = false
    private var completed = false
    private var script: ScriptHandler? = null

    private val foxWatcher: () -> Unit = { render() }

    private val bridge = object : WebViewCompat.WebMessageListener {
        override fun onPostMessage(
            view: WebView,
            message: WebMessageCompat,
            sourceOrigin: Uri,
            mainFrame: Boolean,
            reply: JavaScriptReplyProxy
        ) {
            if (view !== web) return
            val payload = if (message.type == WebMessageCompat.TYPE_STRING) message.data else null
            channel(sourceOrigin.toString(), mainFrame, payload, reply)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mozilla_web)
        web = findViewById(R.id.webSignInPage)
        header = findViewById(R.id.webHeader)
        loading = findViewById(R.id.webLoading)
        loadingText = findViewById(R.id.webLoadingText)
        done = findViewById(R.id.webDone)
        error = findViewById(R.id.webError)
        connect = findViewById(R.id.webConnect)
        back = findViewById(R.id.webBack)

        applyInsets()
        back.setOnClickListener { finishWith(Activity.RESULT_CANCELED) }
        connect.setOnClickListener { finishWith(Activity.RESULT_OK) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWith(Activity.RESULT_CANCELED)
        })

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            // Without both of these the page can neither be answered nor heard,
            // so the sign-in could never complete. Say so instead of showing a
            // form that leads nowhere.
            MozillaDiagnostics.record("WEBVIEW unsupported-feature")
            finishWith(
                Activity.RESULT_CANCELED,
                Intent().putExtra(EXTRA_ERROR, getString(R.string.web_engine_missing))
            )
            return
        }

        harden(web.settings)
        install()
        web.webViewClient = client()

        val email = intent.getStringExtra(EXTRA_EMAIL).orEmpty().trim()
        val create = intent.getBooleanExtra(EXTRA_CREATE, false)
        MozillaDiagnostics.record("WEBVIEW stage=load create=$create prefill=${email.isNotEmpty()}")
        web.loadUrl(pageUrl(email, create))
        Fox.watch(foxWatcher)
        started = true
        render()
    }

    /**
     * JavaScript is not optional here: the sign-in page *is* JavaScript. Every
     * other browser capability this flow does not need is switched off, and the
     * two that would let a page reach beyond its own origin are switched off
     * explicitly rather than left at a default that may change.
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun harden(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        // No local file or content-provider access, and no cross-origin reads
        // from a file URL. Nothing in this flow loads anything but https.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setAllowFileAccessFromFileURLs(false)
        settings.setAllowUniversalAccessFromFileURLs(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        WebSettingsCompat.setSafeBrowsingEnabled(settings, true)

        // The page must not be able to open a window we are not watching.
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false

        settings.setGeolocationEnabled(false)
        settings.mediaPlaybackRequiresUserGesture = true
        settings.setSaveFormData(false)
        settings.setSavePassword(false)
        settings.userAgentString = honestAgent(settings.userAgentString)

        // The sign-in needs first-party cookies for its own CSRF state; a
        // third party embedded in the page gets none.
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
    }

    /**
     * The WebView's own user agent with the two markers removed that announce
     * "embedded WebView": the `; wv` flag and the `Version/4.0` token that
     * Chrome on Android never sends. The Chrome build number stays whatever is
     * really installed, so unlike a hard-coded string it cannot go stale and
     * turn into a fingerprint of its own.
     */
    private fun honestAgent(agent: String?): String? {
        val real = agent?.takeIf { it.contains("Chrome/") } ?: return null
        return real.replace("; wv)", ")").replace("Version/4.0 ", "")
    }

    /**
     * Both injections are bound to a single origin rule. The script runs before
     * any of Mozilla's own, which is the only way to hear a credential the page
     * announces the instant it has one; the listener is the only channel back,
     * and `addJavascriptInterface` is deliberately not used anywhere in this
     * app because it exposes a Java object to every frame that can reach it.
     */
    private fun install() {
        val origins = setOf(FoxWebChannel.ORIGIN)
        script = WebViewCompat.addDocumentStartJavaScript(web, FoxBrowserScript.script, origins)
        WebViewCompat.addWebMessageListener(web, FoxBrowserScript.BRIDGE, origins, bridge)
    }

    private fun channel(
        origin: String,
        mainFrame: Boolean,
        payload: String?,
        reply: JavaScriptReplyProxy
    ) {
        when (val message = FoxWebChannel.read(origin, mainFrame, payload)) {
            is FoxWebChannel.Message.Waiting -> reply.postMessage(FoxWebChannel.replyTo(message))
            is FoxWebChannel.Message.SignedIn -> accept(message.login)
            // A rejected message is a fact about the page, never about the
            // credential, so the reason is safe to record. The token is not.
            is FoxWebChannel.Message.Rejected ->
                MozillaDiagnostics.record("WEBCHANNEL rejected=${message.reason}")
            is FoxWebChannel.Message.Ignored -> Unit
        }
    }

    private fun accept(login: FoxWebChannel.Login) {
        if (handedOver) return
        handedOver = true
        error.visibility = View.GONE
        MozillaDiagnostics.record("WEBCHANNEL stage=session-received uid=${login.uid}")
        loadingText.setText(R.string.web_stage_tokens)
        show(loading)
        Fox.webSignIn(applicationContext, login.email, login.uid, login.sessionToken)
        render()
    }

    private fun render() {
        if (completed) return
        if (Fox.signedIn(this)) {
            completed = true
            MozillaDiagnostics.record("WEBVIEW stage=account-saved")
            show(done)
            return
        }
        if (!handedOver) {
            if (pageReady) show(null) else show(loading)
            return
        }
        if (Fox.busy) {
            show(loading)
            return
        }
        val trouble = Fox.message
        if (trouble.isEmpty()) {
            show(loading)
            return
        }
        val diagnostic = Fox.lastFailureDiagnostic
        if (diagnostic.contains("errno=104")) {
            // The account is not confirmed yet. That is not a failure of this
            // screen: the code is typed into Mozilla's page, which then
            // announces the session again and we take it the second time.
            MozillaDiagnostics.record("WEBVIEW stage=session-unverified")
            handedOver = false
            say(getString(R.string.web_verify_in_page))
            show(null)
            return
        }
        if (diagnostic.contains("http=406") || diagnostic.contains("http=451")) {
            say(getString(R.string.web_blocked))
        } else {
            say(trouble)
        }
        show(null)
    }

    private fun client() = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageReady = false
            if (!handedOver) {
                loadingText.setText(R.string.web_connecting)
                show(loading)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            pageReady = true
            if (!handedOver) render()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Frames stay where they are: a challenge or a captcha is embedded,
            // and solving it is the user's job, in this page, on this screen.
            if (!request.isForMainFrame) return false
            val target = request.url ?: return false
            if (target.scheme == "https" && target.host.equals(PAGE_HOST, true)) return false
            openElsewhere(target)
            return true
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            response: WebResourceResponse
        ) {
            if (!request.isForMainFrame) return
            val status = response.statusCode
            MozillaDiagnostics.record("WEBVIEW http=$status")
            if (status == 406 || status == 451) say(getString(R.string.web_blocked))
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            failure: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            MozillaDiagnostics.record("WEBVIEW error=${failure.errorCode}")
            if (!handedOver) say(getString(R.string.web_page_failed))
        }
    }

    /**
     * Anything that is not the sign-in page leaves the app. Only http and https
     * are forwarded: a page must not be able to use us to launch a `javascript:`,
     * `file:` or `intent:` URL.
     */
    private fun openElsewhere(target: Uri) {
        val scheme = target.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") {
            MozillaDiagnostics.record("WEBVIEW refused-scheme=$scheme")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, target))
        } catch (missing: ActivityNotFoundException) {
            MozillaDiagnostics.record("WEBVIEW no-browser")
        }
    }

    private fun pageUrl(email: String, create: Boolean): String {
        val query = StringBuilder("context=").append(INTEGRATION).append("&service=").append(SERVICE)
        if (email.isNotEmpty()) {
            query.append("&email=").append(URLEncoder.encode(email, "UTF-8"))
        }
        return (if (create) SIGN_UP else PAGE) + "?" + query
    }

    /** The WebView stays laid out and visible; opaque overlays cover it. */
    private fun show(overlay: View?) {
        loading.visibility = if (overlay === loading) View.VISIBLE else View.GONE
        done.visibility = if (overlay === done) View.VISIBLE else View.GONE
    }

    private fun say(text: String) {
        error.text = text
        error.visibility = View.VISIBLE
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.webRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(header.paddingLeft, bars.top, header.paddingRight, header.paddingBottom)
            error.setPadding(error.paddingLeft, error.paddingTop, error.paddingRight, bars.bottom)
            insets
        }
    }

    private fun finishWith(code: Int, data: Intent? = null) {
        setResult(code, data)
        finish()
    }

    override fun onDestroy() {
        if (started) Fox.unwatch(foxWatcher)
        script?.remove()
        script = null
        // The WebView held a signed-in Mozilla session. Dropping it means the
        // next sign-in starts clean, which is what a shared device needs.
        try {
            CookieManager.getInstance().removeAllCookies(null)
        } catch (_: Exception) {
        }
        if (::web.isInitialized) {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.clearHistory()
            (web.parent as? ViewGroup)?.removeView(web)
            web.removeAllViews()
            web.destroy()
        }
        super.onDestroy()
    }
}
