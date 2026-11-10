package com.gozar

import IPtProxy.Controller
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
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

    override fun run(context: Context, body: () -> Unit) = synchronized(lock) {
        val app = context.applicationContext
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

    private class AndroidAttempt(private val app: Context, private val mode: FoxTorPlan.Mode) : FoxTorAttempt {
        private val ready = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        private var controller: Controller? = null
        private var transport: String? = null
        private var connection: ServiceConnection? = null
        private var receiver: BroadcastReceiver? = null
        private var service: TorService? = null
        private var bound = false
        private var logFile: File? = null
        @Volatile private var serviceError: String? = null
        @Volatile private var lastProgress = -1
        @Volatile private var lastSummary = ""
        override var port: Int = 0
            private set

        override fun start(): Int {
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
            torrc.writeText(FoxTorPlan.torrc(mode, transportPort, diagnosticLog.absolutePath))
            observeService()
            val connected = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = (binder as TorService.LocalBinder).service
                    ready.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }
            connection = connected
            bound = app.bindService(Intent(app, TorService::class.java), connected, Context.BIND_AUTO_CREATE)
            if (!bound) throw FoxError("Android refused to bind TorService")
            if (!ready.await(15, TimeUnit.SECONDS)) throw FoxError("TorService binding timed out after 15 seconds")
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FoxTorPlan.timeoutMs(mode))
            while (System.nanoTime() < deadline) {
                serviceError?.let { throw FoxError("TorService error at bootstrap $lastProgress%: $it") }
                val current = service ?: throw FoxError("TorService disconnected at bootstrap $lastProgress%: $lastSummary")
                if (current.torControlConnection == null) {
                    Thread.sleep(300)
                    continue
                }
                val reply = current.getInfo("status/bootstrap-phase")
                val progress = FoxTorPlan.bootstrapProgress(reply)
                if (progress >= 0) {
                    lastProgress = progress
                    lastSummary = FoxTorPlan.bootstrapSummary(reply)
                    report(app, R.string.login_tor_bootstrap, modeLabel(app, mode), progress)
                }
                val socks = current.socksPort
                if (progress == 100 && socks in 1..65535) {
                    port = socks
                    return socks
                }
                Thread.sleep(300)
            }
            val progress = if (lastProgress < 0) "unknown" else lastProgress.toString()
            throw FoxError("${mode.name} Tor bootstrap timed out after 90 seconds at $progress%: $lastSummary")
        }

        private fun observeService() {
            val local = LocalBroadcastManager.getInstance(app)
            val observer = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return
                    when (intent.action) {
                        TorService.ACTION_ERROR -> serviceError = intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Unknown TorService error"
                        TorService.ACTION_STATUS -> {
                            val status = intent.getStringExtra(TorService.EXTRA_STATUS)
                            if (status == TorService.STATUS_STOPPING || status == TorService.STATUS_OFF) stopped.countDown()
                        }
                    }
                }
            }
            receiver = observer
            local.registerReceiver(observer, IntentFilter().apply {
                addAction(TorService.ACTION_ERROR)
                addAction(TorService.ACTION_STATUS)
            })
        }

        private fun startTransport(state: File): Int {
            val name = transport ?: return 0
            val native = Controller(state.absolutePath, false, false, "ERROR", null)
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
            native.start(name, "")
            val localPort = native.port(name).toInt()
            if (localPort !in 1..65535) throw FoxError("$name returned invalid local port $localPort")
            return localPort
        }

        override fun diagnostics(): String {
            val details = mutableListOf<String>()
            if (lastProgress >= 0 || lastSummary.isNotBlank()) details += "Last bootstrap: $lastProgress% $lastSummary"
            serviceError?.let { details += "Service error: $it" }
            logFile?.takeIf { it.isFile }?.let { file ->
                val tail = file.readLines().takeLast(18).joinToString("\n")
                if (tail.isNotBlank()) details += "Tor log tail:\n$tail"
            }
            return details.joinToString("\n").take(2_600)
        }

        override fun close() {
            val current = service
            val socks = if (port in 1..65535) port else current?.socksPort ?: 0
            try {
                current?.torControlConnection?.shutdownTor("SHUTDOWN")
            } catch (error: Exception) {
                Log.w("GozarTor", "Tor shutdown signal failed: " + error.javaClass.simpleName)
            }
            var stoppedCleanly = !bound
            if (bound) {
                stoppedCleanly = try {
                    stopped.await(20, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            }
            if (!stoppedCleanly) {
                Log.w("GozarTor", "Tor did not stop within 20 seconds; forcing service stop")
                try {
                    app.stopService(Intent(app, TorService::class.java))
                } catch (error: Exception) {
                    Log.w("GozarTor", "Forced Tor stop failed: " + error.javaClass.simpleName)
                }
            }
            waitForPortClose(socks)
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
            service = null
            receiver?.let {
                try {
                    LocalBroadcastManager.getInstance(app).unregisterReceiver(it)
                } catch (_: Exception) {
                }
            }
            receiver = null
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
