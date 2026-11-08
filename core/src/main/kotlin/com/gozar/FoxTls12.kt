package com.gozar

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Restrict one HTTPS retry to TLS 1.2 without changing trust or host checks. */
internal class FoxTls12(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    private fun restrict(socket: Socket): Socket {
        val tls = socket as? SSLSocket ?: run {
            socket.close()
            throw IOException("TLS socket unavailable")
        }
        if (!tls.supportedProtocols.contains("TLSv1.2")) {
            tls.close()
            throw IOException("TLS 1.2 is unavailable")
        }
        try { tls.enabledProtocols = arrayOf("TLSv1.2") }
        catch (e: Exception) { tls.close(); throw IOException("TLS 1.2 is unavailable", e) }
        return tls
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = restrict(delegate.createSocket())
    override fun createSocket(host: String, port: Int): Socket = restrict(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        restrict(delegate.createSocket(host, port, local, localPort))
    override fun createSocket(address: InetAddress, port: Int): Socket = restrict(delegate.createSocket(address, port))
    override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        restrict(delegate.createSocket(address, port, local, localPort))
    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        restrict(delegate.createSocket(socket, host, port, autoClose))
}
