package com.gozar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.widget.NestedScrollView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import java.util.Locale
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsFragment : Fragment() {

    private val tonAddress = "UQDxiU-jPFK_TgB0wOzZndhf3IKJLTLm8Vg_Uaif7_C2eCL3"
    private var updateChecking = false
    private var rootChecking = false
    private var appPicker: AppPicker? = null
    private var section = ""
    private var changingSwitch = false
    private var linkState: List<Boolean>? = null
    private var usageState: Pair<Long, Long>? = null
    private var versionName: String? = null
    private val accountListener: () -> Unit = { if (isAdded && view != null && !isHidden) paint() }
    private val linkListener: () -> Unit = {
        if (isAdded && view != null && !isHidden) {
            if (section == "security" || section == "connection") {
                val current = connectionState()
                if (current != linkState) {
                    linkState = current
                    paint()
                }
            } else if (section == "account" && usageState != (Fox.limit to Fox.remaining)) {
                paint()
            }
        }
    }

    private fun connectionState() = listOf(
        FoxLink.connected, FoxLink.connecting, FoxVpnService.isAlive(), FoxVpnService.rootActive
    )

    private fun <T : View> find(id: Int): T = view!!.findViewById(id)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.activity_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        section = savedInstanceState?.getString("section").orEmpty()
        find<View>(R.id.sectionAccountButton).setOnClickListener { showSection("account") }
        find<View>(R.id.sectionSecurityButton).setOnClickListener { showSection("security") }
        find<View>(R.id.sectionConnectionButton).setOnClickListener { showSection("connection") }
        find<View>(R.id.sectionAppearanceButton).setOnClickListener { showSection("appearance") }
        find<View>(R.id.sectionGeneralButton).setOnClickListener { showSection("general") }
        find<View>(R.id.rowKillSwitch).setOnClickListener { explainLockdown() }
        find<View>(R.id.rowDns).setOnClickListener { pickDns() }
        find<View>(R.id.rowNotifications).setOnClickListener { openNotificationSettings() }
        find<View>(R.id.rowAds).setOnClickListener { requestAdBlocking(!Store.blockAds(requireContext())) }
        find<MaterialSwitch>(R.id.swAds).setOnCheckedChangeListener { _, checked ->
            if (!changingSwitch) requestAdBlocking(checked)
        }
        bind(R.id.rowGeo, R.id.swGeo, Store.geolocation(requireContext())) { Store.setGeolocation(requireContext(), it) }
        bind(R.id.rowRecents, R.id.swRecents, Store.recents(requireContext())) { Store.setRecents(requireContext(), it) }

        find<View>(R.id.btnBack).setOnClickListener {
            if (!goBack()) (activity as? MainActivity)?.showHome()
        }
        find<View>(R.id.rowLang).setOnClickListener { pickLanguage() }
        find<View>(R.id.rowTheme).setOnClickListener { pickTheme() }
        find<View>(R.id.rowUpdate).setOnClickListener { checkUpdates() }
        find<View>(R.id.rowGithub).setOnClickListener { openProject() }
        find<View>(R.id.rowAbout).setOnClickListener { showLegalNotice() }
        find<View>(R.id.rowRouting).setOnClickListener { changeProtection { pickRouting() } }
        find<View>(R.id.rowChain).setOnClickListener { changeProtection { editChain() } }
        find<View>(R.id.rowApps).setOnClickListener { changeProtection { pickApps() } }
        find<View>(R.id.rowRoot).setOnClickListener { pickRoot() }
        find<View>(R.id.rowDonate).setOnClickListener { showDonation() }
        find<View>(R.id.btnAccounts).setOnClickListener { showAccounts() }
        find<View>(R.id.btnAccountRefresh).setOnClickListener {
            Fox.refreshUsage(requireContext())
            toast(R.string.account_refreshing)
        }
        find<View>(R.id.btnSignOut).setOnClickListener { askSignOut() }
        Fox.watch(accountListener)
        FoxLink.watch(linkListener)
        showSection(section)
    }

    override fun onDestroyView() {
        Fox.unwatch(accountListener)
        FoxLink.unwatch(linkListener)
        appPicker?.dismiss()
        appPicker = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) paint()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) paint()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("section", section)
        super.onSaveInstanceState(outState)
    }

    fun goBack(): Boolean {
        if (section.isEmpty()) return false
        showSection("")
        return true
    }

    private fun showSection(value: String) {
        section = value
        if (view == null) return
        val entries = listOf(
            "" to R.id.settingsOverview,
            "account" to R.id.settingsAccount,
            "security" to R.id.settingsSecurity,
            "connection" to R.id.settingsConnection,
            "appearance" to R.id.settingsAppearance,
            "general" to R.id.settingsGeneral
        )
        for ((name, id) in entries) find<View>(id).visibility = if (name == section) View.VISIBLE else View.GONE
        find<TextView>(R.id.settingsTitle).setText(when (section) {
            "account" -> R.string.settings_account
            "security" -> R.string.settings_security
            "connection" -> R.string.connection_section
            "appearance" -> R.string.visual
            "general" -> R.string.settings_general
            else -> R.string.settings
        })
        find<NestedScrollView>(R.id.settingsScroll).scrollTo(0, 0)
        paint()
    }

    fun restore(intent: Intent?) {
        if (view != null && !isHidden) paint()
    }

    private fun bind(row: Int, switchId: Int, initial: Boolean, save: (Boolean) -> Unit) {
        val toggle = find<MaterialSwitch>(switchId)
        toggle.isChecked = initial
        toggle.setOnCheckedChangeListener { _, checked -> save(checked) }
        find<View>(row).setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }

    private fun paint() {
        if (view == null) return
        val context = context ?: return
        when (section) {
            "" -> {
                val mail = FoxStore.email(context)
                find<TextView>(R.id.tvSettingsOverviewAccount).text =
                    if (Fox.signedIn(context) && mail.isNotBlank()) mail else getString(R.string.settings_account_desc)
            }
            "account" -> {
                usageState = Fox.limit to Fox.remaining
                val signed = Fox.signedIn(context)
                val mail = FoxStore.email(context)
                find<TextView>(R.id.tvAccountEmail).text = if (signed) mail else getString(R.string.fox_signed_out)
                find<TextView>(R.id.tvAccountInitial).text =
                    if (signed && mail.isNotEmpty()) mail.substring(0, 1).uppercase() else "?"
                val limit = Fox.limit
                val used = (limit - Fox.remaining).coerceAtLeast(0)
                val left = Fox.remaining.coerceAtLeast(0)
                val percent = if (limit > 0) ((used * 100) / limit).toInt().coerceIn(0, 100) else 0
                find<TextView>(R.id.tvAccountPlan).text = getString(R.string.settings_account)
                find<TextView>(R.id.tvUsagePercent).text = if (limit > 0) "$percent%" else "—"
                find<TextView>(R.id.tvUsageUsed).text =
                    if (limit > 0) getString(R.string.account_usage, ltr(plain(used)), ltr(plain(limit))) else getString(R.string.fox_usage_pending)
                find<TextView>(R.id.tvUsageLeft).text =
                    if (limit > 0) getString(R.string.usage_left, ltr(plain(left))) else ""
                find<UsageMeterView>(R.id.usageMeter).apply {
                    visibility = if (limit > 0) View.VISIBLE else View.GONE
                    setProgress(percent)
                }
                find<View>(R.id.btnAccountRefresh).isEnabled = signed
            }
            "security" -> {
                linkState = connectionState()
                find<TextView>(R.id.tvKillSwitch).setText(when (FoxVpnService.lockdownState()) {
                    3 -> R.string.kill_active
                    2 -> R.string.kill_always
                    1 -> R.string.kill_off
                    else -> R.string.kill_unknown
                })
                val blocking = Store.blockAds(context)
                changingSwitch = true
                find<MaterialSwitch>(R.id.swAds).isChecked = blocking
                changingSwitch = false
                find<TextView>(R.id.tvAds).setText(if (blocking) R.string.ad_block_on else R.string.ad_block_off)
                find<TextView>(R.id.tvDns).text = if (blocking) getString(R.string.dns_overridden) else when (Store.dnsProfile(context)) {
                    DnsPolicy.CLOUDFLARE -> getString(R.string.dns_cloudflare)
                    DnsPolicy.QUAD9 -> getString(R.string.dns_quad9)
                    DnsPolicy.CUSTOM -> Store.customDns(context)
                    else -> getString(R.string.dns_auto)
                }
            }
            "connection" -> {
                linkState = connectionState()
                val hasDirectRoutes = Store.bypassLan(context) || Store.bypassList(context).isNotBlank()
                val directGuarded = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
                    (FoxVpnService.isAlive() && FoxVpnService.lockdownActive())
                find<TextView>(R.id.tvRouting).text = when {
                    !hasDirectRoutes -> getString(R.string.routing_off)
                    directGuarded -> getString(R.string.routing_guarded)
                    else -> getString(R.string.routing_on, FoxRoute.split(Store.bypassList(context)).size + if (Store.bypassLan(context)) 1 else 0)
                }
                val hops = FoxUpstream.parse(Store.chain(context)).size
                find<TextView>(R.id.tvChain).text = if (hops > 0) getString(R.string.chain_on, hops) else getString(R.string.chain_off)
                find<TextView>(R.id.tvRoot).text = getString(when {
                    rootChecking -> R.string.root_checking
                    FoxVpnService.rootActive -> R.string.root_active
                    Store.rootRouting(context) -> R.string.root_ready
                    else -> R.string.root_off
                })
                val only = Store.apps(context)
                find<TextView>(R.id.tvApps).text = if (only.isEmpty()) getString(R.string.apps_all) else getString(R.string.apps_count, only.size)
            }
            "appearance" -> {
                find<TextView>(R.id.tvLang).text = when (Store.lang(context)) {
                    "fa" -> getString(R.string.lang_fa)
                    "en" -> getString(R.string.lang_en)
                    else -> getString(R.string.lang_system)
                }
                find<TextView>(R.id.tvTheme).text = when (Store.theme(context)) {
                    "light" -> getString(R.string.theme_light)
                    "dark" -> getString(R.string.theme_dark)
                    else -> getString(R.string.theme_auto)
                }
            }
            "general" -> {
                val version = versionName ?: try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty().also { versionName = it }
                } catch (_: Exception) {
                    ""
                }
                find<TextView>(R.id.tvAbout).text = getString(R.string.version_license, version)
                val available = Store.updateVersion(context)
                find<TextView>(R.id.tvUpdate).text = when {
                    updateChecking -> getString(R.string.update_checking)
                    available.isNotBlank() -> getString(R.string.update_available, available)
                    else -> getString(R.string.update_current, version)
                }
            }
        }
    }

    private fun explainLockdown() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.kill_switch)
            .setMessage(R.string.kill_message)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.kill_open) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                } catch (_: Exception) {
                    toast(R.string.kill_no_settings)
                }
            }
            .show()
    }

    private fun changeProtection(action: () -> Unit) {
        if (FoxVpnService.alwaysOn()) {
            showAlwaysOnSettings(requireContext())
            paint()
            return
        }
        if (FoxLink.connected || FoxLink.connecting || FoxVpnService.isAlive()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_protection)
                .setMessage(R.string.change_disconnect)
                .setNegativeButton(R.string.cancel) { _, _ -> paint() }
                .setOnCancelListener { paint() }
                .setPositiveButton(R.string.disconnect) { _, _ ->
                    FoxVpnService.stop(requireContext())
                    action()
                    paint()
                }
                .show()
        } else {
            action()
            paint()
        }
    }

    private fun requestAdBlocking(enabled: Boolean) {
        val c = requireContext()
        paint()
        if (enabled == Store.blockAds(c)) return
        if (enabled) {
            MaterialAlertDialogBuilder(c)
                .setTitle(R.string.ad_block)
                .setMessage(R.string.ad_block_detail)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ -> changeProtection { Store.setBlockAds(c, true) } }
                .show()
        } else changeProtection { Store.setBlockAds(c, false) }
    }

    private fun pickDns() {
        val c = requireContext()
        val labels = arrayOf(getString(R.string.dns_auto), getString(R.string.dns_cloudflare), getString(R.string.dns_quad9), getString(R.string.dns_custom))
        val values = arrayOf(DnsPolicy.AUTO, DnsPolicy.CLOUDFLARE, DnsPolicy.QUAD9, DnsPolicy.CUSTOM)
        MaterialAlertDialogBuilder(c)
            .setTitle(R.string.dns_title)
            .setSingleChoiceItems(labels, values.indexOf(Store.dnsProfile(c)).coerceAtLeast(0)) { dialog, index ->
                dialog.dismiss()
                val selected = values[index]
                if (selected == DnsPolicy.CUSTOM) editDns() else if (selected != Store.dnsProfile(c)) {
                    changeProtection { Store.setDnsProfile(c, selected) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editDns() {
        val c = requireContext()
        val content = layoutInflater.inflate(R.layout.dialog_text_value, null)
        content.findViewById<TextView>(R.id.dialogTextHint).setText(R.string.dns_prompt)
        val box = content.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.dialogTextBox)
        val field = content.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dialogTextInput)
        field.setHint(R.string.dns_example)
        field.setSingleLine(true)
        field.setText(Store.customDns(c))
        val dialog = MaterialAlertDialogBuilder(c)
            .setTitle(R.string.dns_custom)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.routing_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text?.toString().orEmpty().trim()
                if (!DnsPolicy.validCustom(value)) {
                    box.error = getString(R.string.dns_invalid)
                } else {
                    dialog.dismiss()
                    changeProtection {
                        Store.setCustomDns(c, value)
                        Store.setDnsProfile(c, DnsPolicy.CUSTOM)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun openNotificationSettings() {
        val c = requireContext()
        try {
            val intent = if (android.os.Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
            }
            startActivity(intent)
        } catch (_: Exception) {
            toast(R.string.kill_no_settings)
        }
    }

    private fun pickRouting() {
        val context = requireContext()
        val lan = Store.bypassLan(context)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.routing)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.routing_lan)),
                booleanArrayOf(lan)
            ) { _, which, checked ->
                if (which == 0) Store.setBypassLan(context, checked)
            }
            .setNeutralButton(R.string.routing_list) { _, _ -> editBypass() }
            .setPositiveButton(R.string.routing_save) { _, _ -> paint() }
            .show()
    }

    private fun editBypass() {
        val context = requireContext()
        askText(
            title = getString(R.string.routing_list),
            hint = getString(R.string.routing_list_hint),
            value = Store.bypassList(context)
        ) { text ->
            Store.setBypassList(context, text)
            paint()
        }
    }

    private fun editChain() {
        val context = requireContext()
        askText(
            title = getString(R.string.chain),
            hint = getString(R.string.chain_hint),
            value = Store.chain(context)
        ) { text ->
            Store.setChain(context, text)
            paint()
        }
    }

    private fun askText(title: String, hint: String, value: String, save: (String) -> Unit) {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_text_value, null)
        view.findViewById<TextView>(R.id.dialogTextHint).text = hint
        val box = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.dialogTextBox)
        val field = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dialogTextInput)
        field.setText(value)
        field.setSelection(field.text?.length ?: 0)
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.routing_save) { _, _ ->
                box.error = null
                save(field.text?.toString().orEmpty().trim())
            }
            .show()
    }

    private fun pickRoot() {
        if (rootChecking) return
        if (FoxVpnService.alwaysOn()) {
            showAlwaysOnSettings(requireContext())
            return
        }
        val context = requireContext()
        if (FoxLink.connected || FoxLink.connecting || FoxVpnService.isAlive()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.root_title)
                .setMessage(R.string.root_disconnect)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ ->
                    FoxVpnService.stop(context)
                    chooseRoot()
                }.show()
        } else chooseRoot()
    }

    private fun chooseRoot() {
        val context = requireContext()
        if (Store.rootRouting(context)) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.root_title)
                .setMessage(R.string.root_disable)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ ->
                    Store.setRootRouting(context, false)
                    paint()
                }.show()
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.root_title)
            .setMessage(R.string.root_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.root_enable) { _, _ ->
                rootChecking = true
                paint()
                val app = context.applicationContext
                kotlin.concurrent.thread(isDaemon = true, name = "gozar-root-check") {
                    val granted = RootAccess.granted()
                    if (granted) Store.setRootRouting(app, true)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        rootChecking = false
                        if (isAdded && view != null) {
                            if (!granted) toast(R.string.root_denied)
                            paint()
                        }
                    }
                }
            }.show()
    }

    private fun pickApps() {
        if (appPicker == null) appPicker = AppPicker(requireContext()) { applyApps() }
        appPicker?.open()
    }

    private fun describeApps(count: Int): String =
        if (count == 0) getString(R.string.apps_all) else getString(R.string.apps_picked, count)

    private fun applyApps() {
        paint()
        val context = context ?: return
        if (FoxLink.connected) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.apps)
                .setMessage(R.string.apps_restart)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun showAccounts() {
        val context = requireContext()
        AccountSheet(context, { target, sheet ->
            withDisconnected {
                if (Fox.switchAccount(context, target.id)) {
                    sheet.dismiss()
                    paint()
                } else toast(R.string.account_wait)
            }
        }, { sheet ->
            withDisconnected {
                sheet.dismiss()
                FoxVpnService.stop(context)
                Fox.cancelLogin()
                startActivity(Intent(context, LoginActivity::class.java).putExtra(LoginActivity.ADD_ACCOUNT, true))
            }
        }).show(FoxStore.accounts(context))
    }

    private fun withDisconnected(action: () -> Unit) {
        if (FoxVpnService.alwaysOn()) {
            showAlwaysOnSettings(requireContext())
            return
        }
        if (Fox.busy) {
            toast(R.string.account_wait)
            return
        }
        if (FoxLink.connected || FoxLink.connecting || FoxVpnService.isAlive()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.manage_accounts)
                .setMessage(R.string.switch_account_disconnect)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ ->
                    if (Fox.busy) toast(R.string.account_wait) else action()
                }
                .show()
        } else {
            action()
        }
    }

    private fun askSignOut() {
        if (FoxVpnService.alwaysOn()) {
            showAlwaysOnSettings(requireContext())
            return
        }
        if (Fox.busy) {
            toast(R.string.account_wait)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.fox_sign_out)
            .setMessage(R.string.sign_out_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.fox_sign_out) { _, _ ->
                if (Fox.busy) toast(R.string.account_wait) else signOut()
            }
            .show()
    }

    private fun signOut() {
        val context = requireContext()
        Fox.signOut(context)
        val intent = Intent(context, LoginActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        activity?.finish()
    }

    private fun checkUpdates() {
        if (updateChecking) return
        updateChecking = true
        paint()
        UpdateChecker.check(requireContext(), notify = false) updateResult@{ result ->
            if (!isAdded || view == null) return@updateResult
            updateChecking = false
            when (result) {
                is UpdateChecker.Result.Available -> {
                    Store.setUpdate(requireContext(), result.version, result.url, result.sha256, result.size)
                    paint()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.update_title)
                        .setMessage(getString(R.string.update_message, result.version))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.update_action) { _, _ -> openUrl(result.url) }
                        .show()
                }
                is UpdateChecker.Result.Current -> {
                    paint()
                    toast(R.string.update_latest)
                }
                is UpdateChecker.Result.Failed -> {
                    paint()
                    toast(R.string.update_failed)
                }
            }
        }
    }

    private fun pickLanguage() {
        showLanguagePicker(requireActivity())
    }

    private fun pickTheme() {
        val items = arrayOf(getString(R.string.theme_auto), getString(R.string.theme_light), getString(R.string.theme_dark))
        val values = arrayOf("auto", "light", "dark")
        val current = values.indexOf(Store.theme(requireContext())).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.theme)
            .setSingleChoiceItems(items, current) { dialog, which ->
                Store.setTheme(requireContext(), values[which])
                dialog.dismiss()
                AppCompatDelegate.setDefaultNightMode(nightModeOf(values[which]))
                paint()
            }
            .show()
    }

    private fun showDonation() {
        val context = requireContext()
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        content.addView(TextView(context).apply {
            text = getString(R.string.donate_message)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        })
        content.addView(TextView(context).apply {
            text = tonAddress
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextIsSelectable(true)
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.donate_section)
            .setView(content)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.donate_copy) { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TON", tonAddress))
                toast(R.string.donate_copied)
            }
            .show()
    }

    private fun ltr(value: String): String =
        BidiFormatter.getInstance(resources.configuration.locales[0]).unicodeWrap(value, TextDirectionHeuristics.LTR)

    private fun openProject() = openUrl("https://github.com/bardia049b-stack/Gozar1")

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(R.string.import_failed)
        }
    }

    private fun showLegalNotice() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.legal_title)
            .setMessage(R.string.legal_notice)
            .setNeutralButton(R.string.source_code) { _, _ -> openProject() }
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun toast(res: Int) {
        try {
            android.widget.Toast.makeText(requireContext(), res, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
        }
    }

    private fun plain(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unit = -1
        while (size >= 1024 && unit < units.size - 1) {
            size /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", size, units[unit])
    }
}
