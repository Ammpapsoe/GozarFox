package com.gozar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import kotlin.concurrent.thread
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class FoxVpnService : VpnService() {

    private var descriptor: ParcelFileDescriptor? = null

    private var stack: FoxStack? = null
    @Volatile private var resolver: FoxResolver? = null
    private var rootSession: RootSession? = null
    private var startFailure = ""
    private var systemStarted = false
    @Volatile private var focusProtocol = 0
    private var lastNoticeAt = 0L
    private var lastNoticeState = ""

    override fun onCreate() {
        super.onCreate()
        alive = true
        instance = this
        thread(isDaemon = true, name = "focus-capability") {
            val protocol = FocusIsland.permittedProtocol(applicationContext)
            Handler(Looper.getMainLooper()).post {
                if (instance === this) {
                    focusProtocol = protocol
                    refreshNotification(true)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == SERVICE_INTERFACE) systemStarted = true
        if (intent?.action == STOP) {
            if (alwaysOn()) {
                refreshNotification(true)
                return START_STICKY
            }
            close()
            return START_NOT_STICKY
        }
        foreground()
        open()
        return START_STICKY
    }

    private fun open() {
        if (descriptor != null) return
        FoxRoute.apply(Store.bypassLan(this), Store.bypassList(this))
        FoxRoute.applyChain(Store.chain(this))
        if (!FoxLink.prepare(this)) {
            abortStart(getString(R.string.fox_no_device))
            return
        }
        val mtu = FoxNetwork.mtu(this)
        val builder = Builder()
            .setSession(getString(R.string.fox_title))
            .addAddress(LOCAL_ADDRESS, 32)
            .addAddress(LOCAL_ADDRESS_V6, 128)
            .addDnsServer(DNS_ADDRESS)
            .addRoute("0.0.0.0", 0)
            .addRoute(DNS_ADDRESS, 32)
            .addRoute("::", 0)
            .setMtu(mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", FoxLink.webPort))
        }
        val appsReady = applyAppSelection(
            Store.apps(this),
            packageName,
            { builder.addAllowedApplication(it) },
            { builder.addDisallowedApplication(it) }
        )
        if (!appsReady) {
            abortStart(getString(R.string.apps_no_valid))
            return
        }
        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
        if (fd == null) {
            abortStart(getString(R.string.fox_no_device))
            return
        }
        descriptor = fd
        val app = applicationContext
        val resolver = FoxResolver(
            FoxLink.dnsPort,
            endpoints = DnsPolicy.endpoints(Store.dnsProfile(this), Store.customDns(this), Store.blockAds(this))
        )
        this.resolver = resolver
        val engine = FoxStack(
            FileInputStream(fd.fileDescriptor),
            FileOutputStream(fd.fileDescriptor),
            addressOf(LOCAL_ADDRESS),
            resolver,
            { host, address, port ->
                if (directRoutingAllowed() && FoxRoute.direct(host)) {
                    val pipe = FoxSocketPipe.connect(address, port) ?: throw IOException("direct connection failed")
                    DirectPipe(pipe) { directRoutingAllowed() }
                } else {
                    val location = FoxLink.location ?: throw IOException("not connected yet")
                    val token = FoxLink.pass(app)
                    if (token.isEmpty()) throw IOException("no pass")
                    FoxTunnel.open(location, host, port, token)
                }
            },
            { inbound, size -> FoxLink.count(inbound, size) },
            mtu
        )
        engine.start()
        stack = engine
        FoxLink.start(this)
    }

    override fun onRevoke() {
        close()
        super.onRevoke()
    }

    override fun onDestroy() {
        alive = false
        if (instance === this) instance = null
        release()
        if (startFailure.isNotEmpty()) FoxLink.fault(startFailure)
        super.onDestroy()
    }

    private fun abortStart(message: String) {
        startFailure = message
        close()
        FoxLink.fault(message)
    }

    private fun close() {
        release()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        stopSelf()
    }

    private fun release() {
        val root = synchronized(this) {
            val current = rootSession
            rootSession = null
            rootActive = false
            current
        }
        root?.stop()
        DirectPipe.closeAll()
        try {
            stack?.stop()
        } catch (e: Exception) {
        }
        stack = null
        resolver = null
        try {
            descriptor?.close()
        } catch (e: Exception) {
        }
        descriptor = null
        FoxLink.stop()
    }

    private fun activateRoot() {
        if (!Store.rootRouting(this) || !FoxLink.connected) return
        val current = synchronized(this) {
            if (rootSession != null) return
            val fd = descriptor ?: return
            var made: RootSession? = null
            val session = RootSession.create(this, fd) { active ->
                synchronized(this) {
                    if (rootSession !== made) return@create
                    rootActive = active
                    if (!active && FoxLink.connected && Store.rootRouting(this)) {
                        FoxLink.fault(getString(R.string.root_unavailable))
                    }
                }
            }
            if (session == null) {
                FoxLink.fault(getString(R.string.root_unavailable))
                return
            }
            made = session
            rootSession = session
            session
        }
        current.start()
    }

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = makeNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY, notification)
        }
    }

    private fun makeNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FoxVpnService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val connected = FoxLink.connected
        val location = FoxLink.location?.country.orEmpty().ifBlank { getString(R.string.fox_title) }
        val speed = getString(R.string.notification_speed, fmtSpeed(FoxLink.downRate), fmtSpeed(FoxLink.upRate))
        val total = getString(R.string.notification_usage, fmtBytes(FoxLink.down), fmtBytes(FoxLink.up))
        val detail = when {
            connected -> speed
            FoxLink.error.isNotEmpty() -> FoxLink.error
            else -> getString(R.string.notification_connecting)
        }
        val title = if (connected) getString(R.string.notification_connected, location) else getString(R.string.fox_title)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fox_note)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (connected) "$detail\n$total" else detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
        if (!alwaysOn()) builder.addAction(0, getString(R.string.disconnect), stop)
        if (connected && FoxLink.since > 0) {
            builder.setWhen(FoxLink.since).setShowWhen(true).setUsesChronometer(true)
        } else builder.setShowWhen(false)
        if (Build.VERSION.SDK_INT >= 36 && connected) {
            builder.setRequestPromotedOngoing(true)
                .setShortCriticalText(getString(R.string.focus_chip, compactRate(FoxLink.downRate)))
        }
        val notification = builder.build()
        if (connected) FocusIsland.decorate(this, notification, focusProtocol, getString(R.string.fox_title), getString(R.string.focus_chip, compactRate(FoxLink.downRate)))
        return notification
    }

    private fun compactRate(rate: Double): String {
        val value = rate.coerceAtLeast(0.0)
        return when {
            value < 1000 -> "${value.toInt()} B/s"
            value < 999_500 -> String.format(Locale.US, "%.1fK/s", value / 1000)
            value < 999_500_000 -> String.format(Locale.US, "%.1fM/s", value / 1_000_000)
            else -> String.format(Locale.US, "%.1fG/s", value / 1_000_000_000)
        }
    }

    private fun refreshNotification(force: Boolean = false) {
        if (!alive || descriptor == null) return
        val state = "${FoxLink.connected}|${FoxLink.connecting}|${FoxLink.error}|$focusProtocol"
        val now = SystemClock.elapsedRealtime()
        if (!force && state == lastNoticeState && now - lastNoticeAt < 2000) return
        lastNoticeState = state
        lastNoticeAt = now
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFY, makeNotification())
        } catch (_: Exception) {
        }
    }

    companion object {
        const val LOCAL_ADDRESS = "10.111.0.2"
        const val LOCAL_ADDRESS_V6 = "fd00:111:0:2::2"
        const val DNS_ADDRESS = "10.111.0.53"
        private const val CHANNEL = "fox_vpn"
        private const val NOTIFY = 7412
        private const val STOP = "com.gozar.FOX_STOP"

        @Volatile private var alive = false
        @Volatile var rootActive = false
            private set
        @Volatile private var instance: FoxVpnService? = null

        internal fun applyAppSelection(
            only: Set<String>,
            self: String,
            allow: (String) -> Unit,
            disallow: (String) -> Unit
        ): Boolean {
            if (only.isEmpty()) {
                return try {
                    disallow(self)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            var applied = 0
            for (target in only) {
                if (target == self) continue
                try {
                    allow(target)
                    applied++
                } catch (e: Exception) {
                }
            }
            return applied > 0
        }

        fun activateRoot() {
            instance?.activateRoot()
        }

        fun updateNotification() {
            instance?.refreshNotification()
        }

        fun isAlive(): Boolean = alive

        fun lockdownState(): Int {
            val service = instance ?: return 0
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || service.descriptor == null) return 0
            return try {
                when {
                    service.isLockdownEnabled -> 3
                    service.isAlwaysOn -> 2
                    else -> 1
                }
            } catch (_: Exception) {
                0
            }
        }

        fun alwaysOn(): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return service.systemStarted
            val state = lockdownState()
            return state >= 2 || (state == 0 && service.systemStarted)
        }

        fun directAddress(host: String): String? = instance?.resolver?.directAddress(host)

        /** Diagnostic DNS uses the service resolver and its force-tunnel proxy,
         * not system DNS from this VPN-excluded process. */
        fun diagnosticDns(host: String): FoxLeakGuard.Lookup {
            val resolver = instance?.resolver ?: return FoxLeakGuard.Lookup(FoxLeakGuard.LookupCode.UNAVAILABLE)
            return FoxLeakGuard.lookupA(resolver, host)
        }

        fun directRoutingAllowed(): Boolean {
            val service = instance ?: return false
            if (service.descriptor == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            return try { !service.isLockdownEnabled } catch (_: Exception) { false }
        }

        fun lockdownActive(): Boolean = instance != null && !directRoutingAllowed()

        fun addressOf(text: String): Int {
            val parts = text.split(".")
            if (parts.size != 4) return 0
            var value = 0
            for (part in parts) value = (value shl 8) or (part.toIntOrNull()?.and(0xFF) ?: 0)
            return value
        }

        fun start(c: Context) {
            val intent = Intent(c, FoxVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(intent) else c.startService(intent)
        }

        fun stop(c: Context) {
            val running = instance
            try {
                running?.close()
            } catch (e: Exception) {
            }
            try {
                c.stopService(Intent(c, FoxVpnService::class.java))
            } catch (e: Exception) {
            }
            FoxLink.stop()
        }
    }
}
