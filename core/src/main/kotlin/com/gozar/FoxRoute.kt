package com.gozar

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object FoxRoute {

    @Volatile
    var bypassLan = false

    @Volatile
    var extra: List<String> = emptyList()

    fun apply(lan: Boolean, list: String) {
        bypassLan = lan
        extra = split(list)
    }

    fun applyChain(raw: String) {
        FoxUpstream.hops = FoxUpstream.parse(raw)
    }

    fun split(raw: String): List<String> =
        raw.split(',', '\n', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun direct(host: String): Boolean {
        val name = host.trim().lowercase().trim('[', ']')
        if (name.isEmpty()) return false
        if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local")) return true
        if (extra.any { name == it || name.endsWith(".$it") }) return true
        if (!bypassLan) return false
        return isLan(name)
    }

    fun isLan(name: String): Boolean {
        val address = try {
            if (name.any { it.isLetter() && it != ':' }) null else InetAddress.getByName(name)
        } catch (e: Exception) {
            null
        } ?: return false
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
    }
}

class FoxSocketPipe private constructor(private val socket: Socket) : FoxPipe {

    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        try {
            input.read(buffer, offset, length)
        } catch (e: Exception) {
            -1
        }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        output.write(buffer, offset, length)
        output.flush()
    }

    override fun finishWrite() {
        socket.shutdownOutput()
    }

    override fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
        }
    }

    companion object {
        fun connect(host: String, port: Int, timeout: Int = 8000): FoxPipe? {
            val socket = Socket()
            return try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeout)
                socket.soTimeout = 0
                FoxSocketPipe(socket)
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) { }
                null
            }
        }
    }
}
