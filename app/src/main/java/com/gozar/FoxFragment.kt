package com.gozar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class FoxFragment : Fragment() {

    private lateinit var account: TextView
    private lateinit var state: TextView
    private lateinit var quotaText: TextView
    private lateinit var quotaBar: ProgressBar
    private lateinit var reset: TextView
    private lateinit var connect: ConnectControl
    private lateinit var errorView: TextView
    private lateinit var map: View
    private lateinit var liveRow: View
    private lateinit var liveFlag: CountryFlagView
    private lateinit var liveIp: TextView
    private lateinit var livePing: TextView
    private lateinit var stats: View
    private lateinit var down: RollingValueView
    private lateinit var up: RollingValueView
    private lateinit var total: RollingValueView
    private lateinit var session: TextView
    private lateinit var chart: TrafficChartView
    private lateinit var chartCard: View
    private lateinit var metricIcons: List<MetricIconView>
    private var previousMetrics = listOf<Long>()
    private lateinit var serverFlag: CountryFlagView
    private lateinit var serverName: TextView
    private lateinit var serverMeta: TextView
    private var measured = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestVpnPermission()
    }

    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            FoxVpnService.start(requireContext())
        } else {
            showError(getString(R.string.fox_perm_denied))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_fox, container, false)
        account = view.findViewById(R.id.foxAccount)
        state = view.findViewById(R.id.foxState)
        quotaText = view.findViewById(R.id.foxQuotaText)
        quotaBar = view.findViewById(R.id.foxQuotaBar)
        reset = view.findViewById(R.id.foxReset)
        connect = view.findViewById(R.id.foxConnect)
        errorView = view.findViewById(R.id.foxError)
        map = view.findViewById(R.id.foxMap)
        liveRow = view.findViewById(R.id.foxLive)
        liveFlag = view.findViewById(R.id.foxLiveFlag)
        liveIp = view.findViewById(R.id.foxLiveIp)
        livePing = view.findViewById(R.id.foxLivePing)
        stats = view.findViewById(R.id.foxStats)
        down = view.findViewById(R.id.foxDown)
        up = view.findViewById(R.id.foxUp)
        total = view.findViewById(R.id.foxTotal)
        session = view.findViewById(R.id.foxSession)
        chart = view.findViewById(R.id.foxChart)
        chartCard = view.findViewById(R.id.foxChartCard)
        metricIcons = listOf(R.id.foxDownIcon, R.id.foxUpIcon, R.id.foxTotalIcon, R.id.foxSessionIcon).map { view.findViewById(it) }
        val colors = listOf(R.color.kind_down_container, R.color.kind_up_container,
            R.color.kind_total_container, R.color.kind_proxy_container)
        metricIcons.forEachIndexed { index, icon ->
            icon.setAppearance(requireContext().getColor(colors[index]), index)
        }
        serverFlag = view.findViewById(R.id.foxServerFlag)
        serverName = view.findViewById(R.id.foxServerName)
        serverMeta = view.findViewById(R.id.foxServerMeta)

        connect.tune(
            requireContext().getColor(R.color.fox_red),
            requireContext().getColor(R.color.fox_on_red),
            requireContext().getColor(R.color.m3_ok),
            requireContext().getColor(R.color.m3_on_ok),
            requireContext().getColor(R.color.m3_error),
            requireContext().getColor(R.color.m3_on_error)
        )

        view.findViewById<View>(R.id.foxAccountButton).setOnClickListener { accountMenu() }
        connect.setOnClickListener { toggle() }
        map.setOnClickListener { startActivity(Intent(requireContext(), RouteMapActivity::class.java)) }
        view.findViewById<View>(R.id.foxServerRow).setOnClickListener { pickServer() }
        return view
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        Fox.onChange = { if (isAdded) render() }
        FoxLink.onChange = { if (isAdded) render() }
        Fox.load(requireContext())
        Fox.loadServers(requireContext())
        render()
        val host = activity
        if (host?.intent?.getBooleanExtra(MainActivity.AUTO_CONNECT, false) == true) {
            host.intent.removeExtra(MainActivity.AUTO_CONNECT)
            view.post { if (isAdded) requestVpnPermission() }
        }
    }

    override fun onDestroyView() {
        Fox.onChange = null
        FoxLink.onChange = null
        super.onDestroyView()
    }

    private fun toggle() {
        val c = requireContext()
        when {
            !Fox.signedIn(c) -> {
                openLogin()
                return
            }
            FoxLink.connected || FoxLink.connecting || FoxVpnService.isAlive() -> disconnect()
            else -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !Store.notificationAsked(c)) {
                    Store.setNotificationAsked(c)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else requestVpnPermission()
            }
        }
    }

    private fun requestVpnPermission() {
        if (!isAdded) return
        val c = requireContext()
        val ask = VpnService.prepare(c)
        if (ask != null) permission.launch(ask) else FoxVpnService.start(c)
    }

    private fun disconnect() {
        val c = requireContext()
        if (FoxVpnService.alwaysOn()) {
            showAlwaysOnSettings(c)
            return
        }
        FoxVpnService.stop(c)
        FoxLink.stop()
        render()
    }

    private fun openLogin() {
        val intent = Intent(requireContext(), LoginActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        activity?.finish()
    }

    private fun pickServer() {
        FoxServers.show(requireContext()) {
            if (isAdded) render()
        }
    }

    private fun accountMenu() {
        val c = requireContext()
        if (!Fox.signedIn(c)) {
            openLogin()
            return
        }
        MaterialAlertDialogBuilder(c)
            .setTitle(FoxStore.email(c))
            .setItems(arrayOf(getString(R.string.fox_refresh), getString(R.string.fox_sign_out))) { _, which ->
                if (which == 0) {
                    Fox.refreshUsage(c)
                } else {
                    if (FoxVpnService.alwaysOn()) {
                        showAlwaysOnSettings(c)
                        return@setItems
                    }
                    FoxVpnService.stop(c)
                    FoxLink.stop()
                    Fox.signOut(c)
                    openLogin()
                }
            }
            .show()
    }

    private fun showError(text: String) {
        if (!isAdded) return
        errorView.visibility = View.VISIBLE
        errorView.text = text
    }

    private fun maybeMeasure() {
        if (measured || Fox.busy) return
        if (Fox.servers.isEmpty()) return
        measured = true
        if (!Fox.hasDelays()) Fox.measure(requireContext())
    }

    private fun render() {
        if (!isAdded) return
        val c = requireContext()
        val mail = FoxStore.email(c)
        val signed = Fox.signedIn(c)
        val live = FoxLink.connected
        val busy = FoxLink.connecting || (FoxVpnService.isAlive() && !live && FoxLink.error.isEmpty())

        account.text = if (mail.isEmpty()) getString(R.string.fox_signed_out) else mail

        quotaBar.visibility = if (Fox.limit > 0) View.VISIBLE else View.GONE
        if (Fox.limit > 0) {
            val used = (Fox.limit - Fox.remaining).coerceAtLeast(0)
            quotaText.text = getString(R.string.fox_quota_value, fmtBytes(used), fmtBytes(Fox.limit))
            quotaBar.progress = ((used * 100) / Fox.limit).toInt().coerceIn(0, 100)
            reset.visibility = View.VISIBLE
            reset.text = if (Fox.reset.isEmpty()) "" else getString(R.string.fox_reset, resetText(Fox.reset))
        } else {
            quotaText.text = getString(R.string.fox_usage_pending)
            quotaBar.progress = 0
            reset.visibility = View.GONE
        }

        state.text = when {
            live -> getString(R.string.fox_connected)
            busy -> getString(R.string.fox_connecting)
            else -> getString(R.string.fox_disconnected)
        }

        val wanted = when {
            busy -> ConnectControl.BUSY
            live -> ConnectControl.ON
            FoxLink.error.isNotEmpty() -> ConnectControl.ERROR
            else -> ConnectControl.OFF
        }
        if (connect.state != wanted) connect.state = wanted
        connect.elapsedSeconds = if (live) FoxLink.elapsed().toInt() else 0

        val failure = when {
            FoxLink.error.isNotEmpty() && !live -> FoxLink.error
            else -> ""
        }
        if (failure.isEmpty()) errorView.visibility = View.GONE else showError(failure)

        map.visibility = if (live) View.VISIBLE else View.GONE
        liveRow.visibility = if (live) View.VISIBLE else View.GONE
        if (live) {
            val place = FoxLink.location
            liveFlag.countryCode = place?.code
            liveIp.text = FoxLink.exitIp.ifEmpty { "…" }
            val delay = if (place != null) Fox.delayOf(place) else -1
            livePing.text = if (delay > 0) "$delay ms" else ""
        }
        stats.visibility = if (live) View.VISIBLE else View.GONE
        chartCard.visibility = if (live) View.VISIBLE else View.GONE
        if (live) {
            chart.setSamples(FoxLink.trafficHistory())
            val metrics = listOf(FoxLink.downRate.toLong(), FoxLink.upRate.toLong(), FoxLink.down + FoxLink.up, FoxLink.elapsed())
            if (previousMetrics.size == metrics.size) {
                metrics.forEachIndexed { index, value ->
                    if (value != previousMetrics[index]) metricIcons[index].changeShape()
                }
            }
            previousMetrics = metrics
            down.setValue(fmtSpeed(FoxLink.downRate))
            up.setValue(fmtSpeed(FoxLink.upRate))
            total.setValue(fmtBytes(FoxLink.down + FoxLink.up))
            session.text = fmtTime(FoxLink.elapsed())
        } else previousMetrics = emptyList()

        paintServer(c)
        maybeMeasure()
    }

    private fun paintServer(c: Context) {
        val location = Fox.selectedLocation(c)
        if (location == null) {
            serverFlag.visibility = View.INVISIBLE
            serverName.text = getString(R.string.fox_server_empty)
            serverMeta.text = getString(R.string.fox_servers_sub)
            return
        }
        serverFlag.visibility = View.VISIBLE
        serverFlag.countryCode = location.code
        val place = when {
            location.city.isEmpty() || location.city.equals(location.country, true) -> location.country
            else -> location.country + " · " + location.city
        }
        serverName.text = place
        val delay = Fox.delayOf(location)
        val clock = when {
            delay > 0 -> "$delay ms"
            Fox.busy -> getString(R.string.fox_working)
            else -> getString(R.string.fox_ping_wait)
        }
        serverMeta.text = location.host + " · " + clock
    }

    private fun resetText(raw: String): String {
        val plain = raw.trim()
        val seconds = plain.toLongOrNull()
        val millis = when {
            seconds != null && seconds > 0 -> seconds * 1000L
            else -> try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(plain.take(19))?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
        if (millis <= 0L) return plain
        return android.text.format.DateFormat.getMediumDateFormat(context ?: return plain)
            .format(java.util.Date(millis))
    }
}
