package com.gozar

import android.content.Context
import hev.htproxy.TProxyService
import java.io.File

/**
 * Lifecycle wrapper around the native hev-socks5-tunnel engine.
 *
 * The native process owns the single tun fd once started; packets it cannot
 * forward to the loopback proxy (this happens when the proxy is down) are
 * dropped by the engine, never sent direct. All process-wide state lives in
 * JNI; this object only translates between Android and the C ABI.
 */
internal object HevEngine {

    /**
     * Write the config and hand the tun fd to hev.
     *
     * Returns true only when the engine is still running a moment after the
     * worker thread was started. The JNI start call reports merely that
     * `pthread_create` succeeded, and the worker exits immediately when the
     * configuration cannot be opened (for example because the file mode makes
     * it unreadable). Reporting that as success left the UI showing a
     * connected state while no packet was ever read from the tun device.
     */
    fun start(context: Context, mtu: Int, socksPort: Int, pass: String, tunFd: Int): Boolean {
        if (!TProxyService.isNativeAvailable) return false
        val document = try {
            HevConfig.document(mtu, socksPort, pass)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val file = File(context.cacheDir, CONFIG_FILE)
        try {
            // The editor creates the file owner-only (0600), which is what a
            // credential-bearing document needs. Do not call setReadable or
            // setWritable afterwards: setReadable(false, false) clears the
            // owner read bit too, and the native engine then cannot open its
            // own configuration and exits before touching the tun device.
            file.writeText(document)
        } catch (_: Exception) {
            return false
        }
        val started = try {
            TProxyService.TProxyStartService(file.absolutePath, tunFd)
        } catch (_: Exception) {
            false
        }
        if (!started) return false
        return aliveAfterStart()
    }

    fun stop() {
        if (!TProxyService.isNativeAvailable) return
        try {
            TProxyService.TProxyStopService()
        } catch (_: Exception) {
        }
    }

    fun isRunning(): Boolean =
        TProxyService.isNativeAvailable && runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)

    /** [txPackets, txBytes, rxPackets, rxBytes] with tx = device to network. */
    fun stats(): LongArray? =
        if (!TProxyService.isNativeAvailable) null
        else runCatching { TProxyService.TProxyGetStats() }.getOrNull()

    /**
     * The worker thread sets its running flag before it parses the config, so
     * one read is not proof of life. Poll until the flag survives the startup
     * window; a worker that exited leaves it cleared.
     */
    private fun aliveAfterStart(attempts: Int = ALIVE_ATTEMPTS, intervalMillis: Long = ALIVE_INTERVAL_MS): Boolean {
        for (attempt in 0 until attempts) {
            if (!isRunning()) return false
            if (attempt == attempts - 1) break
            try {
                Thread.sleep(intervalMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return isRunning()
    }

    internal const val CONFIG_FILE = "hev-socks5-config.yaml"
    private const val ALIVE_ATTEMPTS = 6
    private const val ALIVE_INTERVAL_MS = 50L
}
