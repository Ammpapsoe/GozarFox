package com.gozar

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import android.text.InputType
import android.widget.EditText
import android.widget.ImageView

class LoginActivity : BaseActivity() {

    companion object {
        const val ADD_ACCOUNT = "com.gozar.ADD_ACCOUNT"
        private const val ACCOUNT_PAGE = "https://accounts.firefox.com/signin"
        private const val CREATE_PAGE = "https://accounts.firefox.com/signup"
        private const val RESET_PAGE = "https://accounts.firefox.com/reset_password"

        internal fun systemEmail(data: Intent?): String? {
            val value = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim().orEmpty()
            return value.takeIf { Patterns.EMAIL_ADDRESS.matcher(it).matches() }
        }
    }

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var mailBox: View
    private lateinit var secretBox: View
    private lateinit var confirmBox: View
    private lateinit var codeBox: View
    private lateinit var mail: EditText
    private lateinit var secret: EditText
    private lateinit var confirm: EditText
    private lateinit var codeInput: EditText
    private lateinit var reveal: ImageView
    private var shown = false
    private lateinit var primary: MaterialButton
    private lateinit var forgot: MaterialButton
    private lateinit var switchButton: MaterialButton
    private lateinit var languageButton: MaterialButton
    private lateinit var progress: CircularProgressIndicator
    private lateinit var error: TextView
    private lateinit var logs: MaterialButton
    private lateinit var systemAccounts: View
    private lateinit var savedAccounts: MaterialButton
    private lateinit var cancelAdd: MaterialButton

    private var adding = false
    private var initialRevision = 0L
    private val accountPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val value = systemEmail(result.data)
            if (value != null) {
                mail.setText(value)
                mail.setSelection(value.length)
            } else {
                show(getString(R.string.system_account_not_email))
            }
        }
    }
    private val mozillaAuth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // The credential never travels through an Intent. The WebView hands it
        // straight to Fox, so a result only has to say whether it worked.
        if (result.resultCode != Activity.RESULT_OK) {
            show(result.data?.getStringExtra(MozillaWebViewActivity.EXTRA_ERROR) ?: getString(R.string.cancel))
        }
        render()
    }
    private var create = false
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.color.login_bg)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_login)
        create = savedInstanceState?.getBoolean("create", false) ?: false
        adding = savedInstanceState?.getBoolean("adding") ?: intent.getBooleanExtra(ADD_ACCOUNT, false)
        initialRevision = savedInstanceState?.getLong("initial_revision") ?: FoxStore.authRevision(this)
        languageButton = findViewById(R.id.loginLanguage)
        languageButton.setOnClickListener { if (!Fox.busy) showLanguagePicker(this) }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.loginRoot)) { _, insets ->
            val params = languageButton.layoutParams as android.widget.FrameLayout.LayoutParams
            params.topMargin = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            languageButton.layoutParams = params
            insets
        }
        title = findViewById(R.id.loginTitle)
        subtitle = findViewById(R.id.loginSubtitle)
        mailBox = findViewById(R.id.loginMailBox)
        secretBox = findViewById(R.id.loginSecretBox)
        confirmBox = findViewById(R.id.loginConfirmBox)
        codeBox = findViewById(R.id.loginCodeBox)
        mail = findViewById(R.id.loginMail)
        secret = findViewById(R.id.loginSecret)
        confirm = findViewById(R.id.loginConfirm)
        codeInput = findViewById(R.id.loginCode)
        primary = findViewById(R.id.loginPrimary)
        forgot = findViewById(R.id.loginForgot)
        switchButton = findViewById(R.id.loginSwitch)
        progress = findViewById(R.id.loginProgress)
        error = findViewById(R.id.loginError)
        logs = findViewById(R.id.loginLogs)
        reveal = findViewById(R.id.loginReveal)
        systemAccounts = findViewById(R.id.loginSystemAccounts)
        savedAccounts = findViewById(R.id.loginSavedAccounts)
        cancelAdd = findViewById(R.id.loginCancelAdd)
        systemAccounts.setOnClickListener { chooseSystemAccount() }
        savedAccounts.setOnClickListener { chooseSavedAccount() }
        cancelAdd.setOnClickListener { onBackPressed() }
        reveal.setOnClickListener {
            shown = !shown
            val mode = if (shown) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            secret.inputType = mode
            secret.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            secret.setSelection(secret.text.length)
        }

        primary.setOnClickListener { act() }
        logs.setOnClickListener { MozillaDiagnostics.show(this) }
        forgot.setOnClickListener { web(if (create) CREATE_PAGE else RESET_PAGE) }
        switchButton.setOnClickListener {
            create = !create
            error.visibility = View.GONE
            render()
        }
        secret.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                act()
                true
            } else {
                false
            }
        }
        confirm.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                act()
                true
            } else {
                false
            }
        }
        codeInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                act()
                true
            } else {
                false
            }
        }
        Fox.watch(listener)
        Fox.load(this)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("create", create)
        outState.putBoolean("adding", adding)
        outState.putLong("initial_revision", initialRevision)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        Fox.unwatch(listener)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (Fox.busy) {
            toast(R.string.account_wait)
            return
        }
        Fox.cancelLogin()
        super.onBackPressed()
    }

    private fun chooseSystemAccount() {
        if (Fox.busy) return
        try {
            val intent = AccountManager.newChooseAccountIntent(null, null, null, null, null, null, null)
            accountPicker.launch(intent)
        } catch (e: Exception) {
            show(getString(R.string.system_account_unavailable))
        }
    }

    private fun chooseSavedAccount() {
        val accounts = FoxStore.accounts(this)
        if (accounts.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.saved_accounts)
            .setItems(accounts.map { it.email }.toTypedArray()) { _, which ->
                if (Fox.switchAccount(this, accounts[which].id)) render()
                else toast(R.string.account_wait)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun web(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            show(getString(R.string.login_no_browser))
        }
    }

    private fun show(text: String) {
        error.text = text
        error.visibility = View.VISIBLE
    }

    private fun act() {
        if (Fox.busy) return
        error.visibility = View.GONE
        if (Fox.needsStep()) {
            when {
                Fox.canRetryLogin() -> Fox.retryLogin(this)
                Fox.isLinkStep() -> Fox.confirmLink(this)
                else -> Fox.submitCode(this, codeInput.text?.toString().orEmpty().trim())
            }
            render()
            return
        }
        val address = mail.text?.toString()?.trim().orEmpty()
        // Mozilla's own page collects the address and the password, so an empty
        // field here is fine and is the normal way in. A malformed one is not:
        // it would only prefill the page with something nobody can sign in to.
        if (address.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
            show(getString(R.string.login_fill))
            return
        }
        secret.text?.clear()
        confirm.text?.clear()
        mozillaAuth.launch(Intent(this, MozillaWebViewActivity::class.java)
            .putExtra(MozillaWebViewActivity.EXTRA_CREATE, create)
            .putExtra(MozillaWebViewActivity.EXTRA_EMAIL, address))
        render()
    }

    private fun render() {
        val signed = Fox.signedIn(this)
        val completed = !adding || FoxStore.authRevision(this) != initialRevision
        if (signed && !Fox.busy && completed) {
            secret.text?.clear()
            confirm.text?.clear()
            if (!adding) startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.AUTO_CONNECT, true))
            finish()
            return
        }
        val step = Fox.needsStep()
        val link = Fox.isLinkStep()
        val retry = Fox.canRetryLogin()
        mailBox.visibility = if (step) View.GONE else View.VISIBLE
        secretBox.visibility = View.GONE
        confirmBox.visibility = View.GONE
        codeBox.visibility = if (step && !link && !retry) View.VISIBLE else View.GONE
        forgot.visibility = if (step) View.GONE else View.VISIBLE
        forgot.text = getString(if (create) R.string.login_create_browser else R.string.login_forgot)
        switchButton.visibility = if (step) View.GONE else View.VISIBLE
        savedAccounts.visibility = if (!adding && !step && FoxStore.accounts(this).isNotEmpty()) View.VISIBLE else View.GONE
        cancelAdd.visibility = if (adding) View.VISIBLE else View.GONE

        title.text = when {
            step && retry -> getString(R.string.login_retry_title)
            step -> getString(R.string.login_check_title)
            create -> getString(R.string.login_create_title)
            else -> getString(R.string.login_signin_title)
        }
        subtitle.text = when {
            step && retry -> getString(R.string.login_retry_sub)
            step && link -> getString(R.string.login_check_link)
            step -> getString(R.string.login_check_code)
            create -> getString(R.string.login_create_sub)
            else -> getString(R.string.login_signin_sub)
        }
        val action = when {
            step && retry -> getString(R.string.login_retry_action)
            step && link -> getString(R.string.login_confirm_action)
            step -> getString(R.string.login_continue)
            create -> getString(R.string.login_create_action)
            else -> getString(R.string.login_action)
        }
        val busy = Fox.busy
        primary.text = if (busy) "" else action
        primary.contentDescription = if (busy) getString(R.string.login_working) else null
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        primary.isEnabled = !busy
        languageButton.isEnabled = !busy
        mail.isEnabled = !busy
        secret.isEnabled = !busy
        confirm.isEnabled = !busy
        codeInput.isEnabled = !busy
        reveal.isEnabled = !busy
        forgot.isEnabled = !busy
        switchButton.isEnabled = !busy
        systemAccounts.isEnabled = !busy
        savedAccounts.isEnabled = !busy
        cancelAdd.isEnabled = !busy
        logs.visibility = if (MozillaDiagnostics.hasEvents()) View.VISIBLE else View.GONE

        val trouble = !busy && Fox.message.isNotEmpty() &&
            Fox.message != "code" && Fox.message != "link" &&
            Fox.message != "ping" && Fox.message != "servers" && Fox.message != "quota"
        if (trouble) {
            error.text = Fox.message
            error.visibility = View.VISIBLE
        } else {
            error.visibility = View.GONE
        }
    }
}