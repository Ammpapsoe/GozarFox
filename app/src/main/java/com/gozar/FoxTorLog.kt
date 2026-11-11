package com.gozar

import java.util.ArrayDeque

internal object FoxTorLog {
    private const val CAPACITY = 240
    private val startedAt = System.nanoTime()
    private val entries = ArrayDeque<String>()

    @Synchronized
    fun record(event: String, detail: String = "") {
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
        val safeEvent = event.uppercase().replace(Regex("[^A-Z0-9_.-]"), "_").take(48)
        val safeDetail = sanitize(detail)
        entries.addLast("+%.3fs %s%s".format(elapsed / 1000.0, safeEvent, if (safeDetail.isEmpty()) "" else " · $safeDetail"))
        while (entries.size > CAPACITY) entries.removeFirst()
    }

    @Synchronized
    fun render(): String = entries.joinToString("\n")

    @Synchronized
    fun clear() = entries.clear()

    fun sanitize(value: String): String = value
        .replace(Regex("(?i)(obfs4|webtunnel|snowflake|meek_lite)\\s+\\S+.*"), "[bridge redacted]")
        .replace(Regex("(?i)(cert=|url=|front=|fingerprint=|password=|token=|authorization=|cookie=)[^\\s;]+"), "$1[redacted]")
        .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+"), "$1[redacted]")
        .replace(Regex("(?i)https?://[^\\s;]+"), "[url redacted]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
        .replace(Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])"), "[ip]")
        .replace(Regex("(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])"), "[ip]")
        .replace(Regex("(?<![0-9A-Fa-f])[0-9A-Fa-f]{40}(?![0-9A-Fa-f])"), "[fingerprint]")
        .replace(Regex("(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{70}(?:==)?(?![A-Za-z0-9+/=])"), "[certificate]")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .take(360)
}
