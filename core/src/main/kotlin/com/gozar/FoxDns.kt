package com.gozar

import java.net.Inet4Address
import java.net.InetAddress

/**
 * DNS poisoning guard and address memory.
 *
 * Iranian filtering answers sanctioned hostnames with fake addresses -
 * classically private ranges such as 10.10.34.34, which can never route to
 * the real edge. A public hostname that resolves only to non-routable or
 * private space is therefore not "unreachable", it is *poisoned*, and the
 * core reacts differently: it escalates to the last known-good address
 * (safe because every Mozilla edge is verified against our SPKI pins) or to
 * the relay, instead of hammering a black hole.
 */
object FoxDns {

    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private val lock = Any()
    private val good = LinkedHashMap<String, Pair<String, Long>>()

    /** True for addresses a public hostname must never resolve to. */
    fun fake(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress ||
            address.isSiteLocalAddress
        ) return true
        // Carrier-grade NAT space is also a common poisoning sinkhole.
        if (address is Inet4Address) {
            val bytes = address.address
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 100 && second in 64..127) return true
        }
        return false
    }

    /** Resolve, dropping poisoned answers; empty means "poisoned or failed". */
    fun resolve(host: String): List<InetAddress> = try {
        InetAddress.getAllByName(host).filterNot { fake(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun poisoned(host: String): Boolean {
        val raw = try {
            InetAddress.getAllByName(host).toList()
        } catch (_: Exception) {
            return false
        }
        return raw.isNotEmpty() && raw.all { fake(it) }
    }

    fun remember(host: String, ip: String) = synchronized(lock) {
        if (good.size >= 64 && host !in good) good.remove(good.keys.first())
        good[host.lowercase()] = ip to System.currentTimeMillis()
    }

    fun cached(host: String): String? = synchronized(lock) {
        val entry = good[host.lowercase()] ?: return null
        if (System.currentTimeMillis() - entry.second > TTL_MS) {
            good.remove(host.lowercase())
            return null
        }
        entry.first
    }
}
