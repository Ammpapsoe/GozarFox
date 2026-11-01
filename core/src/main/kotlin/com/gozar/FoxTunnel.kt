package com.gozar

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class FoxDuplex(
    private val socket: SSLSocket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
    private val h2: Boolean,
    private val streamId: Int,
    initialSendWindow: Long = 65535L,
    initialWindowSetting: Long = 65535L,
    initialConnectionWindow: Long = 65535L
) : FoxPipe {

    private val lock = Object()
    private val outputLock = Any()
    private var sendWindow = initialSendWindow
    private var initialWindow = initialWindowSetting
    private var connectionWindow = initialConnectionWindow
    private var pending = ByteArray(0)
    private var pendingOffset = 0
    private var ended = false
    @Volatile private var closed = false

    internal fun setReadTimeout(timeout: Int) {
        socket.soTimeout = timeout
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!h2) return input.read(buffer, offset, length)
        if (length == 0) return 0
        if (pendingOffset < pending.size) {
            val take = minOf(pending.size - pendingOffset, length)
            System.arraycopy(pending, pendingOffset, buffer, offset, take)
            pendingOffset += take
            if (pendingOffset == pending.size) {
                pending = ByteArray(0)
                pendingOffset = 0
            }
            return take
        }
        if (ended) return -1
        while (true) {
            val header = ByteArray(9)
            if (!fill(header)) return -1
            val size = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or
                (header[2].toInt() and 0xFF)
            val type = header[3].toInt() and 0xFF
            val flags = header[4].toInt() and 0xFF
            val id = ((header[5].toInt() and 0x7F) shl 24) or ((header[6].toInt() and 0xFF) shl 16) or
                ((header[7].toInt() and 0xFF) shl 8) or (header[8].toInt() and 0xFF)
            if (size > 16384) throw java.io.IOException("the proxy sent an oversized HTTP/2 frame")
            val payload = ByteArray(size)
            if (size > 0 && !fill(payload)) return -1
            when (type) {
                0x00 -> if (id == streamId) {
                    if (flags and 0x01 != 0) ended = true
                    var start = 0
                    var end = size
                    if (flags and 0x08 != 0) {
                        if (size == 0) throw java.io.IOException("invalid HTTP/2 padding")
                        start = 1
                        end -= payload[0].toInt() and 0xFF
                        if (end < start) throw java.io.IOException("invalid HTTP/2 padding")
                    }
                    if (size > 0) grow(size)
                    if (end > start) {
                        val take = minOf(end - start, length)
                        System.arraycopy(payload, start, buffer, offset, take)
                        if (start + take < end) {
                            pending = payload.copyOfRange(start, end)
                            pendingOffset = take
                        }
                        return take
                    }
                    if (ended) return -1
                }
                0x01 -> if (id == streamId && flags and 0x01 != 0) {
                    ended = true
                    return -1
                }
                0x03, 0x07 -> return -1
                0x04 -> if (flags and 0x01 == 0) {
                    val changed = h2InitialWindow(payload, initialWindow)
                    synchronized(lock) {
                        sendWindow += changed - initialWindow
                        initialWindow = changed
                        lock.notifyAll()
                    }
                    send(frame(0x04, 0x01, 0, ByteArray(0)))
                }
                0x06 -> if (flags and 0x01 == 0) send(frame(0x06, 0x01, 0, payload))
                0x08 -> {
                    val grow = if (payload.size >= 4) {
                        ((payload[0].toInt() and 0x7F) shl 24) or ((payload[1].toInt() and 0xFF) shl 16) or
                            ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                    } else 0
                    if (grow <= 0) throw java.io.IOException("invalid HTTP/2 window update")
                    synchronized(lock) {
                        if (id == 0) {
                            connectionWindow += grow
                            if (connectionWindow > 0x7fffffffL) throw java.io.IOException("HTTP/2 window overflow")
                        } else if (id == streamId) {
                            sendWindow += grow
                            if (sendWindow > 0x7fffffffL) throw java.io.IOException("HTTP/2 window overflow")
                        }
                        lock.notifyAll()
                    }
                }
            }
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (!h2) {
            output.write(buffer, offset, length)
            output.flush()
            return
        }
        var sent = 0
        while (sent < length) {
            val size = reserveWindow(minOf(length - sent, 16384))
            send(frame(0x00, 0, streamId, buffer.copyOfRange(offset + sent, offset + sent + size)))
            sent += size
        }
    }

    private fun reserveWindow(maximum: Int): Int {
        val deadline = System.currentTimeMillis() + 25000
        synchronized(lock) {
            while ((sendWindow <= 0 || connectionWindow <= 0) && !closed) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw java.io.IOException("upload stalled")
                lock.wait(left)
            }
            if (closed) throw java.io.IOException("the tunnel is closed")
            val size = minOf(maximum.toLong(), sendWindow, connectionWindow).toInt()
            sendWindow -= size
            connectionWindow -= size
            return size
        }
    }

    private fun grow(size: Int) {
        val update = byteArrayOf(
            ((size ushr 24) and 0x7F).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte()
        )
        send(frame(0x08, 0, 0, update))
        send(frame(0x08, 0, streamId, update))
    }

    override fun finishWrite() {
        if (closed) return
        if (h2) send(frame(0x00, 0x01, streamId, ByteArray(0))) else socket.shutdownOutput()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            lock.notifyAll()
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun send(bytes: ByteArray) {
        synchronized(outputLock) {
            output.write(bytes)
            output.flush()
        }
    }

    private fun fill(buffer: ByteArray): Boolean {
        var done = 0
        while (done < buffer.size) {
            val n = try {
                input.read(buffer, done, buffer.size - done)
            } catch (e: Exception) {
                return false
            }
            if (n < 0) return false
            done += n
        }
        return true
    }

    private fun frame(type: Int, flags: Int, id: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(9 + payload.size)
        out[0] = ((payload.size ushr 16) and 0xFF).toByte()
        out[1] = ((payload.size ushr 8) and 0xFF).toByte()
        out[2] = (payload.size and 0xFF).toByte()
        out[3] = type.toByte()
        out[4] = flags.toByte()
        out[5] = ((id ushr 24) and 0x7F).toByte()
        out[6] = ((id ushr 16) and 0xFF).toByte()
        out[7] = ((id ushr 8) and 0xFF).toByte()
        out[8] = (id and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 9, payload.size)
        return out
    }
}

private fun h2InitialWindow(payload: ByteArray, current: Long): Long {
    if (payload.size % 6 != 0) throw java.io.IOException("invalid HTTP/2 settings")
    var window = current
    for (index in payload.indices step 6) {
        val id = ((payload[index].toInt() and 255) shl 8) or (payload[index + 1].toInt() and 255)
        if (id == 4) {
            val value = ((payload[index + 2].toLong() and 255) shl 24) or
                ((payload[index + 3].toLong() and 255) shl 16) or
                ((payload[index + 4].toLong() and 255) shl 8) or (payload[index + 5].toLong() and 255)
            if (value > 0x7fffffffL) throw java.io.IOException("invalid HTTP/2 initial window")
            window = value
        }
    }
    return window
}

class FoxProxyRefusal(val status: Int, message: String) : java.io.IOException(message)

object FoxTunnel {

    private const val AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"

    private val adaptive = FoxAdaptive()

    fun resetTransport() = adaptive.clear()

    fun open(location: FoxLocation, host: String, port: Int, pass: String, timeout: Int = 15000): FoxDuplex {
        val key = location.host + ":" + location.port + "|" + FoxUpstream.text()
        var firstError: Exception? = null
        for (h2 in adaptive.order(key)) {
            val started = System.nanoTime()
            try {
                val result = if (h2) openH2(location, host, port, pass, timeout) else openH1(location, host, port, pass, timeout)
                adaptive.success(key, h2, (System.nanoTime() - started) / 1_000_000L)
                return result
            } catch (e: Exception) {
                if (isCertificateFailure(e) || e is FoxError ||
                    e is FoxProxyRefusal && e.status in setOf(401, 403, 407, 429, 451)) throw e
                adaptive.failed(key, h2)
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: java.io.IOException("the proxy did not answer")
    }

    private fun isCertificateFailure(error: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is javax.net.ssl.SSLPeerUnverifiedException ||
                current is java.security.cert.CertificateException ||
                current is java.security.cert.CertPathValidatorException) return true
            current = current.cause
        }
        return false
    }

    fun isTlsFailure(error: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is javax.net.ssl.SSLException ||
                current is java.security.cert.CertificateException ||
                current is java.security.cert.CertPathValidatorException) return true
            current = current.cause
        }
        return false
    }

    private fun authority(host: String, port: Int) = "$host:$port"

    private fun openH1(location: FoxLocation, host: String, port: Int, pass: String, timeout: Int): FoxDuplex {
        val socket = connect(location, arrayOf("http/1.1"), timeout)
        try {
            val target = authority(host, port)
            val output = BufferedOutputStream(socket.outputStream)
            val request = "CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Authorization: Bearer $pass\r\n" +
                "User-Agent: $AGENT\r\n\r\n"
            output.write(request.toByteArray(Charsets.UTF_8))
            output.flush()
            val input = BufferedInputStream(socket.inputStream)
            val (status, headers) = response(input)
            if (status == 200) {
                socket.soTimeout = 0
                return FoxDuplex(socket, input, output, false, 1)
            }
            socket.close()
            throw if (status == 0) java.io.IOException(reason(status, headers)) else FoxProxyRefusal(status, reason(status, headers))
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun openH2(location: FoxLocation, host: String, port: Int, pass: String, timeout: Int): FoxDuplex {
        val socket = connect(location, arrayOf("h2"), timeout)
        try {
            val output = BufferedOutputStream(socket.outputStream)
            val target = authority(host, port)
            output.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(frame(0x04, 0, 0, settings()))
            val block = ByteArrayOutputStream()
            block.write(pair(":method", "CONNECT"))
            block.write(pair(":authority", target))
            block.write(pair("proxy-authorization", "Bearer $pass"))
            block.write(pair("user-agent", AGENT))
            output.write(frame(0x01, 0x04, 1, block.toByteArray()))
            output.flush()

            val input = BufferedInputStream(socket.inputStream)
            val deadline = System.currentTimeMillis() + timeout
            var initialWindow = 65535L
            var streamWindow = 65535L
            var connectionWindow = 65535L
            var fragments: ByteArrayOutputStream? = null
            var ended = false
            while (System.currentTimeMillis() < deadline) {
                val header = ByteArray(9)
                if (!readFully(input, header)) throw java.io.IOException("the proxy closed the connection")
                val size = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or
                    (header[2].toInt() and 0xFF)
                val type = header[3].toInt() and 0xFF
                val flags = header[4].toInt() and 0xFF
                val id = ((header[5].toInt() and 0x7F) shl 24) or ((header[6].toInt() and 0xFF) shl 16) or
                    ((header[7].toInt() and 0xFF) shl 8) or (header[8].toInt() and 0xFF)
                if (size > 16384) throw java.io.IOException("the proxy sent an oversized HTTP/2 frame")
                val payload = ByteArray(size)
                if (size > 0 && !readFully(input, payload)) throw java.io.IOException("the proxy closed the connection")
                if (fragments != null && (type != 0x09 || id != 1)) throw java.io.IOException("invalid HTTP/2 continuation")
                var complete: ByteArray? = null
                when (type) {
                    0x04 -> if (flags and 0x01 == 0) {
                        val changed = h2InitialWindow(payload, initialWindow)
                        streamWindow += changed - initialWindow
                        initialWindow = changed
                        output.write(frame(0x04, 0x01, 0, ByteArray(0)))
                        output.flush()
                    }
                    0x06 -> if (flags and 0x01 == 0) {
                        output.write(frame(0x06, 0x01, 0, payload))
                        output.flush()
                    }
                    0x01 -> {
                        if (id != 1) continue
                        var from = 0
                        var to = payload.size
                        if (flags and 0x08 != 0) {
                            if (payload.isEmpty()) throw java.io.IOException("invalid HTTP/2 header padding")
                            from++
                            to -= payload[0].toInt() and 255
                        }
                        if (flags and 0x20 != 0) from += 5
                        if (from > to) throw java.io.IOException("invalid HTTP/2 headers")
                        fragments = ByteArrayOutputStream().apply { write(payload, from, to - from) }
                        ended = flags and 0x01 != 0
                        if (flags and 0x04 != 0) { complete = fragments.toByteArray(); fragments = null }
                    }
                    0x09 -> {
                        val block = fragments ?: throw java.io.IOException("unexpected HTTP/2 continuation")
                        if (block.size() + payload.size > 65536) throw java.io.IOException("HTTP/2 headers too large")
                        block.write(payload)
                        if (flags and 0x04 != 0) { complete = block.toByteArray(); fragments = null }
                    }
                    0x08 -> {
                        if (payload.size != 4) throw java.io.IOException("invalid HTTP/2 window update")
                        val increase = ((payload[0].toLong() and 0x7f) shl 24) or
                            ((payload[1].toLong() and 255) shl 16) or ((payload[2].toLong() and 255) shl 8) or
                            (payload[3].toLong() and 255)
                        if (increase == 0L) throw java.io.IOException("invalid HTTP/2 window update")
                        if (id == 0) connectionWindow += increase else if (id == 1) streamWindow += increase
                        if (connectionWindow > 0x7fffffffL || streamWindow > 0x7fffffffL) throw java.io.IOException("HTTP/2 window overflow")
                    }
                    0x03 -> throw java.io.IOException("the proxy reset the tunnel (${code(payload)})")
                    0x07 -> throw java.io.IOException("the proxy closed the connection (${code(payload)})")
                }
                if (complete != null) {
                    val reply = status(complete) ?: throw java.io.IOException("the proxy did not answer with a status")
                    if (reply in 100..199) continue
                    if (reply in 200..299 && !ended) {
                        socket.soTimeout = 0
                        return FoxDuplex(socket, input, output, true, 1, streamWindow, initialWindow, connectionWindow)
                    }
                    if (ended && reply in 200..299) throw java.io.IOException("the proxy ended the tunnel")
                    throw FoxProxyRefusal(reply, reason(reply, mapOf()))
                }
            }
            socket.close()
            throw java.io.IOException("the proxy did not answer in time")
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    fun verify(location: FoxLocation, pass: String) {
        RelayCheck.verify { target -> open(location, target, 443, pass, 8000) }
    }

    fun passRejected(error: Exception): Boolean = RelayCheck.passRejected(error)

    fun probe(location: FoxLocation, pass: String): String {
        val tunnel = open(location, "ifconfig.me", 80, pass, 8000)
        try {
            tunnel.setReadTimeout(8000)
            val request = "GET /ip HTTP/1.1\r\nHost: ifconfig.me\r\nUser-Agent: curl/8\r\nAccept: */*\r\nConnection: close\r\n\r\n"
            tunnel.write(request.toByteArray(Charsets.US_ASCII), 0, request.length)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                val n = try {
                    tunnel.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    break
                }
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > 4096) break
            }
            val text = out.toString("UTF-8")
            val body = text.substringAfter("\r\n\r\n", "").trim()
            return body.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(64) ?: ""
        } finally {
            tunnel.close()
        }
    }

    private fun connect(location: FoxLocation, protocols: Array<String>, timeout: Int): SSLSocket {
        val raw = chain(location.host, location.port, timeout)
        var tls: SSLSocket? = null
        try {
            raw.soTimeout = timeout
            tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, location.host, location.port, true) as SSLSocket
            val parameters = tls.sslParameters
            parameters.serverNames = listOf(javax.net.ssl.SNIHostName(location.host))
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            try {
                parameters.applicationProtocols = protocols
            } catch (_: NoSuchMethodError) {
                if ("h2" in protocols) throw java.io.IOException("HTTP/2 negotiation is unavailable")
            }
            tls.sslParameters = parameters
            tls.startHandshake()
            if ("h2" in protocols) {
                val selected = try { tls.applicationProtocol } catch (_: NoSuchMethodError) { "" }
                if (selected != "h2") throw java.io.IOException("the proxy did not negotiate HTTP/2")
            }
            return tls
        } catch (e: Exception) {
            try {
                tls?.close()
            } catch (_: Exception) {
            }
            try {
                raw.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun chain(host: String, port: Int, timeout: Int): Socket {
        val hops = FoxUpstream.hops
        val raw = Socket()
        val first = if (hops.isEmpty()) host to port else hops.first().host to hops.first().port
        raw.connect(InetSocketAddress(first.first, first.second), timeout)
        raw.soTimeout = timeout
        if (hops.isEmpty()) return raw
        try {
            for (index in 1 until hops.size) {
                val hop = hops[index]
                hopTo(raw, hops[index - 1].type, hop.host, hop.port)
            }
            hopTo(raw, hops.last().type, host, port)
            return raw
        } catch (e: Exception) {
            try {
                raw.close()
            } catch (ignored: Exception) {
            }
            throw if (e is FoxError) e else FoxError("The proxy chain did not answer: " + (e.message ?: "no reply"))
        }
    }

    private fun hopTo(socket: Socket, type: String, host: String, port: Int) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        if (type == "http") {
            val target = "$host:$port"
            val request = "CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Connection: keep-alive\r\n\r\n"
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val c = input.read()
                if (c < 0) throw FoxError("The proxy closed the connection")
                head.append(c.toChar())
                if (head.length > 8192) throw FoxError("The proxy sent a very long reply")
            }
            val status = head.lineSequence().firstOrNull()
                ?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
            if (status != 200) throw FoxError("The proxy refused the chain ($status)")
            return
        }
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val reply = ByteArray(2)
        if (!fill(input, reply)) throw FoxError("The proxy did not answer")
        if (reply[0].toInt() != 0x05 || reply[1].toInt() != 0x00) throw FoxError("The proxy wants authentication")
        val name = host.toByteArray(Charsets.UTF_8)
        val request = ByteArrayOutputStream()
        request.write(byteArrayOf(0x05, 0x01, 0x00))
        if (name.size <= 255 && !isAddress(host)) {
            request.write(byteArrayOf(0x03, name.size.toByte()))
            request.write(name)
        } else {
            val address = InetAddress.getByName(host).address
            request.write(if (address.size == 16) 0x04 else 0x01)
            request.write(address)
        }
        request.write(byteArrayOf(((port ushr 8) and 0xFF).toByte(), (port and 0xFF).toByte()))
        output.write(request.toByteArray())
        output.flush()
        val answer = ByteArray(4)
        if (!fill(input, answer)) throw FoxError("The proxy did not answer")
        if (answer[1].toInt() != 0x00) throw FoxError("The proxy refused the chain (" + answer[1] + ")")
        val skip = when (answer[3].toInt()) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> {
                val size = input.read()
                if (size < 0) throw FoxError("The proxy did not answer")
                size
            }
            else -> throw FoxError("The proxy sent an unknown reply")
        }
        val tail = ByteArray(skip + 2)
        if (!fill(input, tail)) throw FoxError("The proxy did not answer")
    }

    private fun isAddress(host: String): Boolean =
        host.isNotEmpty() && host.all { it.isDigit() || it == '.' } || host.contains(':')

    private fun fill(input: BufferedInputStream, buffer: ByteArray): Boolean {
        var done = 0
        while (done < buffer.size) {
            val size = input.read(buffer, done, buffer.size - done)
            if (size < 0) return false
            done += size
        }
        return true
    }

    private fun settings(): ByteArray {
        fun entry(id: Int, value: Int): ByteArray = byteArrayOf(
            ((id ushr 8) and 0xFF).toByte(), (id and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(), ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte()
        )
        val out = ByteArrayOutputStream()
        listOf(entry(1, 4096), entry(2, 0), entry(4, 65535), entry(5, 16384), entry(8, 0), entry(3, 100), entry(6, 65536))
            .forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun pair(name: String, value: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00)
        literal(out, name)
        literal(out, value)
        return out.toByteArray()
    }

    private fun literal(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        var remaining = bytes.size
        if (remaining < 127) {
            out.write(remaining)
        } else {
            out.write(127)
            remaining -= 127
            while (remaining >= 128) {
                out.write((remaining and 0x7F) or 0x80)
                remaining = remaining ushr 7
            }
            out.write(remaining)
        }
        out.write(bytes)
    }

    private fun frame(type: Int, flags: Int, id: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(9 + payload.size)
        out[0] = ((payload.size ushr 16) and 0xFF).toByte()
        out[1] = ((payload.size ushr 8) and 0xFF).toByte()
        out[2] = (payload.size and 0xFF).toByte()
        out[3] = type.toByte()
        out[4] = flags.toByte()
        out[5] = ((id ushr 24) and 0x7F).toByte()
        out[6] = ((id ushr 16) and 0xFF).toByte()
        out[7] = ((id ushr 8) and 0xFF).toByte()
        out[8] = (id and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 9, payload.size)
        return out
    }

    private fun code(payload: ByteArray): Int {
        if (payload.size < 4) return 0
        return ((payload[0].toInt() and 0xFF) shl 24) or ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
    }

    private fun hpackNumber(block: ByteArray, offset: Int, prefix: Int): Pair<Int, Int>? {
        if (offset >= block.size) return null
        val mask = (1 shl prefix) - 1
        var value = block[offset].toInt() and mask
        var index = offset + 1
        if (value < mask) return value to index
        var shift = 0
        while (index < block.size && shift <= 28) {
            val next = block[index++].toInt() and 255
            val added = (next and 127).toLong() shl shift
            if (value.toLong() + added > Int.MAX_VALUE) return null
            value += added.toInt()
            if (next and 128 == 0) return value to index
            shift += 7
        }
        return null
    }

    private data class HpackString(val bytes: ByteArray, val huffman: Boolean, val next: Int)

    private fun hpackString(block: ByteArray, offset: Int): HpackString? {
        if (offset >= block.size) return null
        val huffman = block[offset].toInt() and 128 != 0
        val (length, start) = hpackNumber(block, offset, 7) ?: return null
        if (length > block.size - start) return null
        return HpackString(block.copyOfRange(start, start + length), huffman, start + length)
    }

    private fun encodedStatus(value: Int): ByteArray {
        var bits = 0
        var length = 0
        for (digit in value.toString().map { it - '0' }) {
            val count = if (digit < 3) 5 else 6
            val code = if (digit < 3) digit else digit + 22
            bits = (bits shl count) or code
            length += count
        }
        val padding = (8 - length % 8) % 8
        bits = (bits shl padding) or ((1 shl padding) - 1)
        length += padding
        return ByteArray(length / 8) { index -> (bits ushr (length - 8 * (index + 1))).toByte() }
    }

    private fun status(block: ByteArray): Int? {
        var at = 0
        while (at < block.size) {
            val first = block[at].toInt() and 255
            if (first and 0xe0 == 0x20) {
                val (size, next) = hpackNumber(block, at, 5) ?: return null
                if (size > 4096) return null
                at = next
                continue
            }
            if (first and 0x80 != 0) {
                val (index, _) = hpackNumber(block, at, 7) ?: return null
                return when (index) { 8 -> 200; 9 -> 204; 10 -> 206; 11 -> 304; 12 -> 400; 13 -> 404; 14 -> 500; else -> null }
            }
            val prefix = if (first and 0xc0 == 0x40) 6 else 4
            val (nameIndex, next) = hpackNumber(block, at, prefix) ?: return null
            at = next
            val statusName = if (nameIndex == 0) {
                val name = hpackString(block, at) ?: return null
                at = name.next
                if (name.huffman) name.bytes.contentEquals(byteArrayOf(0xb8.toByte(), 0x84.toByte(), 0x8d.toByte(), 0x36, 0xa3.toByte()))
                else String(name.bytes, Charsets.US_ASCII) == ":status"
            } else nameIndex in 8..14
            if (!statusName) return null
            val value = hpackString(block, at) ?: return null
            if (!value.huffman) return String(value.bytes, Charsets.US_ASCII).toIntOrNull()?.takeIf { it in 100..599 }
            return (100..599).firstOrNull { encodedStatus(it).contentEquals(value.bytes) }
        }
        return null
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray): Boolean {
        var done = 0
        while (done < buffer.size) {
            val n = input.read(buffer, done, buffer.size - done)
            if (n < 0) return false
            done += n
        }
        return true
    }

    private fun response(input: BufferedInputStream): Pair<Int, Map<String, String>> {
        val head = StringBuilder()
        var last = 0
        while (head.length < 8192) {
            val c = input.read()
            if (c < 0) break
            head.append(c.toChar())
            if (last == 13 && c == 10 && head.endsWith("\r\n\r\n")) break
            last = c
        }
        val lines = head.toString().split("\r\n").filter { it.isNotEmpty() }
        val status = lines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return status to headers
    }

    private fun reason(status: Int?, headers: Map<String, String>): String {
        val failure = headers["x-fastly-proxy-auth-failed"]
        return when {
            status == null -> "the proxy did not answer with a status"
            status == 407 || status == 401 -> "the proxy pass was refused"
            status == 403 -> "this account has no Firefox VPN access"
            status == 429 -> "the monthly allowance is used up"
            status == 451 -> "Mozilla does not serve this region"
            failure != null -> "the pass was rejected ($failure)"
            status == 400 -> "the pass was rejected (400)"
            status == 0 -> "the proxy closed the connection"
            else -> "the proxy answered $status"
        }
    }
}
