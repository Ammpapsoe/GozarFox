package com.gozar

import IPtProxy.Controller
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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

    override fun run(context: Context, body: () -> Unit) = synchronized(lock) {
        val app = context.applicationContext
        var active: FoxTorAttempt? = null
        for (mode in FoxTorPlan.order) {
            val candidate = attemptFactory(app, mode)
            try {
                candidate.start()
                reachability(candidate.port)
                active = candidate
                break
            } catch (_: InterruptedException) {
                candidate.close()
                Thread.currentThread().interrupt()
                throw FoxError("Secure connection interrupted")
            } catch (_: Exception) {
                candidate.close()
            }
        }
        val attempt = active ?: throw FoxError("Secure connection could not be established")
        try {
            FoxNet.throughLoginSocks(attempt.port, body)
        } finally {
            attempt.close()
        }
    }

    fun clean(context: Context) {
        File(context.cacheDir, "gozar-login-transport").deleteRecursively()
        File(context.cacheDir, "TorService").deleteRecursively()
        context.getDir("TorService", Context.MODE_PRIVATE).deleteRecursively()
    }

    private class AndroidAttempt(private val app: Context, private val mode: FoxTorPlan.Mode) : FoxTorAttempt {
        private val ready = CountDownLatch(1)
        private var controller: Controller? = null
        private var transport: String? = null
        private var connection: ServiceConnection? = null
        private var service: TorService? = null
        private var bound = false
        override var port: Int = 0
            private set

        override fun start(): Int {
            clean(app)
            val state = File(app.cacheDir, "gozar-login-transport")
            if (!state.mkdirs() && !state.isDirectory) throw FoxError("Secure connection could not be initialized")
            transport = FoxTorPlan.transportName(mode)
            val transportPort = startTransport(state)
            TorService.getTorrc(app).writeText(FoxTorPlan.torrc(mode, transportPort))
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
            if (!bound) throw FoxError("Secure connection could not be initialized")
            if (!ready.await(15, TimeUnit.SECONDS)) throw FoxError("Secure connection timed out")
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FoxTorPlan.timeoutMs(mode))
            while (System.nanoTime() < deadline) {
                val current = service ?: throw FoxError("Secure connection stopped")
                val progress = FoxTorPlan.bootstrapProgress(current.getInfo("status/bootstrap-phase"))
                val socks = current.socksPort
                if (progress == 100 && socks in 1..65535) {
                    port = socks
                    return socks
                }
                Thread.sleep(300)
            }
            throw FoxError("Secure connection timed out")
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
            if (localPort !in 1..65535) throw FoxError("Secure transport could not be initialized")
            return localPort
        }

        override fun close() {
            try {
                service?.torControlConnection?.shutdownTor("SHUTDOWN")
            } catch (_: Exception) {
            }
            waitForPortClose()
            connection?.let { connected ->
                if (bound) {
                    try {
                        app.unbindService(connected)
                    } catch (_: Exception) {
                    }
                }
            }
            bound = false
            service = null
            try {
                app.stopService(Intent(app, TorService::class.java))
            } catch (_: Exception) {
            }
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

        private fun waitForPortClose() {
            if (port !in 1..65535) return
            repeat(40) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), 100)
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
