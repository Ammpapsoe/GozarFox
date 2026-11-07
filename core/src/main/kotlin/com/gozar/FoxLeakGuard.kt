package com.gozar

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Packet-drop accounting and bounded tunnel diagnostics.
 *
 * A successful DNS lookup and a public-looking tunnel exit do NOT prove that
 * DNS, IPv6, WebRTC, or traffic from other app UIDs cannot bypass the VPN.
 * The Android VPN app itself is excluded from the VPN, so an ordinary OS
 * lookup here would in fact use the wrong network. The DNS probe below must
 * be given the VPN's resolver, whose DoH connection uses its force-tunnel
 * proxy; it reports connectivity and unexpected answers only.
 */
object FoxLeakGuard {
    const val IPV6_DROPPED = "ipv6-dropped"
    const val MALFORMED_DROPPED = "malformed-dropped"
    const val LOCKDOWN_INTERRUPTION = "lockdown-interruption"

    enum class Check { OK, ISSUE, UNKNOWN }
    enum class LookupCode { ANSWER, NXDOMAIN, UNAVAILABLE }
    data class Lookup(val code: LookupCode, val addresses: List<String> = emptyList())

    data class Report(
        val dnsCheck: Check,
        val exitCheck: Check,
        val exitIp: String,
        val at: Long
    ) {
        // Tunnel diagnostics cannot attest to the behavior of every app/UID,
        // Android's DNS, the physical interface, or the platform kill switch.
        val deviceLeakStatus: Check get() = Check.UNKNOWN
    }

    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val reports = FoxDiagnosticGate<Report>()

    fun note(event: String) {
        counters.getOrPut(event) { AtomicLong() }.incrementAndGet()
    }

    fun count(event: String): Long = counters[event]?.get() ?: 0L
    fun snapshot(): Map<String, Long> = counters.mapValues { it.value.get() }
    fun lastReport(): Report? = reports.current()
    fun beginReport(): Long = reports.begin()
    fun publish(ticket: Long, value: Report): Boolean = reports.publish(ticket, value)
    fun clearReport() { reports.invalidate() }

    fun reset() {
        counters.clear()
        clearReport()
    }

    /**
     * Produce an observation without publishing it. The caller must only
     * publish if the same VPN session is still connected when it completes.
     * An NXDOMAIN is distinct from SERVFAIL, a timeout, or malformed data.
     */
    fun diagnose(
        now: Long = System.currentTimeMillis(),
        positiveHost: String,
        canaryHost: String = "${UUID.randomUUID().toString().replace("-", "")}.gozar-check.invalid",
        resolve: (String) -> Lookup,
        exitIp: String
    ): Report {
        fun lookup(host: String): Lookup = try { resolve(host) } catch (_: Exception) {
            Lookup(LookupCode.UNAVAILABLE)
        }
        val positive = lookup(positiveHost)
        val canary = lookup(canaryHost)
        val dns = when {
            positive.code == LookupCode.ANSWER && positive.addresses.any { !isPublicAddress(it) } -> Check.ISSUE
            canary.code == LookupCode.ANSWER && canary.addresses.isNotEmpty() -> Check.ISSUE
            positive.code == LookupCode.ANSWER && positive.addresses.isNotEmpty() &&
                canary.code == LookupCode.NXDOMAIN -> Check.OK
            else -> Check.UNKNOWN
        }
        // This value was obtained via a tunnel-only probe. It is not a
        // comparison with the device's physical or default-network address.
        val exit = if (isPublicAddress(exitIp)) Check.OK else Check.UNKNOWN
        return Report(dns, exit, exitIp, now)
    }

    /** Query A records via the supplied VPN resolver; never use system DNS. */
    fun lookupA(resolver: FoxResolver, host: String): Lookup {
        val labels = host.trimEnd('.').split('.')
        if (host.length > 253 || labels.isEmpty() || labels.any { label ->
                label.isEmpty() || label.length > 63 || label.startsWith('-') || label.endsWith('-') ||
                    !label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
            }) return Lookup(LookupCode.UNAVAILABLE)
        val id = java.util.concurrent.ThreadLocalRandom.current().nextInt(65536)
        val query = ByteArrayOutputStream().apply {
            write(byteArrayOf((id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                write(bytes.size)
                write(bytes)
            }
            write(byteArrayOf(0, 0, 1, 0, 1))
        }.toByteArray()
        val body = try { resolver.answer(query) } catch (_: Exception) { null }
            ?: return Lookup(LookupCode.UNAVAILABLE)
        return parseAnswer(body)
    }

    private fun parseAnswer(body: ByteArray): Lookup {
        val unavailable = Lookup(LookupCode.UNAVAILABLE)
        if (body.size < 12 || body[2].toInt() and 0x80 == 0 || u16(body, 4) != 1) return unavailable
        when (body[3].toInt() and 15) {
            3 -> return Lookup(LookupCode.NXDOMAIN)
            0 -> Unit
            else -> return unavailable
        }
        val count = u16(body, 6)
        if (count > 256) return unavailable
        var at = nameEnd(body, 12)
        if (at < 0 || at + 4 > body.size) return unavailable
        at += 4
        val addresses = ArrayList<String>()
        repeat(count) {
            at = nameEnd(body, at)
            if (at < 0 || at + 10 > body.size) return unavailable
            val type = u16(body, at)
            val recordClass = u16(body, at + 2)
            val length = u16(body, at + 8)
            at += 10
            if (at + length > body.size) return unavailable
            if (type == 1 && recordClass == 1 && length == 4) {
                addresses.add((0..3).joinToString(".") { index -> (body[at + index].toInt() and 255).toString() })
            }
            at += length
        }
        return Lookup(LookupCode.ANSWER, addresses)
    }

    private fun nameEnd(data: ByteArray, start: Int): Int {
        var at = start
        var steps = 0
        while (at < data.size && steps++ < 128) {
            val size = data[at].toInt() and 255
            if (size == 0) return at + 1
            if (size and 0xc0 == 0xc0) {
                if (at + 1 >= data.size) return -1
                val pointer = ((size and 0x3f) shl 8) or (data[at + 1].toInt() and 255)
                return if (pointer < data.size) at + 2 else -1
            }
            if (size > 63 || at + 1 + size > data.size) return -1
            at += size + 1
        }
        return -1
    }

    private fun u16(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)

    /** Accept only numeric, globally routable-looking IP literals; never resolve a name. */
    fun isPublicAddress(value: String): Boolean {
        val text = value.trim().removeSurrounding("[", "]")
        if (text.isEmpty()) return false
        if (!text.contains(':')) {
            val parts = text.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 ||
                        (it.length > 1 && it[0] == '0') || !it.all { digit -> digit in '0'..'9' } }) return false
            val bytes = parts.map { it.toIntOrNull() ?: return false }
            return ipv4Public(bytes)
        }
        // A colon plus this character whitelist makes this an IPv6 literal,
        // not a hostname; getByName cannot issue an OS DNS lookup for it.
        if (!text.matches(Regex("[0-9a-fA-F:.]+"))) return false
        val bytes = try { InetAddress.getByName(text).address } catch (_: Exception) { return false }
        if (bytes.size == 4) return ipv4Public(bytes.map { it.toInt() and 255 })
        if (bytes.size != 16) return false
        val a = bytes[0].toInt() and 255
        // Only global-unicast 2000::/3; reject 2001:db8::/32 documentation space.
        if (a and 0xe0 != 0x20) return false
        val b = bytes[1].toInt() and 255
        val c = bytes[2].toInt() and 255
        val d = bytes[3].toInt() and 255
        if (a == 0x20 && b == 0x01 && c == 0x0d && d == 0xb8) return false
        return true
    }

    private fun ipv4Public(parts: List<Int>): Boolean {
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val a = parts[0]
        val b = parts[1]
        val c = parts[2]
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false
        if (a == 100 && b in 64..127) return false
        if (a == 169 && b == 254) return false
        if (a == 172 && b in 16..31) return false
        if (a == 192 && b == 168) return false
        if (a == 192 && b == 0 && (c == 0 || c == 2)) return false
        if (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) return false
        if (a == 203 && b == 0 && c == 113) return false
        return true
    }
}
