package com.gozar

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * One bidirectional tunnel to the proxy.
 *
 * On HTTP/1.1 the duplex owns its socket, as before. On HTTP/2 the duplex is
 * one stream on a shared [FoxTunnel.H2Session], so a page load of a hundred
 * connections costs one or two handshakes instead of a hundred. Small writes
 * are coalesced for a few milliseconds so a chatty application does not turn
 * every few bytes into a TLS record of its own.
 */
class FoxDuplex private constructor(
    private val link: Link
) : FoxPipe {

    private interface Link {
        fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
        fun sendBlocking(bytes: ByteArray)
        fun sendIfPossible(bytes: ByteArray): ByteArray
        fun halfClose()
        fun teardown()
        fun setTimeout(timeout: Int)
    }

    private class H1(
        val socket: SSLSocket,
        val input: BufferedInputStream,
        val output: BufferedOutputStream
    ) : Link {
        override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
            input.read(buffer, offset, length)

        override fun sendBlocking(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        override fun sendIfPossible(bytes: ByteArray): ByteArray {
            sendBlocking(bytes)
            return ByteArray(0)
        }

        override fun halfClose() = socket.shutdownOutput()
        override fun teardown() = socket.close()
        override fun setTimeout(timeout: Int) {
            socket.soTimeout = timeout
        }
    }

    internal class H2(private val session: FoxTunnel.H2Session, val id: Int) : Link {
        private val lock = Object()
        private val inbox = ArrayDeque<ByteArray>()
        private var head = ByteArray(0)
        private var headOffset = 0
        private var ended = false
        private var failure: Exception? = null
        private var open = false

        @Volatile
        private var readTimeoutMs: Int = FoxTunnel.READ_TIMEOUT_MS

        fun timeout(): Int = readTimeoutMs

        fun deliver(payload: ByteArray) = synchronized(lock) {
            inbox.addLast(payload)
            lock.notifyAll()
        }

        fun end() = synchronized(lock) {
            ended = true
            lock.notifyAll()
        }

        fun fail(error: Exception) = synchronized(lock) {
            // Keep the first failure: a stream refused with a 407 must not be
            // overwritten by the session-level EOF error that follows it, or
            // callers would retry a proxy that definitively refused.
            if (failure == null) failure = error
            ended = true
            lock.notifyAll()
        }

        fun markOpen() = synchronized(lock) {
            open = true
            lock.notifyAll()
        }

        fun awaitOpen(timeoutMs: Int): Boolean = synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!open && failure == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left)
            }
            failure == null
        }

        fun refusal(): Exception? = synchronized(lock) { failure }

        override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            val deadline = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else 0L
            synchronized(lock) {
                while (true) {
                    if (headOffset < head.size) {
                        val take = minOf(head.size - headOffset, length)
                        System.arraycopy(head, headOffset, buffer, offset, take)
                        headOffset += take
                        if (headOffset == head.size) {
                            head = ByteArray(0)
                            headOffset = 0
                        }
                        return take
                    }
                    if (inbox.isNotEmpty()) {
                        head = inbox.removeFirst()
                        headOffset = 0
                        continue
                    }
                    // Report the end only once every delivered byte has been
                    // consumed; a reset or GOAWAY ends the stream like the
                    // original implementation's EOF, without throwing.
                    if (ended) return -1
                    val left = if (deadline == 0L) 0L else deadline - System.currentTimeMillis()
                    if (deadline != 0L && left <= 0) {
                        throw java.net.SocketTimeoutException("the tunnel read timed out")
                    }
                    lock.wait(if (deadline == 0L) 0L else left)
                }
            }
        }

        override fun sendBlocking(bytes: ByteArray) {
            session.sendData(id, bytes, blocking = true)
        }

        override fun sendIfPossible(bytes: ByteArray): ByteArray = session.sendData(id, bytes, blocking = false)

        override fun halfClose() {
            session.sendFrame(FoxTunnel.frame(0x00, 0x01, id, ByteArray(0)))
        }

        override fun teardown() {
            try {
                session.sendFrame(FoxTunnel.frame(0x03, 0, id, byteArrayOf(0, 0, 0, 8)))
            } catch (_: Exception) {
            }
            session.removeStream(id)
        }

        override fun setTimeout(timeout: Int) {
            readTimeoutMs = timeout
        }
    }

    private val lock = Any()
    private val outputLock = Any()
    private val outBuf = ByteArrayOutputStream()
    private var remainder = ByteArray(0)
    private var flushScheduled = false
    @Volatile private var closed = false

    internal constructor(socket: SSLSocket, input: BufferedInputStream, output: BufferedOutputStream) :
        this(H1(socket, input, output))

    internal constructor(stream: H2) : this(stream as Link)

    internal fun setReadTimeout(timeout: Int) = link.setTimeout(timeout)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val timeout = (link as? H2)?.timeout() ?: 0
        flushQuietly()
        return link.read(buffer, offset, length, timeout)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val ready: ByteArray? = synchronized(outputLock) {
            // Checked under the lock so a write can never slip bytes in after
            // close() has already drained the buffer.
            if (closed) throw java.io.IOException("the tunnel is closed")
            outBuf.write(buffer, offset, length)
            if (outBuf.size() >= COALESCE_BYTES) {
                take()
            } else {
                if (!flushScheduled) {
                    flushScheduled = true
                    FLUSHER.schedule({ flushQuietly() }, COALESCE_DELAY_MS, TimeUnit.MILLISECONDS)
                }
                null
            }
        }
        if (ready != null) link.sendBlocking(ready)
    }

    override fun finishWrite() {
        if (closed) return
        val ready = synchronized(outputLock) {
            if (outBuf.size() > 0 || remainder.isNotEmpty()) take() else ByteArray(0)
        }
        if (ready.isNotEmpty()) link.sendBlocking(ready)
        link.halfClose()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        flushQuietly()
        try {
            link.teardown()
        } catch (_: Exception) {
        }
    }

    /** Runs the buffered bytes out; on a flow-control stall the rest is kept and retried. */
    private fun flushQuietly() {
        val bytes = synchronized(outputLock) {
            flushScheduled = false
            if (outBuf.size() == 0 && remainder.isEmpty()) ByteArray(0) else take()
        }
        if (bytes.isEmpty()) return
        val left = try {
            link.sendIfPossible(bytes)
        } catch (_: Exception) {
            return
        }
        if (left.isNotEmpty()) {
            synchronized(outputLock) {
                remainder = left + remainder
                if (!flushScheduled) {
                    flushScheduled = true
                    FLUSHER.schedule({ flushQuietly() }, STALL_RETRY_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun take(): ByteArray {
        val bytes = if (remainder.isEmpty()) outBuf.toByteArray() else remainder + outBuf.toByteArray()
        outBuf.reset()
        remainder = ByteArray(0)
        return bytes
    }

    companion object {
        private const val COALESCE_BYTES = 1360
        private const val COALESCE_DELAY_MS = 5L
        private const val STALL_RETRY_MS = 10L
        private val FLUSHER = Executors.newScheduledThreadPool(4) { task ->
            Thread(task, "fox-flush").apply { isDaemon = true }
        }
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

    internal const val READ_TIMEOUT_MS = 15000
    private const val AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"
    private const val MAX_SESSIONS = 2
    private const val MAX_STREAMS = 16
    private const val SESSION_IDLE_MS = 60_000L
    private const val UPLOAD_STALL_MS = 25_000L

    private class TransportReset : java.io.IOException("transport reset during session creation")

    private val adaptive = FoxAdaptive()
    private val sessions = HashMap<String, MutableList<H2Session>>()
    // Handshakes run outside the pool lock. A transport reset invalidates
    // in-flight handshakes so they cannot reinsert an obsolete session.
    private var sessionGeneration = 0L

    fun resetTransport() {
        adaptive.clear()
        synchronized(sessions) {
            sessionGeneration += 1
            for (list in sessions.values) for (session in list) session.shutdown()
            sessions.clear()
        }
    }

    internal fun pooledSessionCount(): Int = synchronized(sessions) { sessions.values.sumOf { it.size } }

    fun open(location: FoxLocation, host: String, port: Int, pass: String, timeout: Int = 15000): FoxDuplex {
        val key = location.host + ":" + location.port + "|" + FoxUpstream.text()
        var firstError: Exception? = null
        for (h2 in adaptive.order(key)) {
            val started = System.nanoTime()
            try {
                val result = if (h2) {
                    acquireSession(location, key, timeout).openStream(host, port, pass, timeout)
                } else {
                    openH1(location, host, port, pass, timeout)
                }
                adaptive.success(key, h2, (System.nanoTime() - started) / 1_000_000L)
                return result
            } catch (e: Exception) {
                if (e is TransportReset || isCertificateFailure(e) || e is FoxError ||
                    e is FoxProxyRefusal && e.status in setOf(401, 403, 407, 429, 451)
                ) throw e
                adaptive.failed(key, h2)
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: java.io.IOException("the proxy did not answer")
    }

    private fun acquireSession(location: FoxLocation, key: String, timeout: Int): H2Session {
        val generation = synchronized(sessions) {
            val list = sessions.getOrPut(key) { mutableListOf() }
            val now = System.currentTimeMillis()
            list.removeAll { session ->
                val gone = !session.alive() || (session.activeCount() == 0 && now - session.lastUse() > SESSION_IDLE_MS)
                if (gone) session.shutdown()
                gone
            }
            var best: H2Session? = null
            for (session in list) {
                if (session.hasRoom(MAX_STREAMS) && (best == null || session.activeCount() < best.activeCount())) {
                    best = session
                }
            }
            best?.let {
                it.touch()
                return it
            }
            // Every session is at the soft stream limit: grow the pool while
            // there is room, otherwise share the least busy session rather
            // than making the caller wait.
            if (list.isNotEmpty() && list.size >= MAX_SESSIONS) {
                val fallback = list.minByOrNull { it.activeCount() }!!
                fallback.touch()
                return fallback
            }
            sessionGeneration
        }
        val fresh = H2Session(location, timeout)
        synchronized(sessions) {
            if (generation != sessionGeneration) {
                fresh.shutdown()
                throw TransportReset()
            }
            val list = sessions.getOrPut(key) { mutableListOf() }
            list.removeAll { session ->
                val gone = !session.alive()
                if (gone) session.shutdown()
                gone
            }
            if (list.size >= MAX_SESSIONS) {
                fresh.shutdown()
                val fallback = list.minByOrNull { it.activeCount() }!!
                fallback.touch()
                return fallback
            }
            list.add(fresh)
        }
        return fresh
    }

    private fun isCertificateFailure(error: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is javax.net.ssl.SSLPeerUnverifiedException ||
                current is java.security.cert.CertificateException ||
                current is java.security.cert.CertPathValidatorException
            ) return true
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
                current is java.security.cert.CertPathValidatorException
            ) return true
            current = current.cause
        }
        return false
    }

    internal fun authority(host: String, port: Int) = "$host:$port"

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
                socket.soTimeout = READ_TIMEOUT_MS
                return FoxDuplex(socket, input, output)
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

    /**
     * One HTTP/2 connection to the proxy, shared by up to a handful of tunnels.
     * A reader thread demultiplexes the frames; each stream keeps its own inbox
     * and half of the flow-control state lives here, under [stateLock].
     */
    internal class H2Session(location: FoxLocation, timeout: Int) {

        private val socket: SSLSocket = connect(location, arrayOf("h2"), timeout)
        private val input = BufferedInputStream(socket.inputStream)
        private val output = BufferedOutputStream(socket.outputStream)
        private val outputLock = Any()
        private val streams = ConcurrentHashMap<Int, FoxDuplex.H2>()
        private val stateLock = Object()
        private val headerBlocks = HashMap<Int, ByteArrayOutputStream>()
        private val pendingWindows = HashMap<Int, Long>()
        private val sendWindows = HashMap<Int, Long>()
        private var connectionWindow = 65535L
        private var initialWindow = 65535L
        private var nextId = 1
        @Volatile private var dead = false
        @Volatile private var lastUse = System.currentTimeMillis()
        @Volatile private var serverMaxStreams = Int.MAX_VALUE

        init {
            synchronized(outputLock) {
                output.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.write(frame(0x04, 0, 0, settings()))
                output.flush()
            }
        }

        private val reader = kotlin.concurrent.thread(isDaemon = true, name = "fox-h2-reader") { readLoop() }

        fun alive(): Boolean = !dead && !socket.isClosed
        fun activeCount(): Int = streams.size
        fun hasRoom(softLimit: Int): Boolean = activeCount() < softLimit && activeCount() < serverMaxStreams
        fun lastUse(): Long = lastUse
        fun touch() {
            lastUse = System.currentTimeMillis()
        }

        fun shutdown() {
            dead = true
            synchronized(stateLock) { stateLock.notifyAll() }
            failAll(java.io.IOException("the tunnel session was closed"))
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        fun openStream(host: String, port: Int, pass: String, timeout: Int): FoxDuplex {
            if (dead) throw java.io.IOException("the tunnel session is gone")
            if (activeCount() >= serverMaxStreams) throw java.io.IOException("the proxy limits concurrent streams")
            val deadline = System.currentTimeMillis() + timeout
            // RFC 9113 lets the client send frames right after its preface; waiting
            // for the server SETTINGS first would deadlock proxies that answer the
            // request before advertising settings.
            val id = synchronized(stateLock) {
                val id = nextId
                nextId += 2
                // A window update may have arrived before the stream was known.
                val pending = pendingWindows.remove(id) ?: 0L
                if (pending != 0L) sendWindows[id] = initialWindow + pending
                id
            }
            val stream = FoxDuplex.H2(this, id)
            streams[id] = stream
            touch()
            try {
                val block = ByteArrayOutputStream()
                block.write(pair(":method", "CONNECT"))
                block.write(pair(":authority", authority(host, port)))
                block.write(pair("proxy-authorization", "Bearer $pass"))
                block.write(pair("user-agent", AGENT))
                sendFrame(frame(0x01, 0x04, id, block.toByteArray()))
                if (!stream.awaitOpen((deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1))) {
                    val refusal = stream.refusal()
                    streams.remove(id)
                    cancel(id)
                    throw refusal ?: java.io.IOException("the proxy did not answer in time")
                }
                return FoxDuplex(stream)
            } catch (e: Exception) {
                streams.remove(id)
                cancel(id)
                throw e
            }
        }

        fun removeStream(id: Int) {
            streams.remove(id)
            synchronized(stateLock) {
                sendWindows.remove(id)
                pendingWindows.remove(id)
            }
        }

        private fun cancel(id: Int) {
            try {
                sendFrame(frame(0x03, 0, id, byteArrayOf(0, 0, 0, 8)))
            } catch (_: Exception) {
            }
        }

        /** Sends [bytes] as DATA of [id]; non-blocking sends return the tail that did not fit. */
        fun sendData(id: Int, bytes: ByteArray, blocking: Boolean): ByteArray {
            var sent = 0
            val deadline = System.currentTimeMillis() + UPLOAD_STALL_MS
            while (sent < bytes.size) {
                val chunk = synchronized(stateLock) {
                    var granted = 0
                    while (granted == 0) {
                        if (dead) throw java.io.IOException("the tunnel is closed")
                        val streamWindow = sendWindows[id] ?: initialWindow
                        val available = minOf(streamWindow, connectionWindow)
                        if (available > 0) {
                            granted = minOf((bytes.size - sent).toLong(), available, 16384L).toInt()
                            sendWindows[id] = streamWindow - granted
                            connectionWindow -= granted
                            break
                        }
                        if (!blocking) break
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) throw java.io.IOException("upload stalled")
                        stateLock.wait(left)
                    }
                    granted
                }
                if (chunk == 0) return bytes.copyOfRange(sent, bytes.size)
                sendFrame(frame(0x00, 0, id, bytes, sent, chunk))
                sent += chunk
            }
            return ByteArray(0)
        }

        fun sendFrame(bytes: ByteArray) {
            synchronized(outputLock) {
                output.write(bytes)
                output.flush()
            }
        }

        private fun failAll(error: Exception) {
            for (stream in streams.values) stream.fail(error)
        }

        private fun readLoop() {
            try {
                while (true) {
                    val header = ByteArray(9)
                    if (!fill(input, header)) break
                    val size = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or
                        (header[2].toInt() and 0xFF)
                    val type = header[3].toInt() and 0xFF
                    val flags = header[4].toInt() and 0xFF
                    val id = ((header[5].toInt() and 0x7F) shl 24) or ((header[6].toInt() and 0xFF) shl 16) or
                        ((header[7].toInt() and 0xFF) shl 8) or (header[8].toInt() and 0xFF)
                    if (size > 16384) throw java.io.IOException("the proxy sent an oversized HTTP/2 frame")
                    val payload = ByteArray(size)
                    if (size > 0 && !fill(input, payload)) break
                    when (type) {
                        0x00 -> data(id, flags, size, payload)
                        0x01 -> headers(id, flags, payload)
                        0x03 -> {
                            headerBlocks.remove(id)
                            val stream = streams[id]
                            removeStream(id)
                            stream?.fail(java.io.IOException("the proxy reset the tunnel (${code(payload)})"))
                        }
                        0x04 -> settingsIn(flags, payload)
                        0x06 -> if (flags and 0x01 == 0) sendFrame(frame(0x06, 0x01, 0, payload))
                        0x07 -> throw java.io.IOException("the proxy closed the connection (${code(payload)})")
                        0x08 -> windowUpdate(id, payload)
                        0x09 -> continuation(id, flags, payload)
                    }
                }
                // Mark the session dead before failing its streams: a reader
                // woken by failAll could otherwise grab this session from the
                // pool and write into a socket the proxy has already closed.
                dead = true
                failAll(java.io.IOException("the proxy closed the connection"))
            } catch (error: Exception) {
                dead = true
                failAll(error)
            } finally {
                dead = true
                headerBlocks.clear()
                synchronized(stateLock) { stateLock.notifyAll() }
                failAll(java.io.IOException("the tunnel session ended"))
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }

        private fun data(id: Int, flags: Int, size: Int, payload: ByteArray) {
            var start = 0
            var end = size
            if (flags and 0x08 != 0) {
                if (size == 0) return
                start = 1
                end -= payload[0].toInt() and 0xFF
                if (end < start) return
            }
            if (size > 0) grow(id, size)
            val stream = streams[id] ?: return
            // Deliver the payload before flagging the end: a reader woken by
            // end() with an empty inbox would return EOF and truncate the
            // final frame's data. read() drains the inbox before honoring
            // ended, so this order is race-free.
            if (end > start) stream.deliver(payload.copyOfRange(start, end))
            if (flags and 0x01 != 0) stream.end()
        }

        private fun grow(id: Int, amount: Int) {
            val update = byteArrayOf(
                ((amount ushr 24) and 0x7F).toByte(),
                ((amount ushr 16) and 0xFF).toByte(),
                ((amount ushr 8) and 0xFF).toByte(),
                (amount and 0xFF).toByte()
            )
            try {
                sendFrame(frame(0x08, 0, 0, update))
                if (streams.containsKey(id)) sendFrame(frame(0x08, 0, id, update))
            } catch (_: Exception) {
            }
        }

        private fun headers(id: Int, flags: Int, payload: ByteArray) {
            var from = 0
            var to = payload.size
            if (flags and 0x08 != 0) {
                if (payload.isEmpty()) return
                from++
                to -= payload[0].toInt() and 255
            }
            if (flags and 0x20 != 0) from += 5
            if (from > to) return
            val block = headerBlocks.getOrPut(id) { ByteArrayOutputStream() }
            block.write(payload, from, to - from)
            if (flags and 0x04 != 0) completeHeaders(id)
        }

        private fun continuation(id: Int, flags: Int, payload: ByteArray) {
            val block = headerBlocks[id] ?: return
            if (block.size() + payload.size > 65536) {
                headerBlocks.remove(id)
                streams.remove(id)?.fail(java.io.IOException("HTTP/2 headers too large"))
                return
            }
            block.write(payload)
            if (flags and 0x04 != 0) completeHeaders(id)
        }

        private fun completeHeaders(id: Int) {
            val block = headerBlocks.remove(id) ?: return
            val stream = streams[id] ?: return
            val reply = status(block.toByteArray())
            if (reply == null) {
                // Unparsable response headers: fail fast so the caller can fall
                // back instead of hanging until the open timeout.
                stream.fail(java.io.IOException("the proxy sent unparsable HTTP/2 headers"))
                return
            }
            if (reply in 100..199) return
            if (reply in 200..299) stream.markOpen()
            else stream.fail(FoxProxyRefusal(reply, reason(reply, mapOf())))
        }

        private fun settingsIn(flags: Int, payload: ByteArray) {
            if (flags and 0x01 != 0) return
            val changed = h2InitialWindow(payload, synchronized(stateLock) { initialWindow })
            // Honor the server's own concurrency cap (SETTINGS_MAX_CONCURRENT_STREAMS).
            // h2InitialWindow has already validated that payload.size % 6 == 0.
            for (index in payload.indices step 6) {
                val id = ((payload[index].toInt() and 255) shl 8) or (payload[index + 1].toInt() and 255)
                if (id == 3) {
                    val value = ((payload[index + 2].toLong() and 255) shl 24) or
                        ((payload[index + 3].toLong() and 255) shl 16) or
                        ((payload[index + 4].toLong() and 255) shl 8) or (payload[index + 5].toLong() and 255)
                    serverMaxStreams = if (value == 0L) 0 else value.toInt().coerceAtLeast(0)
                }
            }
            synchronized(stateLock) {
                val delta = changed - initialWindow
                initialWindow = changed
                if (delta != 0L) {
                    for ((id, window) in sendWindows) {
                        val updated = window + delta
                        if (updated > 0x7fffffffL) throw java.io.IOException("HTTP/2 window overflow")
                        sendWindows[id] = updated
                    }
                }
                stateLock.notifyAll()
            }
            sendFrame(frame(0x04, 0x01, 0, ByteArray(0)))
        }

        private fun windowUpdate(id: Int, payload: ByteArray) {
            if (payload.size != 4) return
            val increase = ((payload[0].toLong() and 0x7F) shl 24) or ((payload[1].toLong() and 255) shl 16) or
                ((payload[2].toLong() and 255) shl 8) or (payload[3].toLong() and 255)
            if (increase == 0L) return
            synchronized(stateLock) {
                if (id == 0) {
                    connectionWindow += increase
                    if (connectionWindow > 0x7fffffffL) throw java.io.IOException("HTTP/2 window overflow")
                } else if (streams.containsKey(id)) {
                    val updated = (sendWindows[id] ?: initialWindow) + increase
                    if (updated > 0x7fffffffL) throw java.io.IOException("HTTP/2 window overflow")
                    sendWindows[id] = updated
                } else {
                    // A window update may arrive before the stream is registered; keep it.
                    pendingWindows[id] = (pendingWindows[id] ?: 0L) + increase
                }
                stateLock.notifyAll()
            }
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

    internal fun connect(location: FoxLocation, protocols: Array<String>, timeout: Int): SSLSocket {
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
            tls.soTimeout = 0
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
        raw.keepAlive = false
        raw.tcpNoDelay = true
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
            throw if (e is FoxError) e else FoxError("The proxy chain did not answer: ${e.message ?: "no reply"}")
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
            val status = head.lineSequence().firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
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

    internal fun fill(input: BufferedInputStream, buffer: ByteArray): Boolean {
        var done = 0
        while (done < buffer.size) {
            val size = input.read(buffer, done, buffer.size - done)
            if (size < 0) return false
            done += size
        }
        return true
    }

    internal fun settings(): ByteArray {
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

    internal fun pair(name: String, value: String): ByteArray {
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

    internal fun frame(type: Int, flags: Int, id: Int, payload: ByteArray): ByteArray =
        frame(type, flags, id, payload, 0, payload.size)

    /** Builds a frame straight from a slice of [payload], with no intermediate copy. */
    internal fun frame(type: Int, flags: Int, id: Int, payload: ByteArray, offset: Int, length: Int): ByteArray {
        val out = ByteArray(9 + length)
        out[0] = ((length ushr 16) and 0xFF).toByte()
        out[1] = ((length ushr 8) and 0xFF).toByte()
        out[2] = (length and 0xFF).toByte()
        out[3] = type.toByte()
        out[4] = flags.toByte()
        out[5] = ((id ushr 24) and 0x7F).toByte()
        out[6] = ((id ushr 16) and 0xFF).toByte()
        out[7] = ((id ushr 8) and 0xFF).toByte()
        out[8] = (id and 0xFF).toByte()
        System.arraycopy(payload, offset, out, 9, length)
        return out
    }

    internal fun code(payload: ByteArray): Int {
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

    internal fun status(block: ByteArray): Int? {
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

    internal fun reason(status: Int?, headers: Map<String, String>): String {
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
