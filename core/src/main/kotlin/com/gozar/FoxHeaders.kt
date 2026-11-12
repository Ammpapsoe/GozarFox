package com.gozar

/**
 * Browser-shaped request headers for Mozilla's own hosts.
 *
 * Mozilla's Fastly edge answers a bare 406 with an empty body to clients that
 * do not look like a browser: no `Accept`, no `Sec-Fetch-*` trio, and a
 * compressed `Accept-Encoding` that an API client would never ask for. These
 * values describe what a real page-level `fetch()` sends for a cross-origin
 * call, which is the shape Mozilla's infrastructure is built to accept.
 *
 * The set is applied to Mozilla hosts only. Sending it anywhere else would be
 * both useless and a needless fingerprint, so [forHost] returns nothing for
 * every other origin.
 */
object FoxHeaders {

    /** Hosts Mozilla owns, matched by exact name or `.mozilla.com`-style suffix. */
    private val owners = listOf(
        "accounts.firefox.com",
        "api.accounts.firefox.com",
        "oauth.accounts.firefox.com",
        "profile.accounts.firefox.com",
        "vpn.mozilla.org",
        "fpn.firefox.com",
        "firefox.settings.services.mozilla.com",
        "services.mozilla.com",
        "mozilla.org",
        "mozilla.com",
        "mozilla.net"
    )

    private fun isMozillaHost(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase().removeSuffix(".")
        if (host.isEmpty()) return false
        if (host.endsWith(".mozilla.org") || host.endsWith(".mozilla.com") || host.endsWith(".mozilla.net")) return true
        return owners.any { host == it || host.endsWith(".$it") }
    }

    /** Headers for `url`, or an empty map when the URL is not a Mozilla host. */
    fun forUrl(url: String): Map<String, String> {
        val host = try {
            java.net.URL(url).host
        } catch (error: Exception) {
            ""
        }
        return forHost(host)
    }

    /** Headers for `host`, or an empty map when the host is not Mozilla's. */
    fun forHost(rawHost: String): Map<String, String> = if (isMozillaHost(rawHost)) profile() else emptyMap()

    /**
     * The profile itself. `Accept-Encoding: identity` is deliberate: a 406 body
     * is empty and a challenge page is not something we can decode from a
     * compressed stream, so we ask for plain text and keep the diagnosis
     * readable. `Priority: u=4` is Chrome's urgency hint for a background
     * fetch and is ignored by servers that do not understand it.
     */
    private fun profile(): Map<String, String> = linkedMapOf(
        "Accept" to "application/json;q=0.9,*/*;q=0.2",
        "Accept-Encoding" to "identity",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Priority" to "u=4",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "no-cors",
        "Sec-Fetch-Site" to "none"
    )

    /**
     * Folds the profile under `overrides`: a caller that sets its own `Accept`
     * keeps it, and everything the caller did not mention is filled in.
     */
    fun merge(url: String, overrides: Map<String, String>): Map<String, String> {
        val profile = forUrl(url)
        if (profile.isEmpty()) return overrides
        val merged = LinkedHashMap<String, String>(profile.size + overrides.size)
        merged.putAll(profile)
        for ((key, value) in overrides) {
            val existing = merged.keys.firstOrNull { it.equals(key, true) }
            if (existing != null) merged.remove(existing)
            merged[key] = value
        }
        return merged
    }
}
