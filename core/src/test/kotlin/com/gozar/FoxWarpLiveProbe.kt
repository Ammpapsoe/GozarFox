package com.gozar

import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit developer diagnostic, built only by workflow_dispatch and not used
 * by the app. Its proxy may route directly; it never registers a device or
 * prints credentials. Supply a protected, pre-existing test identity.
 */
object FoxWarpLiveProbe {
    @JvmStatic fun main(args: Array<String>) {
        if (args.size !in 3..4 || args[0] != "--allow-test-proxy" || (args.size == 4 && args[3] != "--reenroll-same-device")) {
            System.err.println("STATE=KOTLIN_PROBE_REQUIRES_AUTHORIZATION")
            return
        }
        val port = args[1].toIntOrNull()
        if (port == null || port !in 1..65535) throw IllegalArgumentException("invalid local proxy")
        val file = File(args[2])
        if (!file.isFile || file.length() > 1_048_576) throw IllegalArgumentException("identity unavailable")
        val permissions = java.nio.file.Files.getPosixFilePermissions(file.toPath())
        if (permissions.any { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) {
            throw IllegalArgumentException("identity must be private")
        }
        val valid = AtomicBoolean(true)
        val reenroll = args.size == 4
        var saved = if (reenroll) org.json.JSONObject(file.readText()).put("server_key", "").put("ipv4", "").toString() else file.readText()
        var enrolled = false
        val warp = FoxWarp(port, { saved }, { updated ->
            // Keep the same private file usable if re-enrollment changes the
            // address or pin; the file was checked for owner-only permissions.
            file.writeText(updated)
            saved = updated
            enrolled = true
            true
        }, { valid.get() })
        var failed = false
        try {
            warp.start()
            if (reenroll && !enrolled) throw IllegalStateException("WARP reenrollment was not persisted")
            if (reenroll) println("STATE=KOTLIN_WARP_SAME_DEVICE_REENROLLED")
            println("STATE=KOTLIN_WARP_AUTHENTICATED_UDP_SCAN_OK")
            val flow = warp.openUDP("1.1.1.1", 53)
            try {
                val query = byteArrayOf(0x79, 0x31, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                    7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                    3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 1, 0, 1)
                flow.send(query)
                val replies = ArrayBlockingQueue<Boolean>(1)
                Thread({
                    val response = try { flow.receive() } catch (_: Exception) { null }
                    replies.offer(response != null && response.size >= 12 && response[0] == 0x79.toByte() &&
                        response[1] == 0x31.toByte() && response[2].toInt() and 0x80 != 0 && response[3].toInt() and 15 == 0)
                }, "warp-live-read").apply { isDaemon = true; start() }
                if (replies.poll(15, TimeUnit.SECONDS) != true) throw IllegalStateException("UDP reply unavailable")
                println("STATE=KOTLIN_WARP_REAL_UDP_ROUND_TRIP_OK")
            } finally { flow.close() }
        } catch (_: Exception) {
            System.err.println("STATE=KOTLIN_WARP_PROBE_FAILED")
            failed = true
        } finally {
            valid.set(false)
            warp.close()
        }
        if (failed) kotlin.system.exitProcess(1)
    }
}
