package com.gozar

import IPtProxy.Controller
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
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
import org.torproject.jni.TorService

internal fun interface FoxLoginTransport {
    fun run(context: Context, body: () -> Unit)
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
    @Volatile internal var attemptFactory: (Context, FoxTorPlan.Mode, String?, Long) -> FoxTorAttempt = { app, mode, bridge, budget -> AndroidAttempt(app, mode, bridge, budget) }
    @Volatile internal var reachability: (Int, Int) -> Unit = { port, timeout ->
        FoxNet.throughLoginSocks(port) {
            FoxNet.call(FoxNet.SERVERS, "GET", timeout = timeout)
        }
    }
    @Volatile internal var statusCallback: (String) -> Unit = { Fox.updateLoginStage(it) }
    @Volatile internal var manualProvider: () -> FoxBridge.Candidate? = { FoxBridgeStore.manualCandidate() }
    @Volatile internal var automaticProvider: (Context) -> List<FoxBridge.Candidate> = { app -> FoxBridgeStore.automatic(app) }
    private var sharedController: Controller? = null

    private fun transportController(state: File): Controller = sharedController
        ?: Controller(state.absolutePath, false, false, "ERROR", null).also { sharedController = it }

    override fun run(context: Context, body: () -> Unit) = synchronized(lock) {
        val app = context.applicationContext
        clean(app)
        var active: FoxTorAttempt? = null
        val failures = mutableListOf<String>()
        val rotationStarted = System.nanoTime()
        fun rotationRemaining(): Long = TimeUnit.NANOSECONDS.toMillis(
            TimeUnit.SECONDS.toNanos(150) - (System.nanoTime() - rotationStarted)
        ).coerceAtLeast(0L)

        fun tryTransport(mode: FoxTorPlan.Mode, bridge: String?, budget: Long = FoxTorPlan.timeoutMs(mode)): Boolean {
            val allowed = minOf(FoxTorPlan.timeoutMs(mode), budget)
            if (allowed <= 0) return false
            val candidate = attemptFactory(app, mode, bridge, allowed)
            try {
                report(app, R.string.login_tor_starting, modeLabel(app, mode))
                val started = System.nanoTime()
                candidate.start()
                val remaining = TimeUnit.NANOSECONDS.toMillis(
                    TimeUnit.MILLISECONDS.toNanos(allowed) - (System.nanoTime() - started)
                ).toInt()
                if (remaining <= 0) throw FoxError("Transport time budget exhausted")
                report(app, R.string.login_tor_checking)
                reachability(candidate.port, remaining)
                active = candidate
                return true
            } catch (error: InterruptedException) {
                candidate.close()
                Thread.currentThread().interrupt()
                throw FoxError("Secure connection interrupted")
            } catch (error: Exception) {
                val detail = failureDetail(error, candidate)
                failures += mode.name + ": " + detail
                Log.w("GozarTor", mode.name + " failed: " + detail)
                candidate.close()
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
        if (active == null) FoxBridgeStore.manual = ""

        if (active == null) {
            val automatic = try {
                automaticProvider(app)
            } catch (error: Exception) {
                failures += "FEED: " + error.javaClass.simpleName
                emptyList()
            }
            val seen = HashSet<String>()
            var count = 0
            for (candidate in automatic) {
                if (count >= FoxBridge.MAX_AUTOMATIC || active != null || rotationRemaining() <= 0) break
                if (candidate.mode !in setOf(FoxTorPlan.Mode.OBFS4, FoxTorPlan.Mode.WEBTUNNEL)) continue
                val valid = FoxBridge.parse(candidate.line)?.takeIf { it.mode == candidate.mode } ?: continue
                if (!seen.add(valid.line)) continue
                count++
                if (tryTransport(valid.mode, valid.line, rotationRemaining())) break
            }
        }

        if (active == null) {
            for (mode in listOf(FoxTorPlan.Mode.SNOWFLAKE, FoxTorPlan.Mode.MEEK)) {
                if (tryTransport(mode, null)) break
            }
        }

        val activeAttempt = active ?: throw FoxError("Secure connection could not be established\n" + failures.joinToString("\n"))
        try {
            FoxNet.throughLoginSocks(activeAttempt.port, body)
        } finally {
            activeAttempt.close()
        }
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
        private var controller: Controller? = null
        private var transport: String? = null
        private var connection: ServiceConnection? = null
        private var control: RemoteControl? = null
        private var bound = false
        @Volatile private var remoteDisconnected = false
        @Volatile private var closing = false
        @Volatile private var diagnosticStage = "created"
        @Volatile private var lastProgress = -1
        @Volatile private var lastBootstrapSummary = ""
        @Volatile private var networkEnabled = false
        @Volatile private var bridgeConfigured = false
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
                    ready.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (!closing) remoteDisconnected = true
                }

                override fun onBindingDied(name: ComponentName?) {
                    if (!closing) remoteDisconnected = true
                }

                override fun onNullBinding(name: ComponentName?) {
                    if (!closing) remoteDisconnected = true
                }
            }
            connection = connected
            bound = app.bindService(Intent(app, RemoteTorService::class.java), connected, Context.BIND_AUTO_CREATE)
            if (!bound) throw FoxError("Android refused to bind isolated TorService")
            val bindBudget = minOf(15_000L, TimeUnit.NANOSECONDS.toMillis(budget - (System.nanoTime() - started)).coerceAtLeast(1L))
            if (!ready.await(bindBudget, TimeUnit.MILLISECONDS)) throw FoxError("Isolated TorService binding timed out")
            diagnosticStage = "control"
            val deadline = started + budget
            while (System.nanoTime() < deadline) {
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
                if (mode in setOf(FoxTorPlan.Mode.OBFS4, FoxTorPlan.Mode.WEBTUNNEL) && FoxTorPlan.fastFailure(reply)) {
                    throw FoxError("Bridge connection was rejected")
                }
                val progress = FoxTorPlan.bootstrapProgress(reply)
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
                    val configured = it.command("GETCONF DisableNetwork UseBridges Bridge ClientTransportPlugin")
                    networkEnabled = configured.lineSequence().any { line -> line.endsWith("DisableNetwork=0") }
                    bridgeConfigured = mode == FoxTorPlan.Mode.DIRECT || configured.lineSequence().any { line -> line.substringAfter(' ').startsWith("Bridge=") }
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
            details += "network=$networkEnabled;bridge=$bridgeConfigured;transport=$transportConfigured"
            if (lastProgress >= 0) details += "bootstrap=$lastProgress%"
            if (lastBootstrapSummary.isNotBlank()) details += "state=$lastBootstrapSummary"
            if (remoteDisconnected) details += "process=disconnected"
            return details.joinToString(";")
        }

        override fun close() {
            closing = true
            diagnosticStage = "shutdown"
            try {
                control?.command("SIGNAL SHUTDOWN")
            } catch (_: Exception) {
            }
            control?.close()
            control = null
            waitForPortClose(port)
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
            stopRemoteTor()
            transport?.let { name ->
                try {
                    controller?.stop(name)
                } catch (_: Exception) {
                }
            }
            controller = null
            transport = null
            clean(app)
        }

        private fun stopRemoteTor() {
            try {
                app.stopService(Intent(app, RemoteTorService::class.java))
            } catch (_: Exception) {
            }
            val manager = app.getSystemService(ActivityManager::class.java) ?: return
            val processName = app.packageName + ":tor"
            repeat(40) { attempt ->
                val remote = manager.runningAppProcesses.orEmpty().firstOrNull {
                    it.processName == processName && it.uid == Process.myUid()
                }
                if (remote == null) return
                if (attempt == 0 || attempt == 20) Process.killProcess(remote.pid)
                Thread.sleep(100)
            }
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
