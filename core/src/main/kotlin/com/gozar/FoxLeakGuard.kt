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
    fun lookupA(resolver: FoxResolver, host: String, scope: FoxDnsScope? = null): Lookup {
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
        val body = try { resolver.answer(query, scope) } catch (_: Exception) { null }
            ?: return Lookup(LookupCode.UNAVAILABLE)
        return parseAnswer(body)
    }

    private fun parseAnswer(body: ByteArray): Lookup {
        val unavailable = Lookup(LookupCode.UNAVAILABLE)
        if (body.size !in 12..65535 || u16(body, 4) != 1) return unavailable
        val flags = body[2].toInt() and 255
        // A truncated reply, a non-query opcode, or a malformed reserved bit
        // is not positive evidence. FoxResolver already matches the question
        // and transaction ID before this diagnostic parser sees the answer.
        if (flags and 0x80 == 0 || flags and 0x78 != 0 || flags and 0x02 != 0 || body[3].toInt() and 0x40 != 0) return unavailable
        val code = body[3].toInt() and 15
        if (code != 0 && code != 3) return unavailable
        val answers = u16(body, 6)
        val records = answers + u16(body, 8) + u16(body, 10)
        if (answers > 256 || records > 512) return unavailable
        val question = readName(body, 12) ?: return unavailable
        var at = question.next
        if (at + 4 > body.size || u16(body, at) != 1 || u16(body, at + 2) != 1) return unavailable
        at += 4
        val addresses = HashMap<String, MutableList<String>>()
        val aliases = HashMap<String, String>()
        repeat(records) { index ->
            val owner = readName(body, at) ?: return unavailable
            at = owner.next
            if (at + 10 > body.size) return unavailable
            val type = u16(body, at)
            val recordClass = u16(body, at + 2)
            val length = u16(body, at + 8)
            // The high byte of an OPT record's TTL carries the extended RCODE.
            // Do not interpret an EDNS error with a low four-bit RCODE of zero
            // as a successful positive lookup.
            if (type == 41 && body[at + 4].toInt() and 255 != 0) return unavailable
            at += 10
            if (length > body.size - at) return unavailable
            val end = at + length
            if (index < answers && recordClass == 1) {
                when (type) {
                    1 -> {
                        if (length != 4) return unavailable
                        val value = (0..3).joinToString(".") { part -> (body[at + part].toInt() and 255).toString() }
                        addresses.getOrPut(owner.text) { ArrayList() }.add(value)
                    }
                    5 -> {
                        val alias = readName(body, at) ?: return unavailable
                        if (alias.next != end) return unavailable
                        val previous = aliases.put(owner.text, alias.text)
                        if (previous != null && previous != alias.text) return unavailable
                    }
                }
            }
            at = end
        }
        if (at != body.size) return unavailable
        // NXDOMAIN with an A answer is contradictory, not evidence that the
        // randomized canary was absent. Check the alias chain even for an
        // NXDOMAIN so a cycle cannot masquerade as a valid negative result.
        if (code == 3 && addresses.isNotEmpty()) return unavailable
        var name = question.text
        val visited = HashSet<String>()
        repeat(32) {
            if (!visited.add(name)) return unavailable
            val found = addresses[name].orEmpty()
            val alias = aliases[name]
            if (found.isNotEmpty()) return if (alias == null) Lookup(LookupCode.ANSWER, found) else unavailable
            if (alias == null) return Lookup(if (code == 3) LookupCode.NXDOMAIN else LookupCode.ANSWER)
            name = alias
        }
        return unavailable
    }

    private data class WireName(val text: String, val next: Int)

    /** Decode a bounded DNS name, including backwards compression pointers. */
    private fun readName(data: ByteArray, start: Int): WireName? {
        var at = start
        var next = -1
        var wireLength = 1 // terminating root label
        val labels = ArrayList<String>()
        var steps = 0
        while (at in data.indices && steps++ < 128) {
            val size = data[at].toInt() and 255
            if (size == 0) return WireName(labels.joinToString("."), if (next < 0) at + 1 else next)
            if (size and 0xc0 == 0xc0) {
                if (at + 1 >= data.size) return null
                val pointer = ((size and 0x3f) shl 8) or (data[at + 1].toInt() and 255)
                // RFC 1035 compression points to a prior occurrence. Requiring
                // a backwards target also prevents pointer cycles.
                if (pointer >= at) return null
                if (next < 0) next = at + 2
                at = pointer
                continue
            }
            if (size and 0xc0 != 0 || size > 63 || at + 1 + size > data.size) return null
            wireLength += size + 1
            if (wireLength > 255) return null
            val label = StringBuilder(size * 2)
            for (index in at + 1 until at + 1 + size) {
                var value = data[index].toInt() and 255
                if (value in 65..90) value += 32 // DNS names are ASCII case-insensitive.
                label.append("0123456789abcdef"[value ushr 4])
                label.append("0123456789abcdef"[value and 15])
            }
            // Hex labels avoid treating a literal dot inside a wire label as
            // a separator while comparing answer owners and CNAME targets.
            labels.add(label.toString())
            at += size + 1
        }
        return null
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
