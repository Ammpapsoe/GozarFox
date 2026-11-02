package com.gozar

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

class FoxProxy(
    private val port: Int,
    private val forceTunnel: Boolean = false,
    private val countTraffic: Boolean = true,
    private val directAllowed: () -> Boolean = { FoxVpnService.directRoutingAllowed() },
    private val directAddress: (String) -> String? = { FoxVpnService.directAddress(it) },
    private val pass: () -> String?
) {

    private val credential = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .let { raw -> String(android.util.Base64.encode(raw, android.util.Base64.NO_WRAP)) }
    private val slots = Semaphore(MAX_CLIENTS)
    private var active: Int = port

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun boundPort(): Int = active
    private val connections = Any()
    private val clients = mutableSetOf<Socket>()
    private val pipes = mutableSetOf<FoxPipe>()

    fun start(): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
            server = socket
            active = socket.localPort
            running = true
            thread(isDaemon = true, name = "fox-proxy") { acceptLoop(socket) }
            true
        } catch (e: Exception) {
            FoxLink.fault(e.message ?: "the local proxy did not start")
            false
        }
    }

    fun stop() {
        val openClients: List<Socket>
        val openPipes: List<FoxPipe>
        synchronized(connections) {
            running = false
            try {
                server?.close()
            } catch (_: Exception) {
            }
            server = null
            openClients = clients.toList()
            openPipes = pipes.toList()
            clients.clear()
            pipes.clear()
        }
        for (client in openClients) try { client.close() } catch (_: Exception) { }
        for (pipe in openPipes) try { pipe.close() } catch (_: Exception) { }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            if (!client.inetAddress.isLoopbackAddress || !slots.tryAcquire()) {
                try { client.close() } catch (_: Exception) { }
                continue
            }
            val accepted = synchronized(connections) {
                if (!running) false else clients.add(client)
            }
            if (!accepted) {
                slots.release()
                try { client.close() } catch (_: Exception) { }
                continue
            }
            thread(isDaemon = true) { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            input.mark(1)
            val first = input.read()
            input.reset()
            if (first == 0x05) socks(input, output) else web(input, output)
        } catch (_: Exception) {
        } finally {
            slots.release()
            synchronized(connections) { clients.remove(client) }
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun socks(input: InputStream, output: BufferedOutputStream) {
        if (input.read() != 0x05) return
        val count = input.read()
        if (count <= 0) return
        val methods = ByteArray(count)
        if (readFull(input, methods) < 0) return
        if (!methods.any { it.toInt() == 0x02 }) {
            output.write(byteArrayOf(0x05, 0xFF.toByte()))
            output.flush()
            return
        }
        output.write(byteArrayOf(0x05, 0x02))
        output.flush()
        if (!login(input, output)) return
        val head = ByteArray(4)
        if (readFull(input, head) < 0 || head[0].toInt() != 0x05 || head[2].toInt() != 0x00) return
        if (head[1].toInt() != 0x01) {
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }
        val host = when (head[3].toInt()) {
            0x01 -> {
                val raw = ByteArray(4)
                if (readFull(input, raw) < 0) return
                InetAddress.getByAddress(raw).hostAddress ?: return
            }
            0x03 -> {
                val length = input.read()
                if (length <= 0) return
                val raw = ByteArray(length)
                if (readFull(input, raw) < 0) return
                String(raw, Charsets.UTF_8)
            }
            0x04 -> {
                val raw = ByteArray(16)
                if (readFull(input, raw) < 0) return
                InetAddress.getByAddress(raw).hostAddress ?: return
            }
            else -> return
        }
        val portBytes = ByteArray(2)
        if (readFull(input, portBytes) < 0) return
        val target = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        val tunnel = open(host, target)
        if (tunnel == null) {
            output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
        pump(input, output, tunnel)
    }

    private fun login(input: InputStream, output: BufferedOutputStream): Boolean {
        if (input.read() != 0x01) return false
        val user = ByteArray(input.read().coerceAtLeast(0)).also { if (readFull(input, it) < 0) return false }
        val length = input.read()
        if (length < 0) return false
        val word = ByteArray(length).also { if (readFull(input, it) < 0) return false }
        val expected = credential.toByteArray(Charsets.UTF_8)
        val ok = MessageDigest.isEqual(user, expected) && MessageDigest.isEqual(word, expected)
        output.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
        output.flush()
        return ok
    }

    private fun web(input: InputStream, output: BufferedOutputStream) {
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val first = lines.firstOrNull()?.split(" ") ?: return
        if (first.size < 3) return
        val method = first[0]
        val target = first[1]
        val version = first[2]
        if (method.equals("CONNECT", true)) {
            val colon = target.lastIndexOf(':')
            if (colon <= 0) return
            val host = target.substring(0, colon).trim('[', ']')
            val port = target.substring(colon + 1).toIntOrNull() ?: 443
            val tunnel = open(host, port)
            if (tunnel == null) {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                output.flush()
                return
            }
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            output.flush()
            pump(input, output, tunnel)
            return
        }
        val absolute = target.startsWith("http://") || target.startsWith("https://")
        if (!absolute) {
            output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
            output.flush()
            return
        }
        val url = try { java.net.URL(target) } catch (e: Exception) { return }
        val secure = url.protocol == "https"
        val host = url.host
        val port = if (url.port > 0) url.port else if (secure) 443 else 80
        if (secure) {
            output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
            output.flush()
            return
        }
        val tunnel = open(host, port) ?: return
        val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
        val rebuilt = StringBuilder("$method $path $version\r\n")
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val name = line.substringBefore(':').trim()
            if (name.equals("Proxy-Connection", true)) continue
            if (name.equals("Connection", true)) continue
            if (name.equals("Proxy-Authorization", true)) continue
            rebuilt.append(line).append("\r\n")
        }
        rebuilt.append("Connection: close\r\n\r\n")
        val raw = rebuilt.toString().toByteArray(Charsets.UTF_8)
        tunnel.write(raw, 0, raw.size)
        pump(input, output, tunnel)
    }

    private fun open(host: String, port: Int): FoxPipe? {
        val tunnel = if (!forceTunnel && directAllowed() && FoxRoute.direct(host)) {
            val address = directAddress(host) ?: return null
            if (!directAllowed()) return null
            FoxSocketPipe.connect(address, port)?.let { DirectPipe(it, directAllowed) }
        } else {
            val access = pass() ?: return null
            val location = FoxLink.location ?: return null
            try {
                FoxTunnel.open(location, host, port, access)
            } catch (e: Exception) {
                if (!forceTunnel) FoxLink.fault(e.message ?: "tunnel failed")
                null
            }
        } ?: return null
        val accepted = synchronized(connections) {
            if (!running) false else pipes.add(tunnel)
        }
        if (!accepted) {
            try { tunnel.close() } catch (_: Exception) { }
            return null
        }
        return tunnel
    }

    private fun pump(input: InputStream, output: BufferedOutputStream, tunnel: FoxPipe) {
        val up = thread(isDaemon = true) {
            val buffer = ByteArray(16384)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    tunnel.write(buffer, 0, n)
                    if (countTraffic) FoxLink.count(false, n.toLong())
                }
                tunnel.finishWrite()
            } catch (_: Exception) {
                try { tunnel.close() } catch (_: Exception) { }
            }
        }
        val buffer = ByteArray(16384)
        try {
            while (true) {
                val n = tunnel.read(buffer, 0, buffer.size)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
                if (countTraffic) FoxLink.count(true, n.toLong())
            }
        } catch (_: Exception) {
        }
        try {
            up.interrupt()
        } catch (_: Exception) {
        }
        try { tunnel.close() } catch (_: Exception) { }
        synchronized(connections) { pipes.remove(tunnel) }
    }

    private fun readHead(input: InputStream): String? {
        val builder = StringBuilder()
        var last = 0
        while (builder.length < 16384) {
            val c = input.read()
            if (c < 0) return if (builder.isEmpty()) null else builder.toString()
            builder.append(c.toChar())
            if (last == '\r'.code && c == '\n'.code) {
                val text = builder.toString()
                if (text.endsWith("\r\n\r\n")) return text
            }
            last = c
        }
        return null
    }

    companion object {
        private const val MAX_CLIENTS = 64
    }

    private fun readFull(input: InputStream, buffer: ByteArray): Int {
        var done = 0
        while (done < buffer.size) {
            val n = input.read(buffer, done, buffer.size - done)
            if (n < 0) return -1
            done += n
        }
        return done
    }

}
