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
    @Volatile internal var attemptFactory: (Context, FoxTorPlan.Mode) -> FoxTorAttempt = { app, mode -> AndroidAttempt(app, mode) }
    @Volatile internal var reachability: (Int) -> Unit = { port ->
        FoxNet.throughLoginSocks(port) {
            FoxNet.call(FoxNet.SERVERS, "GET", timeout = 20_000)
        }
    }
    @Volatile internal var statusCallback: (String) -> Unit = { Fox.updateLoginStage(it) }
    private var sharedController: Controller? = null

    private fun transportController(state: File): Controller = sharedController
        ?: Controller(state.absolutePath, false, false, "ERROR", null).also { sharedController = it }

    override fun run(context: Context, body: () -> Unit) = synchronized(lock) {
        val app = context.applicationContext
        clean(app)
        var active: FoxTorAttempt? = null
        val failures = mutableListOf<String>()
        for (mode in FoxTorPlan.order) {
            val candidate = attemptFactory(app, mode)
            try {
                report(app, R.string.login_tor_starting, modeLabel(app, mode))
                candidate.start()
                report(app, R.string.login_tor_checking)
                reachability(candidate.port)
                active = candidate
                break
            } catch (error: InterruptedException) {
                candidate.close()
                Thread.currentThread().interrupt()
                throw FoxError("Secure connection interrupted")
            } catch (error: Exception) {
                val detail = failureDetail(error, candidate)
                failures += mode.name + ": " + detail
                Log.w("GozarTor", mode.name + " failed\n" + detail)
                candidate.close()
            }
        }
        val attempt = active ?: throw FoxError("Secure connection could not be established\n" + failures.joinToString("\n"))
        try {
            FoxNet.throughLoginSocks(attempt.port, body)
        } finally {
            attempt.close()
        }
    }

    private fun modeLabel(context: Context, mode: FoxTorPlan.Mode): String = context.getString(when (mode) {
        FoxTorPlan.Mode.DIRECT -> R.string.login_tor_direct
        FoxTorPlan.Mode.SNOWFLAKE -> R.string.login_tor_snowflake
        FoxTorPlan.Mode.MEEK -> R.string.login_tor_meek
    })

    private fun report(context: Context, resource: Int, vararg arguments: Any) {
        statusCallback(context.getString(resource, *arguments))
    }

    private fun failureDetail(error: Exception, candidate: FoxTorAttempt): String {
        val summary = buildString {
            append(error.javaClass.simpleName)
            error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            val cause = error.cause
            if (cause != null && cause.message != error.message) {
                append("; cause=").append(cause.javaClass.simpleName)
                cause.message?.let { append(": ").append(it) }
            }
        }
        val native = candidate.diagnostics()
        return cleanDiagnostic(if (native.isBlank()) summary else summary + "\n" + native).take(3_200)
    }

    private fun cleanDiagnostic(text: String): String = text.replace('\r', ' ').replace('\t', ' ')
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

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
                if (line.firstOrNull() == '5') throw FoxError("Tor control error: " + line.take(240))
                throw FoxError("Unexpected Tor control reply: " + line.take(240))
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private class AndroidAttempt(private val app: Context, private val mode: FoxTorPlan.Mode) : FoxTorAttempt {
        private val ready = CountDownLatch(1)
        private var controller: Controller? = null
        private var transport: String? = null
        private var connection: ServiceConnection? = null
        private var control: RemoteControl? = null
        private var bound = false
        private var logFile: File? = null
        @Volatile private var remoteDisconnected = false
        @Volatile private var closing = false
        @Volatile private var diagnosticStage = "created"
        @Volatile private var lastProgress = -1
        @Volatile private var lastSummary = ""
        override var port: Int = 0
            private set

        override fun start(): Int {
            diagnosticStage = "prepare-state"
            stopRemoteTor()
            clean(app)
            val state = File(app.cacheDir, "gozar-login-transport")
            if (!state.mkdirs() && !state.isDirectory) throw FoxError("Cannot create Tor diagnostic directory")
            val diagnosticLog = File(state, "tor.log")
            logFile = diagnosticLog
            transport = FoxTorPlan.transportName(mode)
            val transportPort = startTransport(state)
            val torrc = TorService.getTorrc(app)
            val directory = torrc.parentFile ?: throw FoxError("Tor configuration directory is unavailable")
            if (!directory.mkdirs() && !directory.isDirectory) throw FoxError("Cannot recreate Tor configuration directory")
            diagnosticStage = "write-torrc"
            torrc.writeText(FoxTorPlan.torrc(mode, transportPort, diagnosticLog.absolutePath))
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
            if (!ready.await(15, TimeUnit.SECONDS)) throw FoxError("Isolated TorService binding timed out after 15 seconds")
            diagnosticStage = "bootstrap"
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FoxTorPlan.timeoutMs(mode))
            while (System.nanoTime() < deadline) {
                if (remoteDisconnected) throw FoxError("Isolated Tor process stopped at bootstrap $lastProgress%: $lastSummary")
                if (control == null) {
                    try {
                        control = openControl()
                    } catch (_: Exception) {
                        Thread.sleep(250)
                        continue
                    }
                }
                val reply = try {
                    control?.command("GETINFO status/bootstrap-phase") ?: throw FoxError("Tor control is unavailable")
                } catch (error: Exception) {
                    control?.close()
                    control = null
                    if (remoteDisconnected) throw FoxError("Isolated Tor process stopped at bootstrap $lastProgress%: $lastSummary")
                    Thread.sleep(250)
                    continue
                }
                val progress = FoxTorPlan.bootstrapProgress(reply)
                if (progress >= 0) {
                    lastProgress = progress
                    lastSummary = FoxTorPlan.bootstrapSummary(reply)
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
                Thread.sleep(300)
            }
            val progress = if (lastProgress < 0) "unknown" else lastProgress.toString()
            throw FoxError("${mode.name} Tor bootstrap timed out after 90 seconds at $progress%: $lastSummary")
        }

        private fun openControl(): RemoteControl {
            val socketFile = File(app.getDir("TorService", Context.MODE_PRIVATE), "data/ControlSocket")
            val socket = LocalSocket()
            return try {
                socket.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.soTimeout = 5_000
                RemoteControl(socket).also { it.command("AUTHENTICATE") }
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
            if (localPort !in 1..65535) throw FoxError("$name returned invalid local port $localPort")
            return localPort
        }

        override fun diagnostics(): String {
            val details = mutableListOf<String>()
            details += "Last native stage: $diagnosticStage"
            if (lastProgress >= 0 || lastSummary.isNotBlank()) details += "Last bootstrap: $lastProgress% $lastSummary"
            if (remoteDisconnected) details += "Isolated Tor process disconnected"
            logFile?.takeIf { it.isFile }?.let { file ->
                val tail = file.readLines().takeLast(18).joinToString("\n")
                if (tail.isNotBlank()) details += "Tor log tail:\n$tail"
            }
            return details.joinToString("\n").take(2_600)
        }

        override fun close() {
            closing = true
            diagnosticStage = "shutdown"
            try {
                control?.command("SIGNAL SHUTDOWN")
            } catch (error: Exception) {
                Log.w("GozarTor", "Tor shutdown signal failed: " + error.javaClass.simpleName)
            }
            control?.close()
            control = null
            waitForPortClose(port)
            connection?.let { connected ->
                if (bound) {
                    try {
                        app.unbindService(connected)
                    } catch (error: Exception) {
                        Log.w("GozarTor", "Tor unbind failed: " + error.javaClass.simpleName)
                    }
                }
            }
            bound = false
            connection = null
            stopRemoteTor()
            transport?.let { name ->
                try {
                    controller?.stop(name)
                } catch (error: Exception) {
                    Log.w("GozarTor", "$name stop failed: " + error.javaClass.simpleName)
                }
            }
            controller = null
            transport = null
            clean(app)
            logFile = null
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
            Log.w("GozarTor", "Isolated Tor process did not exit after shutdown")
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
            Log.w("GozarTor", "Tor SOCKS port $observedPort remained open after shutdown")
        }
    }
}
