package com.gozar

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

internal class FoxSocksHttpBridge(private val socksPort: Int) : Closeable {
    private val server = ServerSocket()
    private val sockets = ConcurrentHashMap<Socket, Boolean>()
    @Volatile private var closed = false
    val port: Int
    private val acceptor: Thread

    init {
        require(socksPort in 1..65535) { "invalid SOCKS port" }
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
        port = server.localPort
        acceptor = thread(isDaemon = true, name = "gozar-login-bridge") {
            while (!closed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                sockets[client] = true
                thread(isDaemon = true, name = "gozar-login-connect") {
                    handle(client)
                }
            }
        }
    }

    override fun close() {
        closed = true
        try {
            server.close()
        } catch (_: Exception) {
        }
        for (socket in sockets.keys) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        sockets.clear()
        try {
            acceptor.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val target = parseConnect(input) ?: run {
                reject(output)
                return
            }
            val remote = Socket()
            upstream = remote
            sockets[remote] = true
            remote.soTimeout = 30_000
            remote.connect(InetSocketAddress("127.0.0.1", socksPort), 20_000)
            connectSocks(remote, target)
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            client.soTimeout = 60_000
            remote.soTimeout = 60_000
            val copy = thread(isDaemon = true, name = "gozar-login-upstream") {
                try {
                    pump(remote.getInputStream(), output)
                } catch (_: Exception) {
                } finally {
                    closeQuietly(client)
                    closeQuietly(remote)
                }
            }
            try {
                pump(input, remote.getOutputStream())
            } catch (_: Exception) {
            } finally {
                closeQuietly(client)
                closeQuietly(remote)
            }
            copy.join(1000)
        } catch (_: Exception) {
        } finally {
            closeQuietly(client)
            upstream?.let { closeQuietly(it) }
            sockets.remove(client)
            upstream?.let { sockets.remove(it) }
        }
    }

    private fun parseConnect(input: InputStream): String? {
        val bytes = readHeader(input) ?: return null
        val first = String(bytes, Charsets.ISO_8859_1).substringBefore("\r\n")
        val parts = first.split(' ')
        if (parts.size != 3 || parts[0] != "CONNECT" || !parts[2].startsWith("HTTP/")) return null
        val authority = parts[1]
        val colon = authority.lastIndexOf(':')
        if (colon <= 0 || colon != authority.indexOf(':')) return null
        val host = authority.substring(0, colon).lowercase()
        val port = authority.substring(colon + 1).toIntOrNull() ?: return null
        if (port != 443 || host.length > 253 || host.endsWith('.') || !allowedHost(host)) return null
        return host
    }

    private fun allowedHost(host: String): Boolean = when (host) {
        "oauth.accounts.firefox.com", "vpn.mozilla.org", "firefox.settings.services.mozilla.com" -> true
        else -> FoxRelayDiscovery.validUrl("https://$host")
    }

    private fun readHeader(input: InputStream): ByteArray? {
        val data = java.io.ByteArrayOutputStream()
        while (data.size() <= 8192) {
            val value = input.read()
            if (value < 0) return null
            data.write(value)
            val size = data.size()
            if (size >= 4) {
                val raw = data.toByteArray()
                if (raw[size - 4] == 13.toByte() && raw[size - 3] == 10.toByte() &&
                    raw[size - 2] == 13.toByte() && raw[size - 1] == 10.toByte()) return raw
            }
        }
        return null
    }

    private fun connectSocks(socket: Socket, hostname: String) {
        val host = hostname.toByteArray(Charsets.US_ASCII)
        if (host.size !in 1..255) throw java.io.IOException("invalid destination")
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        output.write(byteArrayOf(5, 1, 0))
        output.flush()
        val greeting = ByteArray(2)
        input.readFully(greeting)
        if (greeting[0] != 5.toByte() || greeting[1] != 0.toByte()) throw java.io.IOException("SOCKS authentication failed")
        val request = java.io.ByteArrayOutputStream()
        request.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()))
        request.write(host)
        request.write(443 ushr 8)
        request.write(443 and 255)
        output.write(request.toByteArray())
        output.flush()
        val head = ByteArray(4)
        input.readFully(head)
        if (head[0] != 5.toByte() || head[1] != 0.toByte()) throw java.io.IOException("SOCKS connection failed")
        val remaining = when (head[3].toInt()) {
            1 -> 4
            3 -> input.readUnsignedByte()
            4 -> 16
            else -> throw java.io.IOException("invalid SOCKS response")
        }
        val suffix = ByteArray(remaining + 2)
        input.readFully(suffix)
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            if (count == 0) continue
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    private fun reject(output: OutputStream) {
        try {
            output.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (_: Exception) {
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
