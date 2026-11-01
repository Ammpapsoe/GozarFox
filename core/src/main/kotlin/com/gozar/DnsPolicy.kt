package com.gozar

import java.net.URI

object DnsPolicy {
    const val AUTO = "auto"
    const val CLOUDFLARE = "cloudflare"
    const val QUAD9 = "quad9"
    const val CUSTOM = "custom"
    const val ADGUARD = "https://dns.adguard-dns.com/dns-query"
    private const val GOOGLE = "https://dns.google/dns-query"
    private const val MOZILLA = "https://mozilla.cloudflare-dns.com/dns-query"
    private const val QUAD9_URL = "https://dns.quad9.net/dns-query"

    fun validCustom(value: String): Boolean {
        if (value.isEmpty() || value.length > 2048 || value != value.trim()) return false
        return try {
            val uri = URI(value)
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null &&
                (uri.port == -1 || uri.port in 1..65535) &&
                !uri.isOpaque
        } catch (_: Exception) {
            false
        }
    }

    fun endpoints(profile: String, custom: String, blockAds: Boolean): List<String> {
        if (blockAds) return listOf(ADGUARD)
        return when (profile) {
            AUTO -> listOf(GOOGLE, MOZILLA)
            CLOUDFLARE -> listOf(MOZILLA)
            QUAD9 -> listOf(QUAD9_URL)
            CUSTOM -> if (validCustom(custom)) listOf(custom) else emptyList()
            else -> emptyList()
        }
    }
}
