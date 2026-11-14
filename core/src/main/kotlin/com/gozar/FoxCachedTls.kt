package com.gozar

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * Speaks to a remembered address instead of the resolver's answer, while
 * keeping the URL's hostname for SNI and certificate verification. Safe for
 * Mozilla edges because their certificates are additionally verified against
 * our SPKI pins; useless for an attacker because the pins, not the address,
 * decide trust.
 */
internal class FoxCachedTls(
    private val base: SSLSocketFactory,
    private val ip: String,
    private val timeoutMs: Int,
) : SSLSocketFactory() {

    private fun dialed(host: String, port: Int): Socket {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(ip, port), timeoutMs.coerceAtLeast(1000))
        return raw
    }

    private fun wrap(host: String, port: Int): Socket =
        base.createSocket(dialed(host, port), host, port, true)

    override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites
    override fun createSocket(host: String, port: Int): Socket = wrap(host, port)
    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        wrap(host, port)
    override fun createSocket(host: java.net.InetAddress, port: Int): Socket = wrap(host.hostName, port)
    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): Socket =
        wrap(address.hostName, port)
    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        base.createSocket(socket, host, port, autoClose)
}
