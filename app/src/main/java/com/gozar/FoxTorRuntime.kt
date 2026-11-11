package com.gozar

import IPtProxy.Controller
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
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
    fun close()
}

internal object FoxTorRuntime : FoxLoginTransport {
    private val lock = Any()
    @Volatile private var retained: FoxTorAttempt? = null
    @Volatile private var starting: FoxTorAttempt? = null
    @Volatile private var cancelRequested = false
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
    @Volatile private var remotePid = 0

    private fun transportController(state: File): Controller = sharedController
        ?: Controller(state.absolutePath, false, false, "ERROR", null).also { sharedController = it }

    override fun run(context: Context, body: () -> Unit) = synchronized(lock) {
        val app = context.applicationContext
        cancelRequested = false
        retained?.let { existing ->
            FoxNet.throughLoginSocks(existing.port, body)
            return@synchronized
        }
        stopRemoteProcess(app)
        clean(app)
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
                report(app, R.string.login_tor_starting, modeLabel(app, mode))
                candidate.start()
                if (cancelRequested) throw FoxError("Secure connection stopped")
                retained = candidate
                starting = null
                report(app, R.string.login_tor_checking)
                reachability(candidate.port, 25_000)
                return true
            } catch (error: InterruptedException) {
                starting = null
                candidate.close()
                Thread.currentThread().interrupt()
                throw FoxError("Secure connection interrupted")
            } catch (error: Exception) {
                starting = null
                if (retained === candidate) {
                    throw FoxError("Tor is connected, but the Mozilla request failed. Retry on the same tunnel")
                }
                val detail = failureDetail(error, candidate)
                failures += mode.name + ": " + detail
                Log.w("GozarTor", mode.name + " failed: " + detail)
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
        FoxNet.throughLoginSocks(activeAttempt.port, body)
    }

    override fun isActive(): Boolean = retained != null

    override fun finish(context: Context) = shutdown(context, false)

    override fun stop(context: Context) = shutdown(context, true)

    private fun shutdown(context: Context, cancelled: Boolean) {
        if (cancelled) cancelRequested = true
        val app = context.applicationContext
        val pending = starting
        val active = retained
        starting = null
        retained = null
        try {
            if (pending != null && pending !== active) pending.close()
            active?.close()
        } catch (_: Exception) {
        }
        try {
            stopRemoteProcess(app)
        } catch (_: Exception) {
        }
        val native = sharedController
        sharedController = null
        for (mode in FoxTorPlan.order) {
            val name = FoxTorPlan.transportName(mode) ?: continue
            try {
                native?.stop(name)
            } catch (_: Exception) {
            }
        }
        clean(app)
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
        val native = candidate.diagnostics()
        return listOf(error.javaClass.simpleName, native).filter { it.isNotBlank() }.joinToString("; ").take(640)
    }

    private fun processMatches(pid: Int, processName: String): Boolean {
        if (pid <= 0) return false
        return try {
            File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).substringBefore('\u0000') == processName
        } catch (_: Exception) {
            false
        }
    }

    private fun queryRemotePid(binder: IBinder): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (!binder.transact(RemoteTorService.PID_TRANSACTION, data, reply, 0)) return 0
            reply.setDataPosition(0)
            reply.readInt().takeIf { it > 0 } ?: 0
        } catch (_: Exception) {
            0
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun stopRemoteProcess(context: Context) {
        try {
            context.stopService(Intent(context, RemoteTorService::class.java))
        } catch (_: Exception) {
        }
        val manager = context.getSystemService(ActivityManager::class.java)
        val processName = context.packageName + ":tor"
        var tracked = remotePid
        repeat(50) { attempt ->
            val discovered = manager?.runningAppProcesses.orEmpty().firstOrNull {
                it.processName == processName && it.uid == Process.myUid()
            }?.pid ?: 0
            if (discovered > 0) tracked = discovered
            if (attempt == 0 || attempt == 20 || attempt == 40) {
                if (processMatches(tracked, processName)) Process.killProcess(tracked)
            }
            val trackedAlive = processMatches(tracked, processName)
            if (!trackedAlive && discovered == 0) {
                remotePid = 0
                return
            }
            Thread.sleep(100)
        }
        remotePid = tracked
        throw FoxError("Previous Tor process did not stop")
    }

    fun clean(context: Context) {
        File(context.cacheDir, "gozar-login-transport").deleteRecursively()
        File(context.cacheDir, "TorService").deleteRecursively()
        context.getDir("TorService", Context.MODE_PRIVATE).deleteRecursively()
    }

    private class RemoteControl(private val socket: LocalSocket) {
        private val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        private val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))

        fun command(value: String): String {
            writer.write(value)
            writer.write("\r\n")
            writer.flush()
            val lines = mutableListOf<String>()
            var continued = false
            while (true) {
                val line = reader.readLine() ?: throw FoxError("Tor control socket closed")
                if (line.startsWith("650 ")) continue
                if (line == "." && continued) {
                    continued = false
                    continue
                }
                lines += line
                if (continued) continue
                if (line.length < 4) throw FoxError("Invalid Tor control reply")
                if (line[3] == '+') {
                    continued = true
                    continue
                }
                if (line[3] == '-') continue
                if (line.startsWith("250 ")) return lines.joinToString("\n")
                if (line.firstOrNull() == '5') throw FoxError("Tor control rejected configuration: " + line.take(3))
                throw FoxError("Unexpected Tor control reply")
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private class AndroidAttempt(
        private val app: Context,
        private val mode: FoxTorPlan.Mode,
        private val bridge: String?,
        private val budgetMs: Long
    ) : FoxTorAttempt {
        private val ready = CountDownLatch(1)
        private val closed = AtomicBoolean(false)
        private var controller: Controller? = null
        private var transport: String? = null
        private var connection: ServiceConnection? = null
        private var remoteBinder: IBinder? = null
        private var deathRecipient: IBinder.DeathRecipient? = null
        private var control: RemoteControl? = null
        private var bound = false
        @Volatile private var remoteDisconnected = false
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
            val budget = TimeUnit.MILLISECONDS.toNanos(budgetMs)
            diagnosticStage = "prepare-state"
            stopRemoteTor()
            clean(app)
            val state = File(app.cacheDir, "gozar-login-transport")
            if (!state.mkdirs() && !state.isDirectory) throw FoxError("Cannot create Tor state directory")
            transport = FoxTorPlan.transportName(mode)
            transportPort = startTransport(state)
            val torrc = TorService.getTorrc(app)
            val directory = torrc.parentFile ?: throw FoxError("Tor configuration directory is unavailable")
            if (!directory.mkdirs() && !directory.isDirectory) throw FoxError("Cannot recreate Tor configuration directory")
            diagnosticStage = "write-torrc"
            torrc.writeText(FoxTorPlan.torrc(mode, transportPort))
            diagnosticStage = "bind-remote-service"
            val connected = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null) {
                        remoteDisconnected = true
                        ready.countDown()
                        return
                    }
                    remotePid = queryRemotePid(binder)
                    remoteBinder = binder
                    val recipient = IBinder.DeathRecipient {
                        if (!closing) remoteDisconnected = true
                        ready.countDown()
                    }
                    deathRecipient = recipient
                    try {
                        binder.linkToDeath(recipient, 0)
                    } catch (_: Exception) {
                        remoteDisconnected = true
                    }
                    ready.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (!closing) remoteDisconnected = true
                    ready.countDown()
                }

                override fun onBindingDied(name: ComponentName?) {
                    if (!closing) remoteDisconnected = true
                    ready.countDown()
                }

                override fun onNullBinding(name: ComponentName?) {
                    if (!closing) remoteDisconnected = true
                    ready.countDown()
                }
            }
            connection = connected
            val service = Intent(app, RemoteTorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(service) else app.startService(service)
            bound = app.bindService(service, connected, Context.BIND_AUTO_CREATE)
            if (!bound) throw FoxError("Android refused to bind isolated TorService")
            val bindBudget = minOf(15_000L, TimeUnit.NANOSECONDS.toMillis(budget - (System.nanoTime() - started)).coerceAtLeast(1L))
            val bindDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(bindBudget)
            while (!ready.await(100, TimeUnit.MILLISECONDS)) {
                if (remoteDisconnected) throw FoxError("Isolated Tor process disconnected during binding")
                if (cancelRequested) throw FoxError("Secure connection stopped")
                if (System.nanoTime() >= bindDeadline) throw FoxError("Isolated TorService binding timed out")
            }
            if (remoteDisconnected) throw FoxError("Isolated Tor process disconnected during binding")
            diagnosticStage = "control"
            val initialDeadlineMs = TimeUnit.NANOSECONDS.toMillis(started + budget)
            var circuitDeadlineMs: Long? = null
            while (true) {
                if (cancelRequested) throw FoxError("Secure connection stopped")
                val nowMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
                if (nowMs >= (circuitDeadlineMs ?: initialDeadlineMs)) break
                if (remoteDisconnected) throw FoxError("Isolated Tor process stopped during bootstrap")
                if (control == null) {
                    try {
                        control = openControl()
                    } catch (_: Exception) {
                        Thread.sleep(100)
                        continue
                    }
                }
                val reply = try {
                    control?.command("GETINFO status/bootstrap-phase") ?: throw FoxError("Tor control is unavailable")
                } catch (error: Exception) {
                    control?.close()
                    control = null
                    if (remoteDisconnected) throw FoxError("Isolated Tor process stopped during bootstrap")
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
                    lastProgress = progress
                    report(app, R.string.login_tor_bootstrap, modeLabel(app, mode), progress)
                }
                if (progress == 100) {
                    val listeners = control?.command("GETINFO net/listeners/socks") ?: ""
                    val socks = Regex(":(\\d{1,5})").findAll(listeners).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (socks in 1..65535) {
                        port = socks
                        return socks
                    }
                }
                Thread.sleep(100)
            }
            throw FoxError("Tor bootstrap time budget exhausted")
        }

        private fun openControl(): RemoteControl {
            val socketFile = File(app.getDir("TorService", Context.MODE_PRIVATE), "data/ControlSocket")
            val socket = LocalSocket()
            return try {
                socket.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.soTimeout = 1_000
                RemoteControl(socket).also {
                    it.command("AUTHENTICATE")
                    diagnosticStage = "activate-network"
                    it.command(FoxTorPlan.configure(mode, bridge, transportPort))
                    it.command(FoxTorPlan.activate())
                    it.command("SIGNAL ACTIVE")
                    val configured = it.command("GETCONF DisableNetwork ClientTransportPlugin")
                    networkEnabled = configured.lineSequence().any { line -> line.endsWith("DisableNetwork=0") }
                    transportConfigured = mode == FoxTorPlan.Mode.DIRECT || configured.lineSequence().any { line -> line.substringAfter(' ').startsWith("ClientTransportPlugin=") }
                    diagnosticStage = "bootstrap"
                }
            } catch (error: Exception) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                throw error
            }
        }

        private fun startTransport(state: File): Int {
            val name = transport ?: return 0
            diagnosticStage = "create-$name-controller"
            val native = transportController(state)
            diagnosticStage = "configure-$name"
            controller = native
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
            native.start(name, "")
            diagnosticStage = "read-$name-port"
            val localPort = native.port(name).toInt()
            if (localPort !in 1..65535) throw FoxError("Invalid local transport port")
            return localPort
        }

        override fun diagnostics(): String {
            val details = mutableListOf<String>()
            details += "stage=$diagnosticStage"
            details += "network=$networkEnabled;transport=$transportConfigured"
            if (lastProgress >= 0) details += "bootstrap=$lastProgress%"
            if (lastBootstrapSummary.isNotBlank()) details += "state=$lastBootstrapSummary"
            if (remoteDisconnected) details += "process=disconnected"
            return details.joinToString(";")
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            closing = true
            diagnosticStage = "shutdown"
            try {
                control?.command("SIGNAL SHUTDOWN")
            } catch (_: Exception) {
            }
            control?.close()
            control = null
            waitForPortClose(port)
            deathRecipient?.let { recipient ->
                try {
                    remoteBinder?.unlinkToDeath(recipient, 0)
                } catch (_: Exception) {
                }
            }
            deathRecipient = null
            remoteBinder = null
            connection?.let { connected ->
                if (bound) {
                    try {
                        app.unbindService(connected)
                    } catch (_: Exception) {
                    }
                }
            }
            bound = false
            connection = null
            var cleanupFailure: Exception? = null
            try {
                stopRemoteTor()
            } catch (error: Exception) {
                cleanupFailure = error
            }
            transport?.let { name ->
                try {
                    controller?.stop(name)
                } catch (_: Exception) {
                }
            }
            controller = null
            transport = null
            waitForPortClose(transportPort)
            waitForPortClose(port)
            transportPort = 0
            port = 0
            clean(app)
            cleanupFailure?.let { throw it }
        }

        private fun stopRemoteTor() = stopRemoteProcess(app)

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
