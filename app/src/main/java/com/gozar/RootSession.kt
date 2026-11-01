package com.gozar

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal object RootAccess {
    fun granted(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            val timer = thread(isDaemon = true) {
                try {
                    Thread.sleep(30_000)
                    process.destroy()
                } catch (_: InterruptedException) {
                }
            }
            val result = process.inputStream.bufferedReader().use { it.readText().trim() }
            val exit = process.waitFor()
            timer.interrupt()
            exit == 0 && result == "0"
        } catch (_: Exception) {
            false
        }
    }
}

internal class RootSession private constructor(
    private val flag: File,
    private val plan: RootPlan,
    private val callback: (Boolean) -> Unit
) {
    @Volatile private var cancelled = false
    private var worker: Thread? = null

    fun start() {
        worker = thread(isDaemon = true, name = "gozar-root") {
            val ready = AtomicBoolean(false)
            var process: java.lang.Process? = null
            try {
                if (!cancelled && flag.createNewFile()) {
                    if (cancelled) {
                        flag.delete()
                        return@thread
                    }
                    process = ProcessBuilder("su", "-c", plan.commands()).redirectErrorStream(true).start()
                    val shell = process
                    val timeout = thread(isDaemon = true) {
                        try {
                            Thread.sleep(30_000)
                            if (!ready.get()) shell.destroy()
                        } catch (_: InterruptedException) {
                        }
                    }
                    val reader = shell.inputStream.bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.trim() == "GOZAR_ROOT_READY") {
                            ready.set(true)
                            timeout.interrupt()
                            if (!cancelled) callback(true)
                        }
                    }
                    shell.waitFor()
                    timeout.interrupt()
                }
            } catch (_: Exception) {
            } finally {
                flag.delete()
                if (!cancelled) callback(false)
                try { process?.destroy() } catch (_: Exception) { }
            }
        }
    }

    fun stop() {
        cancelled = true
        flag.delete()
        try { worker?.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    companion object {
        fun create(context: Context, descriptor: ParcelFileDescriptor, callback: (Boolean) -> Unit): RootSession? {
            return try {
                val info = File("/proc/self/fdinfo/${descriptor.fd}").readLines()
                val iface = info.firstOrNull { it.startsWith("iff:") }?.substringAfter(':')?.trim() ?: return null
                val stat = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))
                val startedAt = stat.getOrNull(19) ?: return null
                val random = SecureRandom()
                val suffix = String.format(Locale.ROOT, "%08x", random.nextInt())
                val flag = File(context.filesDir, ".gozar-root-$suffix")
                val plan = RootPlan(
                    iface, Process.myPid(), startedAt, flag.absolutePath, suffix,
                    30000 + random.nextInt(30000), 7000 + random.nextInt(3000), false
                )
                RootSession(flag, plan, callback)
            } catch (_: Exception) {
                null
            }
        }
    }
}
