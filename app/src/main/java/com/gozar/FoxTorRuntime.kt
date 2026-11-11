package com.gozar

import IPtProxy.Controller
import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.torproject.jni.TorService

internal fun interface FoxLoginTransport {
    fun run(context: Context, body: () -> Unit)
    fun isActive(): Boolean = false
    fun finish(context: Context) = Unit
    fun stop(context: Context) = Unit
}

internal object FoxDirectLoginTransport : FoxLoginTransport {
    override fun run(context: Context, body: () -> Unit) = body()
}

internal interface FoxTorAttempt {
    val port: Int
    fun start(): Int
    fun diagnostics(): String = ""
    fun isAlive(): Boolean = true
    fun close()
}

internal object FoxTorRuntime : FoxLoginTransport {
    private val lock = Any()
    @Volatile private var retained: FoxTorAttempt? = null
    @Volatile private var starting: FoxTorAttempt? = null
    @Volatile private var cancelRequested = false
    @Volatile internal var orbotStatus = TorService.STATUS_OFF
        private set
    @Volatile internal var attemptFactory: (Context, FoxTorPlan.Mode, String?, Long) -> FoxTorAttempt = { app, mode, bridge, budget -> AndroidAttempt(app, mode, bridge, budget) }
    @Volatile internal var reachability: (Int, Int) -> Unit = { port, timeout ->
        FoxNet.throughLoginSocks(port) {
            val reply = FoxNet.callWithRetry(FoxNet.SERVERS, "GET", timeout = timeout, attempts = 2)
            if (reply.code != 200) throw java.io.IOException("Mozilla reachability was not confirmed")
        }
    }
    @Volatile internal var statusCallback: (String) -> Unit = { Fox.updateLoginStage(it) }
    @Volatile internal var manualProvider: () -> FoxBridge.Candidate? = { FoxBridgeStore.manualCandidate() }
    @Volatile internal var automaticProvider: (Context) -> List<FoxBridge.Candidate> = { app -> FoxBridgeStore.automatic(app) }
    private var sharedController: Controller? = null

    private fun transportController(state: File): Controller = sharedController
        ?: Controller(state.absolutePath, false, false, "ERROR", null).also {
            sharedController = it
            FoxTorLog.record("ORBOT_TRANSPORT_CONTROLLER", "shared instance ready")
        }

    override fun run(context: Context, body: () -> Unit) = synchronized(lock) {
        val app = context.applicationContext
        cancelRequested = false
        retained?.let { existing ->
            if (existing.isAlive()) {
                FoxNet.throughLoginSocks(existing.port, body)
                return@synchronized
            }
            retained = null
            try {
                existing.close()
            } catch (error: Exception) {
                FoxTorLog.record("ORBOT_CLOSE_ERROR", error.javaClass.simpleName)
            }
            throw FoxError("TorService disconnected. Retry with a fresh connection")
        }
        FoxTorLog.record("LOGIN_TRANSPORT_START")
        val failures = mutableListOf<String>()
        val rotationStarted = System.nanoTime()
        fun rotationRemaining(): Long = TimeUnit.NANOSECONDS.toMillis(
            TimeUnit.SECONDS.toNanos(150) - (System.nanoTime() - rotationStarted)
        ).coerceAtLeast(0L)

        fun tryTransport(mode: FoxTorPlan.Mode, bridge: String?, budget: Long = FoxTorPlan.timeoutMs(mode)): Boolean {
            val allowed = minOf(FoxTorPlan.timeoutMs(mode), budget)
            if (allowed <= 0) return false
            if (cancelRequested) throw FoxError("Secure connection stopped")
            val candidate = attemptFactory(app, mode, bridge, allowed)
            starting = candidate
            try {
                FoxTorLog.record("ATTEMPT_START", mode.name)
                report(app, R.string.login_tor_starting, modeLabel(app, mode))
                candidate.start()
                if (cancelRequested) throw FoxError("Secure connection stopped")
                retained = candidate
                starting = null
                report(app, R.string.login_tor_checking)
                reachability(candidate.port, 25_000)
                FoxTorLog.record("ATTEMPT_RETAINED", mode.name)
                return true
            } catch (error: InterruptedException) {
                starting = null
                candidate.close()
                Thread.currentThread().interrupt()
                throw FoxError("Secure connection interrupted")
            } catch (error: Exception) {
                starting = null
                if (retained === candidate) {
                    FoxTorLog.record("MOZILLA_RETRY_SAME_TUNNEL", error.javaClass.simpleName)
                    throw FoxError("Tor is connected, but the Mozilla request failed. Retry on the same tunnel")
                }
                val detail = failureDetail(error, candidate)
                failures += mode.name + ": " + detail
                FoxTorLog.record("ATTEMPT_FAILED", mode.name + "; " + detail)
                candidate.close()
                if (cancelRequested) throw FoxError("Secure connection stopped")
                return false
            }
        }

        val manual = try {
            manualProvider()
        } catch (error: Exception) {
            failures += "MANUAL: " + error.javaClass.simpleName
            null
        }
        if (manual?.mode == FoxTorPlan.Mode.OBFS4) tryTransport(manual.mode, manual.line, rotationRemaining())
        if (retained == null) FoxBridgeStore.manual = ""

        if (retained == null) {
            val automatic = try {
                automaticProvider(app)
            } catch (error: Exception) {
                failures += "FEED: " + error.javaClass.simpleName
                emptyList()
            }
            val seen = HashSet<String>()
            var count = 0
            for (candidate in automatic) {
                if (count >= FoxBridge.MAX_AUTOMATIC || retained != null || rotationRemaining() <= 0) break
                if (candidate.mode !in setOf(FoxTorPlan.Mode.OBFS4, FoxTorPlan.Mode.WEBTUNNEL)) continue
                val valid = FoxBridge.parse(candidate.line)?.takeIf { it.mode == candidate.mode } ?: continue
                if (!seen.add(valid.line)) continue
                count++
                if (tryTransport(valid.mode, valid.line, rotationRemaining())) break
            }
        }

        if (retained == null) {
            for (mode in listOf(FoxTorPlan.Mode.SNOWFLAKE, FoxTorPlan.Mode.MEEK)) {
                if (tryTransport(mode, null)) break
            }
        }

        val activeAttempt = retained ?: throw FoxError("Secure connection could not be established\n" + failures.joinToString("\n"))
        if (cancelRequested) throw FoxError("Secure connection stopped")
        try {
            FoxNet.throughLoginSocks(activeAttempt.port, body)
        } catch (error: Exception) {
            if (!activeAttempt.isAlive()) {
                if (retained === activeAttempt) retained = null
                activeAttempt.close()
                throw FoxError("TorService disconnected. Retry with a fresh connection")
            }
            throw error
        }
    }

    override fun isActive(): Boolean = retained?.isAlive() == true

    override fun finish(context: Context) = shutdown(context, false)

    override fun stop(context: Context) = shutdown(context, true)

    private fun shutdown(context: Context, cancelled: Boolean) {
        if (cancelled) cancelRequested = true
        orbotStatus = TorService.STATUS_STOPPING
        FoxTorLog.record("ORBOT_STOP_REQUESTED")
        val app = context.applicationContext
        val pending = starting
        val active = retained
        starting = null
        retained = null
        try {
            if (pending != null && pending !== active) pending.close()
            active?.close()
        } catch (error: Exception) {
            FoxTorLog.record("ORBOT_CLOSE_ERROR", error.javaClass.simpleName)
        }
        val native = sharedController
        for (mode in FoxTorPlan.order) {
            val name = FoxTorPlan.transportName(mode) ?: continue
            try {
                native?.stop(name)
            } catch (error: Exception) {
                FoxTorLog.record("TRANSPORT_STOP_ERROR", error.javaClass.simpleName)
            }
        }
        clean(app)
        orbotStatus = TorService.STATUS_OFF
        FoxTorLog.record("ORBOT_STOP_COMPLETE")
    }

    private fun modeLabel(context: Context, mode: FoxTorPlan.Mode): String = context.getString(when (mode) {
        FoxTorPlan.Mode.DIRECT -> R.string.login_tor_direct
        FoxTorPlan.Mode.OBFS4 -> R.string.login_tor_obfs4
        FoxTorPlan.Mode.WEBTUNNEL -> R.string.login_tor_webtunnel
        FoxTorPlan.Mode.SNOWFLAKE -> R.string.login_tor_snowflake
        FoxTorPlan.Mode.MEEK -> R.string.login_tor_meek
    })

    private fun report(context: Context, resource: Int, vararg arguments: Any) {
        statusCallback(context.getString(resource, *arguments))
    }

    private fun failureDetail(error: Exception, candidate: FoxTorAttempt): String {
        val message = FoxTorLog.sanitize(error.message.orEmpty())
        val native = candidate.diagnostics()
        return listOf(error.javaClass.simpleName, message, native).filter { it.isNotBlank() }.joinToString("; ").take(640)
    }

    fun clean(context: Context) {
        File(context.cacheDir, "gozar-login-transport").deleteRecursively()
        File(context.cacheDir, "TorService").deleteRecursively()
        context.getDir("TorService", Context.MODE_PRIVATE).deleteRecursively()
    }

    private class AndroidAttempt(
        private val app: Context,
        private val mode: FoxTorPlan.Mode,
        private val bridge: String?,
        private val budgetMs: Long
    ) : FoxTorAttempt {
        private val closed = AtomicBoolean(false)
        private var nativeController: Controller? = null
        private var transport: String? = null
        private val orbot = OrbotTorController(app) { status ->
            orbotStatus = status
            FoxTorLog.record("ORBOT_STATE", status)
        }
        @Volatile private var closing = false
        @Volatile private var diagnosticStage = "created"
        @Volatile private var lastProgress = -1
        @Volatile private var lastBootstrapSummary = ""
        @Volatile private var networkEnabled = false
        @Volatile private var transportConfigured = false
        private var transportPort = 0
        override var port: Int = 0
            private set

        override fun start(): Int {
            val started = System.nanoTime()
            val initialDeadlineMs = TimeUnit.NANOSECONDS.toMillis(started + TimeUnit.MILLISECONDS.toNanos(budgetMs))
            var circuitDeadlineMs: Long? = null
            diagnosticStage = "prepare-state"
            val state = File(app.cacheDir, "gozar-login-transport")
            if (!state.mkdirs() && !state.isDirectory) throw FoxError("Cannot create Tor state directory")
            transport = FoxTorPlan.transportName(mode)
            transportPort = startTransport(state)
            val torrc = TorService.getTorrc(app)
            val directory = torrc.parentFile ?: throw FoxError("Tor configuration directory is unavailable")
            if (!directory.mkdirs() && !directory.isDirectory) throw FoxError("Cannot recreate Tor configuration directory")
            diagnosticStage = "write-torrc"
            torrc.writeText(FoxTorPlan.torrc(mode, transportPort))
            diagnosticStage = "bind-tor-service"
            orbot.startTorService()
            while (true) {
                if (cancelRequested || closing) throw FoxError("Secure connection stopped")
                val nowMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
                if (nowMs >= (circuitDeadlineMs ?: initialDeadlineMs)) break
                if (orbot.remoteDisconnected) throw FoxError("TorService disconnected")
                val control = orbot.conn
                if (control == null) {
                    Thread.sleep(100)
                    continue
                }
                if (!networkEnabled) {
                    diagnosticStage = "activate-network"
                    control.setConf(FoxTorPlan.controlConfiguration(mode, bridge, transportPort))
                    transportConfigured = true
                    control.setConf("DisableNetwork", "0")
                    control.signal(TorService.SIGNAL_ACTIVE)
                    networkEnabled = true
                    diagnosticStage = "bootstrap"
                }
                val reply = try {
                    "250-status/bootstrap-phase=" + control.getInfo("status/bootstrap-phase")
                } catch (error: Exception) {
                    if (orbot.remoteDisconnected) throw FoxError("TorService disconnected during bootstrap")
                    Thread.sleep(100)
                    continue
                }
                if (circuitDeadlineMs == null && mode in setOf(FoxTorPlan.Mode.OBFS4, FoxTorPlan.Mode.WEBTUNNEL) && FoxTorPlan.fastFailure(reply)) {
                    throw FoxError("Bridge connection was rejected")
                }
                val progress = FoxTorPlan.bootstrapProgress(reply)
                circuitDeadlineMs = FoxTorPlan.circuitDeadline(progress, nowMs, circuitDeadlineMs)
                lastBootstrapSummary = FoxTorPlan.bootstrapSummary(reply)
                if (progress >= 0) {
                    if (progress != lastProgress) FoxTorLog.record("BOOTSTRAP", mode.name + "; progress=$progress")
                    lastProgress = progress
                    report(app, R.string.login_tor_bootstrap, modeLabel(app, mode), progress)
                }
                if (progress == 100) {
                    val socks = orbot.socksPort.takeIf { it in 1..65535 } ?: control.getInfo("net/listeners/socks").let { listeners ->
                        Regex(":(\\d{1,5})").findAll(listeners).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }
                    if (socks in 1..65535) {
                        port = socks
                        diagnosticStage = "ready"
                        return socks
                    }
                }
                Thread.sleep(100)
            }
            throw FoxError("Tor bootstrap time budget exhausted")
        }

        private fun startTransport(state: File): Int {
            val name = transport ?: return 0
            diagnosticStage = "create-$name-controller"
            val native = transportController(state)
            diagnosticStage = "configure-$name"
            nativeController = native
            if (mode == FoxTorPlan.Mode.SNOWFLAKE) {
                native.snowflakeBrokerUrl = FoxTorPlan.SNOWFLAKE_BROKER
                native.snowflakeFrontDomains = FoxTorPlan.SNOWFLAKE_FRONTS
                native.snowflakeIceServers = FoxTorPlan.SNOWFLAKE_ICE
                native.snowflakeAmpCacheUrl = ""
                native.snowflakeSqsUrl = ""
                native.snowflakeSqsCreds = ""
                native.snowflakeMaxPeers = 1L
            }
            diagnosticStage = "start-$name"
            native.start(name, null)
            diagnosticStage = "read-$name-port"
            val localPort = native.port(name).toInt()
            if (localPort !in 1..65535) throw FoxError("Invalid local transport port")
            return localPort
        }

        override fun isAlive(): Boolean = !closed.get() && !closing && !orbot.remoteDisconnected &&
            orbot.mCurrentStatus != TorService.STATUS_OFF && orbot.conn != null

        override fun diagnostics(): String {
            val details = mutableListOf<String>()
            details += "stage=$diagnosticStage"
            details += "orbot=${orbot.mCurrentStatus}"
            details += "network=$networkEnabled;transport=$transportConfigured"
            if (lastProgress >= 0) details += "bootstrap=$lastProgress%"
            if (lastBootstrapSummary.isNotBlank()) details += "state=$lastBootstrapSummary"
            if (orbot.remoteDisconnected) details += "process=disconnected"
            return details.joinToString(";")
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            closing = true
            diagnosticStage = "shutdown"
            orbot.stopTor()
            transport?.let { name ->
                try {
                    nativeController?.stop(name)
                } catch (error: Exception) {
                    FoxTorLog.record("TRANSPORT_STOP_ERROR", error.javaClass.simpleName)
                }
            }
            nativeController = null
            transport = null
            waitForPortClose(transportPort)
            waitForPortClose(port)
            transportPort = 0
            port = 0
            clean(app)
        }

        private fun waitForPortClose(observedPort: Int) {
            if (observedPort !in 1..65535) return
            repeat(40) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", observedPort), 100)
                    }
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                } catch (_: Exception) {
                    return
                }
            }
        }
    }
}
