package com.gozar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

object FoxLink {

    const val SOCKS_PORT = 10810
    const val WEB_PORT = 10811
    const val DNS_PORT = 10812

    @Volatile var connected = false
    @Volatile var connecting = false
    @Volatile var error: String = ""
    @Volatile var location: FoxLocation? = null
    @Volatile var exitIp: String = ""
    @Volatile var since: Long = 0L
    private val downBytes = AtomicLong()
    private val upBytes = AtomicLong()
    val down: Long get() = downBytes.get()
    val up: Long get() = upBytes.get()
    @Volatile var downRate: Double = 0.0
    @Volatile var upRate: Double = 0.0

    private var socks: FoxProxy? = null
    private var web: FoxProxy? = null
    private var dns: FoxProxy? = null
    private val historyLock = Any()
    private val history = ArrayDeque<Pair<Float, Float>>()
    private val stateLock = Any()
    private var generation = 0L
    private val main = Handler(Looper.getMainLooper())
    private var ticker: Thread? = null
    private var renewer: Thread? = null

    var onChange: (() -> Unit)? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun watch(listener: () -> Unit) { listeners.addIfAbsent(listener) }
    fun unwatch(listener: () -> Unit) { listeners.remove(listener) }

    fun tick() {
        main.post {
            onChange?.invoke()
            for (listener in listeners) listener()
            FoxVpnService.updateNotification()
        }
    }

    fun fault(message: String) {
        error = message
        tick()
    }

    fun count(inbound: Boolean, bytes: Long) {
        if (bytes <= 0) return
        if (inbound) downBytes.addAndGet(bytes) else upBytes.addAndGet(bytes)
    }

    fun trafficHistory(): List<Pair<Float, Float>> = synchronized(historyLock) { history.toList() }

    fun start(c: Context) {
        val app = c.applicationContext
        val attempt = synchronized(stateLock) {
            if (connected || connecting) return
            generation += 1
            connecting = true
            error = ""
            generation
        }
        tick()
        thread {
            try {
                FoxRoute.apply(Store.bypassLan(app), Store.bypassList(app))
                FoxRoute.applyChain(Store.chain(app))
                FoxTunnel.resetTransport()
                val access = Fox.validAccess(app)
                if (!current(attempt)) return@thread
                var pass = FoxApi.pass(access.token)
                if (!storePass(app, pass, access, attempt)) return@thread
                if (Fox.servers.isEmpty()) {
                    val servers = FoxApi.servers()
                    if (!current(attempt)) return@thread
                    Fox.servers = servers
                }
                val chosen = Fox.selectedLocation(app) ?: throw FoxError("No server available")
                val lower = pass.limit > 0 && pass.remaining <= 0
                if (lower) throw FoxError("Your 50 GB for this month is used up")
                synchronized(stateLock) {
                    if (attempt != generation) return@thread
                    location = chosen
                    socks = FoxProxy(SOCKS_PORT) { FoxStore.pass(app).ifEmpty { null } }
                    web = FoxProxy(WEB_PORT) { FoxStore.pass(app).ifEmpty { null } }
                    dns = FoxProxy(DNS_PORT, forceTunnel = true, countTraffic = false) { FoxStore.pass(app).ifEmpty { null } }
                    if (socks?.start() != true || web?.start() != true || dns?.start() != true) {
                        throw FoxError("The local proxy did not start")
                    }
                }
                try {
                    FoxTunnel.verify(chosen, pass.token)
                } catch (e: Exception) {
                    if (!FoxTunnel.passRejected(e)) throw e
                    if (!current(attempt)) return@thread
                    val renewed = Fox.validAccess(app)
                    pass = FoxApi.pass(renewed.token)
                    if (!storePass(app, pass, renewed, attempt)) return@thread
                    FoxTunnel.verify(chosen, pass.token)
                }
                synchronized(stateLock) {
                    if (attempt != generation) return@thread
                    exitIp = ""
                    connected = true
                    connecting = false
                    since = System.currentTimeMillis()
                    downBytes.set(0L)
                    upBytes.set(0L)
                    synchronized(historyLock) { history.clear() }
                    error = ""
                    if (Store.recents(app)) Store.setLastServer(app, chosen.host)
                    startTicker(attempt)
                    startRenewal(app, attempt)
                }
                tick()
                FoxVpnService.activateRoot()
                detectExit(chosen, pass.token, attempt)
            } catch (e: Exception) {
                synchronized(stateLock) {
                    if (attempt != generation) return@thread
                    connected = false
                    connecting = false
                    error = if (FoxTunnel.isTlsFailure(e)) {
                        app.getString(R.string.fox_secure_connection_error)
                    } else {
                        e.message ?: "Could not connect"
                    }
                    stopServers()
                }
                tick()
                if (FoxVpnService.alwaysOn()) {
                    main.postDelayed({
                        if (current(attempt) && FoxVpnService.isAlive() && FoxVpnService.alwaysOn() && !connected && !connecting) start(app)
                    }, 15_000L)
                }
            }
        }
    }

    private fun detectExit(location: FoxLocation, token: String, attempt: Long) {
        thread(isDaemon = true, name = "fox-exit-ip") {
            val value = try { FoxTunnel.probe(location, token) } catch (_: Exception) { "" }
            if (value.isEmpty()) return@thread
            val changed = synchronized(stateLock) {
                if (attempt != generation || !connected) false else {
                    exitIp = value
                    true
                }
            }
            if (changed) tick()
        }
    }

    private fun current(attempt: Long): Boolean = synchronized(stateLock) { attempt == generation }

    private fun storePass(app: Context, pass: FoxPass, access: FoxAccess, attempt: Long, live: Boolean = false): Boolean =
        synchronized(stateLock) {
            if (attempt != generation || (live && !connected)) false
            else FoxStore.saveUsage(app, pass, access.id, access.revision)
        }

    fun stop() = stopAttempt(null, "")

    private fun stopAttempt(attempt: Long?, reason: String) {
        synchronized(stateLock) {
            if (attempt != null && attempt != generation) return
            generation += 1
            stopServers()
            connected = false
            connecting = false
            exitIp = ""
            downRate = 0.0
            upRate = 0.0
            since = 0L
            error = reason
            stopTicker()
            stopRenewal()
        }
        tick()
    }

    private fun stopServers() {
        socks?.stop()
        web?.stop()
        dns?.stop()
        socks = null
        web = null
        dns = null
    }

    private fun startTicker(attempt: Long) {
        stopTicker()
        ticker = thread(isDaemon = true, name = "fox-meter") {
            var lastDown = down
            var lastUp = up
            var lastAt = SystemClock.elapsedRealtime()
            while (current(attempt) && connected) {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
                if (!current(attempt) || !connected) break
                if (!FoxVpnService.directRoutingAllowed()) DirectPipe.closeAll()
                if (!FoxVpnService.isAlive()) {
                    stopAttempt(attempt, "The system closed the tunnel")
                    break
                }
                val now = SystemClock.elapsedRealtime()
                val span = (now - lastAt).coerceAtLeast(1)
                val totalDown = down
                val totalUp = up
                val applied = synchronized(stateLock) {
                    if (attempt != generation || !connected) false else {
                        downRate = (totalDown - lastDown).coerceAtLeast(0) * 1000.0 / span
                        upRate = (totalUp - lastUp).coerceAtLeast(0) * 1000.0 / span
                        synchronized(historyLock) {
                            history.addLast(downRate.coerceAtLeast(0.0).toFloat() to upRate.coerceAtLeast(0.0).toFloat())
                            while (history.size > 60) history.removeFirst()
                        }
                        true
                    }
                }
                if (!applied) break
                lastDown = totalDown
                lastUp = totalUp
                lastAt = now
                tick()
            }
        }
    }

    private fun startRenewal(app: Context, attempt: Long) {
        stopRenewal()
        renewer = thread(isDaemon = true, name = "fox-renew") {
            var retry = 0L
            while (current(attempt) && connected) {
                val wait = if (retry > 0) retry else FoxStore.renewalDelay(FoxStore.passUntil(app))
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    break
                }
                if (!current(attempt) || !connected) break
                try {
                    val access = Fox.validAccess(app)
                    val pass = FoxApi.pass(access.token)
                    if (!storePass(app, pass, access, attempt, true)) break
                    retry = 0L
                    Fox.load(app)
                    tick()
                } catch (_: Exception) {
                    retry = if (retry == 0L) 15_000L else (retry * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    private fun stopRenewal() {
        renewer?.interrupt()
        renewer = null
    }

    private fun stopTicker() {
        ticker?.interrupt()
        ticker = null
    }

    fun elapsed(): Long = if (connected) (System.currentTimeMillis() - since) / 1000 else 0L
}
