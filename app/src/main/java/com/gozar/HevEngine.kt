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
     * Write the config and hand the tun fd to hev. Returns true when the
     * worker thread started; false leaves no native state behind.
     */
    fun start(context: Context, mtu: Int, socksPort: Int, pass: String, tunFd: Int): Boolean {
        if (!TProxyService.isNativeAvailable) return false
        val config = buildString {
            append("tunnel:\n")
            append("  mtu: ").append(mtu).append('\n')
            append("socks5:\n")
            append("  address: 127.0.0.1\n")
            append("  port: ").append(socksPort).append('\n')
            // Base64 credentials never contain a single quote, so plain
            // single-quoted YAML scalars are safe here.
            append("  username: '").append(pass).append("'\n")
            append("  password: '").append(pass).append("'\n")
            append("  udp: 'udp'\n")
            append("misc:\n")
            append("  log-level: warn\n")
        }
        val file = File(context.cacheDir, "hev-socks5-config.yaml")
        return try {
            file.writeText(config)
            file.setReadable(false, false)
            file.setWritable(true, true)
            TProxyService.TProxyStartService(file.absolutePath, tunFd)
        } catch (_: Exception) {
            false
        }
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
}
