package com.gozar

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Leak accounting and self-test. Every packet or query the guard stops is
 * counted, and [selfTest] proves the two leak classes that matter for a
 * filtering-evasion VPN: DNS answers that did not come through the tunnel,
 * and an exit IP that is not the tunnel's.
 *
 * The counters are process-lifetime AtomicLongs; reading them is lock-free,
 * so drop paths in the packet pump never block on reporting.
 */
object FoxLeakGuard {

    const val IPV6_DROPPED = "ipv6-dropped"
    const val MALFORMED_DROPPED = "malformed-dropped"
    const val KILL_SWITCH_BLOCKED = "kill-switch-blocked"
    const val DNS_OUTSIDE_TUNNEL = "dns-outside-tunnel"

    private val counters = ConcurrentHashMap<String, AtomicLong>()

    @Volatile
    private var report: Report? = null

    data class Report(
        val dnsLeakFree: Boolean,
        val exitVerified: Boolean,
        val exitIp: String,
        val at: Long,
        val detail: String
    ) {
        val passed: Boolean get() = dnsLeakFree && exitVerified
    }

    fun note(event: String) {
        counters.getOrPut(event) { AtomicLong() }.incrementAndGet()
    }

    fun count(event: String): Long = counters[event]?.get() ?: 0L

    fun snapshot(): Map<String, Long> = counters.mapValues { it.value.get() }

    fun reset() {
        counters.clear()
        report = null
    }

    fun lastReport(): Report? = report

    /**
     * Runs the leak self-test. [resolve] must answer a canary query through
     * the tunnel resolver; [exitIp] must report the source IP the far side
     * sees, fetched through the tunnel. [baselineIp] is the address observed
     * before connecting — when known, the exit must differ from it.
     */
    fun selfTest(
        now: Long = System.currentTimeMillis(),
        baselineIp: String,
        resolve: (String) -> List<String>,
        exitIp: () -> String
    ): Report {
        val resolved = try {
            resolve(CANARY)
        } catch (_: Exception) {
            emptyList()
        }
        val dnsLeakFree = resolved.isNotEmpty() && resolved.none { isLocalAddress(it) }
        val exit = try {
            exitIp()
        } catch (_: Exception) {
            ""
        }
        val exitVerified = exit.isNotBlank() && !isLocalAddress(exit) &&
            (baselineIp.isBlank() || exit != baselineIp)
        val detail = buildString {
            if (!dnsLeakFree) append("dns answers missing or local; ")
            if (exit.isBlank()) append("no exit ip through the tunnel; ")
            else if (isLocalAddress(exit)) append("exit ip is local; ")
            else if (baselineIp.isNotBlank() && exit == baselineIp) append("exit ip unchanged from baseline; ")
        }.trim().trimEnd(';')
        val result = Report(dnsLeakFree, exitVerified, exit, now, detail)
        report = result
        return result
    }

    /** A hostname that only exists to be resolved; nothing serves on it. */
    const val CANARY = "leak-canary.gozar.invalid"

    /** True for loopback, private, link-local, and unspecified literals. */
    fun isLocalAddress(value: String): Boolean {
        var ip = value.trim()
        if (ip.startsWith("[")) {
            val end = ip.indexOf(']')
            if (end > 0) ip = ip.substring(1, end)
        }
        val parts = ip.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 0 || a == 10 || a == 127 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                a >= 224
        }
        if (ip.contains(':')) {
            val lower = ip.lowercase()
            if (lower == "::" || lower == "::1") return true
            if (lower.startsWith("fc") || lower.startsWith("fd")) return true // unique local
            if (lower.startsWith("fe8") || lower.startsWith("fe9") ||
                lower.startsWith("fea") || lower.startsWith("feb")
            ) return true // link local
            return false
        }
        // Not an IP literal at all — treat as unverified, not local.
        return false
    }
}
