package com.gozar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

object FoxLink {

    @Volatile var socksPort: Int = 0
        private set
    @Volatile var webPort: Int = 0
        private set
    @Volatile var dnsPort: Int = 0
        private set

    @Volatile var connected = false
    @Volatile var connecting = false
    @Volatile var error: String = ""
    @Volatile var location: FoxLocation? = null
    @Volatile var exitIp: String = ""
    @Volatile var since: Long = 0L
    private val downBytes = AtomicLong()
    private val upBytes = AtomicLong()
    private val lastTraffic = AtomicLong()
    private val exitAttempt = AtomicLong(-1)
    private val renewLock = Any()
    private val geoRefresh = AtomicLong()
    val down: Long get() = downBytes.get()
    val up: Long get() = upBytes.get()
    @Volatile var downRate: Double = 0.0
    @Volatile var upRate: Double = 0.0

    private var prepared = false
    private var socks: FoxProxy? = null
    private var web: FoxProxy? = null
    private var dns: FoxProxy? = null
    private val historyLock = Any()
    private val history = ArrayDeque<Pair<Float, Float>>()
    private val attempts = FoxAttemptFence()
    private val stateLock = attempts.monitor
    private val generation: Long get() = attempts.generation
    private var transportTicket: Long? = null
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
        lastTraffic.set(System.currentTimeMillis())
    }

    fun trafficHistory(): List<Pair<Float, Float>> = synchronized(historyLock) { history.toList() }

    fun prepare(c: Context): Boolean {
        val app = c.applicationContext
        return synchronized(stateLock) {
            if (prepared) return true
            socks = FoxProxy(0) { pass(app).ifEmpty { null } }
            web = FoxProxy(0) { pass(app).ifEmpty { null } }
            dns = FoxProxy(0, forceTunnel = true, countTraffic = false) { pass(app).ifEmpty { null } }
            if (socks?.start() != true || web?.start() != true || dns?.start() != true) {
                stopServers()
                return false
            }
            socksPort = socks?.boundPort() ?: 0
            webPort = web?.boundPort() ?: 0
            dnsPort = dns?.boundPort() ?: 0
            prepared = socksPort > 0 && webPort > 0 && dnsPort > 0
            if (prepared && !connected) pauseServers()
            prepared
        }
    }

    fun start(c: Context) {
        val app = c.applicationContext
        val attempt = synchronized(stateLock) {
            if (connected || connecting) return
            val next = attempts.advance()
            FoxLeakGuard.clearReport()
            connecting = true
            error = ""
            next
        }
        tick()
        thread {
            try {
                // A repackaged build gets no tunnel.
                if (!TamperCheck.verify(app)) throw FoxError(app.getString(R.string.tamper_detected))
                val bypassLan = Store.bypassLan(app)
                val bypassList = Store.bypassList(app)
                val chain = Store.chain(app)
                // Check and mutate under the same monitor used by stop/start;
                // an obsolete worker cannot reset a newer transport.
                val ticket = attempts.ifCurrent(attempt) {
                    FoxRoute.apply(bypassLan, bypassList)
                    FoxRoute.applyChain(chain)
                    FoxTunnel.startTransport().also { transportTicket = it }
                } ?: return@thread
                val access = Fox.validAccess(app)
                if (!current(attempt)) return@thread
                var pass = FoxApi.pass(access.token)
                if (!storePass(app, pass, access, attempt)) return@thread
                if (Fox.servers.isEmpty()) {
                    val servers = FoxApi.servers()
                    if (attempts.ifCurrent(attempt) { Fox.servers = servers } == null) return@thread
                }
                val candidates = Fox.autoCandidates(app)
                if (candidates.isEmpty()) throw FoxError("No server available")
                val lower = pass.limit > 0 && pass.remaining <= 0
                if (lower) throw FoxError("Your 50 GB for this month is used up")
                val prepared = attempts.ifCurrent(attempt) { prepare(app) } ?: return@thread
                if (!prepared) throw FoxError("The local proxy did not start")
                var chosen: FoxLocation? = null
                var lastError: Exception? = null
                var renewed = false
                for (candidate in candidates) {
                    if (!current(attempt)) return@thread
                    synchronized(stateLock) {
                        if (attempt != generation) return@thread
                        location = candidate
                    }
                    try {
                        try {
                            FoxTunnel.verify(candidate, pass.token, ticket)
                        } catch (e: Exception) {
                            if (!FoxTunnel.passRejected(e) || renewed) throw e
                            renewed = true
                            if (!current(attempt)) return@thread
                            val fresh = Fox.validAccess(app)
                            pass = FoxApi.pass(fresh.token)
                            if (!storePass(app, pass, fresh, attempt)) return@thread
                            FoxTunnel.verify(candidate, pass.token, ticket)
                        }
                        chosen = candidate
                        break
                    } catch (e: Exception) {
                        Log.w("Gozar", "Server ${candidate.host} failed during verification", e)
                        if (FoxTunnel.isTlsFailure(e) || FoxTunnel.passRejected(e) || e is FoxError) throw e
                        lastError = e
                    }
                }
                val verified = chosen ?: throw lastError?.let {
                    FoxError("Server ${location?.host ?: "unknown"} failed because of ${it.message ?: it.javaClass.simpleName}")
                } ?: FoxError("No server available")
                synchronized(stateLock) {
                    if (attempt != generation) return@thread
                    exitIp = ""
                    connected = true
                    connecting = false
                    resumeServers()
                    since = System.currentTimeMillis()
                    downBytes.set(0L)
                    upBytes.set(0L)
                    lastTraffic.set(System.currentTimeMillis())
                    synchronized(historyLock) { history.clear() }
                    error = ""
                    if (Store.recents(app)) Store.setLastServer(app, verified.host)
                    // The tunnel is proven up: this build passes its update trial.
                    try {
                        App.markTunnelHealthy(app)
                    } catch (_: Exception) {
                    }
                    startTicker(attempt)
                    startRenewal(app, attempt, ticket)
                }
                tick()
                FoxVpnService.activateRoot()
                detectExit(verified, pass.token, attempt, ticket)
                geoRefresh.set(System.currentTimeMillis())
                thread(isDaemon = true, name = "fox-geo") {
                    FoxGeo.refresh(verified, pass.token, ticket)
                }
            } catch (e: Exception) {
                synchronized(stateLock) {
                    if (attempt != generation) return@thread
                    connected = false
                    connecting = false
                    transportTicket = null
                    FoxTunnel.stopTransport()
                    error = if (FoxTunnel.isTlsFailure(e)) {
                        app.getString(R.string.fox_secure_connection_error)
                    } else {
                        e.message ?: "Could not connect"
                    }
                    retireServers()
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

    private fun detectExit(location: FoxLocation, token: String, attempt: Long, ticket: Long) {
        if (exitAttempt.getAndSet(attempt) == attempt) return
        thread(isDaemon = true, name = "fox-exit-ip") {
            val value = try { FoxTunnel.probe(location, token, ticket) } catch (_: Exception) { "" }
            val active = synchronized(stateLock) {
                if (attempt != generation || !connected) false else {
                    exitIp = value
                    true
                }
            }
            if (active) {
                tick()
                // Run even if the exit lookup failed, so DNS diagnostics do
                // not silently disappear when that third-party site is down.
                runLeakDiagnosticFor(attempt, null)
            }
        }
    }

    /**
     * Test the configured tunnel resolver without sending OS DNS queries from
     * the VPN-excluded app UID. A tunnel-only probe cannot prove device-wide
     * leak freedom; the UI explicitly reports that limitation.
     */
    fun runLeakDiagnostic(onComplete: (() -> Unit)? = null): Boolean = runLeakDiagnosticFor(null, onComplete)

    private fun runLeakDiagnosticFor(expectedAttempt: Long?, onComplete: (() -> Unit)?): Boolean {
        val ticket = synchronized(stateLock) {
            if (!connected || !FoxVpnService.isAlive() ||
                (expectedAttempt != null && expectedAttempt != generation)) return false
            generation to FoxLeakGuard.beginReport()
        }
        thread(isDaemon = true, name = "fox-leak-diagnostic") {
            try {
                val result = FoxLeakGuard.diagnose(
                    positiveHost = "api.accounts.firefox.com",
                    resolve = { host -> FoxVpnService.diagnosticDns(host) },
                    exitIp = exitIp
                )
                val published = synchronized(stateLock) {
                    if (!connected || ticket.first != generation) false
                    else FoxLeakGuard.publish(ticket.second, result)
                }
                if (published) tick()
            } catch (error: Exception) {
                Log.w("Gozar", "Tunnel diagnostic did not complete", error)
            } finally {
                onComplete?.let { callback -> main.post { callback() } }
            }
        }
        return true
    }

    private fun current(attempt: Long): Boolean = synchronized(stateLock) { attempt == generation }

    data class TransportScope(val location: FoxLocation, val ticket: Long)

    /** The proxy identity prevents an old listener from borrowing a new attempt. */
    internal fun transportScope(proxy: FoxProxy? = null): TransportScope? = synchronized(stateLock) {
        if (!connected || (proxy != null && proxy !== socks && proxy !== web && proxy !== dns)) return@synchronized null
        val place = location ?: return@synchronized null
        val ticket = transportTicket ?: return@synchronized null
        TransportScope(place, ticket)
    }

    internal fun transportIsCurrent(ticket: Long): Boolean = synchronized(stateLock) {
        connected && transportTicket == ticket && FoxTunnel.transportIsCurrent(ticket)
    }

    private fun storePass(app: Context, pass: FoxPass, access: FoxAccess, attempt: Long, live: Boolean = false): Boolean =
        synchronized(stateLock) {
            if (attempt != generation || (live && !connected)) false
            else FoxStore.saveUsage(app, pass, access.id, access.revision)
        }

    fun stop() = stopAttempt(null, "")

    private fun stopAttempt(attempt: Long?, reason: String) {
        synchronized(stateLock) {
            if (attempt != null && attempt != generation) return
            attempts.advance()
            transportTicket = null
            FoxTunnel.stopTransport()
            FoxLeakGuard.clearReport()
            retireServers()
            if (connected && FoxVpnService.lockdownState() == 3) {
                // This is an observed disconnection while lockdown is enabled,
                // not a claim that an individual packet was blocked.
                FoxLeakGuard.note(FoxLeakGuard.LOCKDOWN_INTERRUPTION)
            }
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

    // Android's VPN interface and resolver hold the originally advertised
    // loopback ports. Keep them reserved but paused across an in-place retry;
    // once the interface is gone, close the listeners completely.
    private fun retireServers() {
        if (FoxVpnService.hasInterface()) pauseServers() else stopServers()
    }

    private fun pauseServers() {
        socks?.pause()
        web?.pause()
        dns?.pause()
    }

    private fun resumeServers() {
        socks?.resume()
        web?.resume()
        dns?.resume()
    }

    private fun stopServers() {
        socks?.stop()
        web?.stop()
        dns?.stop()
        socks = null
        web = null
        dns = null
        prepared = false
        socksPort = 0
        webPort = 0
        dnsPort = 0
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

    private fun startRenewal(app: Context, attempt: Long, ticket: Long) {
        stopRenewal()
        renewer = thread(isDaemon = true, name = "fox-renew") {
            var retry = 0L
            var seenDown = down
            var seenUp = up
            while (current(attempt) && connected) {
                val wait = if (retry > 0) retry else FoxStore.renewalDelay(FoxStore.passUntil(app))
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    break
                }
                if (!current(attempt) || !connected) break
                val totalDown = down
                val totalUp = up
                val idle = totalDown == seenDown && totalUp == seenUp &&
                    System.currentTimeMillis() - lastTraffic.get() >= 30_000L
                seenDown = totalDown
                seenUp = totalUp
                if (idle && retry == 0L) continue
                try {
                    val access = Fox.validAccess(app)
                    val pass = FoxApi.pass(access.token)
                    if (!storePass(app, pass, access, attempt, true)) break
                    retry = 0L
                    Fox.load(app)
                    tick()
                    val now = System.currentTimeMillis()
                    if (now - geoRefresh.get() >= 86_400_000L) {
                        geoRefresh.set(now)
                        val place = location
                        if (place != null) {
                            thread(isDaemon = true, name = "fox-geo") { FoxGeo.refresh(place, pass.token, ticket) }
                        }
                    }
                } catch (_: Exception) {
                    retry = if (retry == 0L) 15_000L else (retry * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    fun pass(app: Context): String {
        val token = FoxStore.pass(app)
        val attempt = synchronized(stateLock) { if (connected) generation else null } ?: return token
        if (token.isEmpty()) return token
        if (FoxStore.passUntil(app) - System.currentTimeMillis() > 120_000L) return token
        return synchronized(renewLock) {
            val saved = FoxStore.pass(app)
            if (!current(attempt) || !connected || saved.isEmpty() ||
                FoxStore.passUntil(app) - System.currentTimeMillis() > 120_000L) return@synchronized saved
            try {
                val access = Fox.validAccess(app)
                val fresh = FoxApi.pass(access.token)
                if (storePass(app, fresh, access, attempt, true)) fresh.token else saved
            } catch (_: Exception) {
                saved
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
