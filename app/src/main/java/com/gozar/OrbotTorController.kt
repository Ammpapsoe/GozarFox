package com.gozar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.IBinder
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.util.StringTokenizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import net.freehaven.tor.control.TorControlCommands
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService

internal class OrbotTorController(context: Context, private val listener: (String) -> Unit) {
    private val context = context.applicationContext
    val torStartRequested = AtomicBoolean(false)
    @Volatile var shouldUnbindTorService = false
    @Volatile var mCurrentStatus = TorService.STATUS_OFF
    @Volatile var conn: TorControlConnection? = null
    @Volatile var socksPort = -1
    @Volatile var remoteDisconnected = false
    private var torServiceConnection: ServiceConnection? = null
    @Volatile private var controlSocket: LocalSocket? = null
    @Volatile private var connectionStage = "idle"

    fun diagnostics(): String = connectionStage

    @Synchronized
    fun startTorService() {
        if (!torStartRequested.compareAndSet(false, true)) return
        connectionStage = "bind-requested"
        FoxTorLog.record("ORBOT_BIND_REQUESTED")
        PrintWriter(FileWriter(TorService.getDefaultsTorrc(context), false)).use { defaults ->
            defaults.println("DNSPort 0")
            defaults.println("TransPort 0")
            defaults.println("DisableNetwork 1")
        }
        remoteDisconnected = false
        mCurrentStatus = TorService.STATUS_STARTING
        listener(TorService.STATUS_STARTING)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
                connectionStage = "service-connected"
                FoxTorLog.record("ORBOT_SERVICE_CONNECTED")
                try {
                    connectControlSocket()
                    if (!torStartRequested.get() || conn == null) return
                    Thread.sleep(1000)
                    if (!torStartRequested.get()) return
                    initControlConnection()
                    conn?.setEvents(listOf(TorControlCommands.EVENT_STATUS_CLIENT))
                } catch (error: Exception) {
                    connectionStage = "connection-error:" + error.javaClass.simpleName
                    FoxTorLog.record("ORBOT_CONNECTION_ERROR", error.javaClass.simpleName)
                    remoteDisconnected = true
                    mCurrentStatus = TorService.STATUS_OFF
                    torStartRequested.set(false)
                    clearControl()
                    listener(TorService.STATUS_OFF)
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                connectionStage = "service-disconnected"
                FoxTorLog.record("ORBOT_SERVICE_DISCONNECTED")
                remoteDisconnected = true
                mCurrentStatus = TorService.STATUS_OFF
                torStartRequested.set(false)
                clearControl()
                listener(TorService.STATUS_OFF)
            }

            override fun onBindingDied(componentName: ComponentName) {
                connectionStage = "binding-died"
                FoxTorLog.record("ORBOT_BINDING_DIED")
                remoteDisconnected = true
                if (torServiceConnection === this) {
                    if (shouldUnbindTorService) {
                        context.unbindService(this)
                        shouldUnbindTorService = false
                    }
                    clearControl()
                    torServiceConnection = null
                    torStartRequested.set(false)
                }
                mCurrentStatus = TorService.STATUS_OFF
                listener(TorService.STATUS_OFF)
            }
        }
        torServiceConnection = connection
        val serviceIntent = Intent(context, TorService::class.java)
        shouldUnbindTorService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.bindService(serviceIntent, Context.BIND_AUTO_CREATE, executor, connection)
        } else {
            context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        }
        if (!shouldUnbindTorService) {
            connectionStage = "bind-failed"
            FoxTorLog.record("ORBOT_BIND_FAILED")
            torServiceConnection = null
            clearControl()
            torStartRequested.set(false)
            mCurrentStatus = TorService.STATUS_OFF
            throw IOException("bindService failed")
        }
        connectionStage = "bound-awaiting-service"
    }

    private fun connectControlSocket() {
        connectionStage = "control-connecting"
        val socketFile = File(File(context.getDir("TorService", Context.MODE_PRIVATE), "data"), "ControlSocket")
        while (conn == null) {
            if (!torStartRequested.get()) return
            val candidate = LocalSocket()
            try {
                candidate.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                val control = TorControlConnection(candidate.inputStream, candidate.outputStream)
                controlSocket = candidate
                control.launchThread(true)
                control.authenticate(ByteArray(0))
                conn = control
                connectionStage = "control-connected"
                FoxTorLog.record("ORBOT_CONTROL_CONNECTED")
            } catch (_: IOException) {
                try {
                    candidate.close()
                } catch (_: IOException) {
                }
                Thread.sleep(500)
            }
        }
    }

    private fun initControlConnection() {
        val control = conn ?: return
        val confSocks = control.getInfo("net/listeners/socks")
        val tokens = StringTokenizer(confSocks, " ")
        while (tokens.hasMoreTokens()) {
            val value = tokens.nextToken()
            val split = value.lastIndexOf(':')
            if (split > 0) socksPort = value.substring(split + 1).replace("\"", "").toInt()
        }
        mCurrentStatus = TorService.STATUS_ON
        connectionStage = "control-ready"
        FoxTorLog.record("ORBOT_CONTROL_READY")
        listener(TorService.STATUS_ON)
    }

    private fun clearControl() {
        conn = null
        controlSocket?.let { socket ->
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
        controlSocket = null
    }

    fun stopTor() {
        connectionStage = "stopping"
        conn?.let { control ->
            try {
                control.shutdownTor(TorControlCommands.SIGNAL_SHUTDOWN)
            } catch (_: Exception) {
            }
        }
        if (shouldUnbindTorService) {
            try {
                torServiceConnection?.let(context::unbindService)
            } catch (_: Exception) {
            }
            shouldUnbindTorService = false
        }
        clearControl()
        torServiceConnection = null
        torStartRequested.set(false)
        socksPort = -1
        mCurrentStatus = TorService.STATUS_OFF
        connectionStage = "stopped"
        FoxTorLog.record("ORBOT_CONTROLLER_STOPPED")
        listener(TorService.STATUS_OFF)
    }

    companion object {
        private val executor = Executors.newCachedThreadPool()
    }
}
