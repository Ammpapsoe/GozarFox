package com.gozar

/**
 * The exact text of the hev-socks5-tunnel configuration document.
 *
 * This is deliberately a pure function of its inputs and lives outside the
 * Android-dependent wrapper so that it can be unit-tested on the JVM: a
 * malformed document makes the native engine exit before it ever reads a
 * packet from the tun device, and errors of that kind are invisible from
 * Kotlin (the JNI start call reports only whether the worker thread spawned).
 *
 * The document is a fixed shape. Every value is validated here for two
 * reasons: YAML scalars must not be able to break out of their quotes, and a
 * tunnel mtu that cannot hold a single IPv4 TCP segment turns every connection
 * into a silent stall rather than a helpful error.
 */
internal object HevConfig {

    /** Smallest mtu hev 2.20.0 accepts; below this the engine refuses to start. */
    const val MIN_MTU = 1280

    /** Largest value that still fits the engine's u16 length arithmetic. */
    const val MAX_MTU = 9000

    private fun quote(value: String): String {
        require(value.isNotEmpty()) { "empty credential" }
        require(value.none { it == '\'' || it == '\\' || it == '\n' || it == '\r' || it == '\u0000' }) {
            "credential must not contain quote, backslash or control characters"
        }
        return "'$value'"
    }

    /** Validate and normalise a tunnel mtu, or throw with the offending value. */
    fun mtu(value: Int): Int {
        require(value in MIN_MTU..MAX_MTU) { "tunnel mtu out of range: $value" }
        return value
    }

    fun port(value: Int): Int {
        require(value in 1..65535) { "proxy port out of range: $value" }
        return value
    }

    /**
     * Build the document the native engine reads.
     *
     * [pass] is the shared SOCKS credential the local proxy expects; the
     * engine presents it as both username and password because [FoxProxy]
     * accepts either half as the bearer material.
     */
    fun document(mtu: Int, socksPort: Int, pass: String): String = buildString {
        append("tunnel:\n")
        append("  mtu: ").append(mtu(mtu)).append('\n')
        append("socks5:\n")
        append("  address: 127.0.0.1\n")
        append("  port: ").append(port(socksPort)).append('\n')
        append("  username: ").append(quote(pass)).append('\n')
        append("  password: ").append(quote(pass)).append('\n')
        append("  udp: 'udp'\n")
        append("misc:\n")
        append("  log-level: ").append(LOG_LEVEL).append('\n')
    }

    /**
     * `warn` keeps the release build quiet. It still reports the failures that
     * matter to us: a configuration or tun device the engine cannot open is
     * logged at error level before it exits.
     */
    const val LOG_LEVEL = "warn"
}
