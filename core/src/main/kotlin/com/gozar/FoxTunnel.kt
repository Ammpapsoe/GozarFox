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
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http2.DefaultHttp2DataFrame
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
import io.netty.handler.codec.http2.DefaultHttp2PingFrame
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame
import io.netty.handler.codec.http2.DefaultHttp2SettingsFrame
import io.netty.handler.codec.http2.Http2DataFrame
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2GoAwayFrame
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.Http2MultiplexCodecBuilder
import io.netty.handler.codec.http2.Http2PingFrame
import io.netty.handler.codec.http2.Http2ResetFrame
import io.netty.handler.codec.http2.Http2Settings
import io.netty.handler.codec.http2.Http2SettingsFrame
import io.netty.handler.codec.http2.Http2StreamChannel
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap
import io.netty.handler.codec.http2.Http2StreamFrame
import io.netty.handler.proxy.HttpProxyHandler
import io.netty.handler.proxy.ProxyConnectException
import io.netty.handler.proxy.Socks5ProxyHandler
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import io.netty.util.ReferenceCounted

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

    internal class H2(
        private val session: FoxTunnel.H2Session,
        internal val child: io.netty.handler.codec.http2.Http2StreamChannel,
        private val bounded: Boolean = false
    ) : Link {
        private val lock = Object()
        private val inbox = ArrayDeque<ByteArray>()
        private var head = ByteArray(0)
        private var headOffset = 0
        private var buffered = 0
        private var ended = false
        private var failure: Exception? = null
        private var open = false

        @Volatile
        private var readTimeoutMs: Int = FoxTunnel.READ_TIMEOUT_MS

        fun timeout(): Int = readTimeoutMs

        fun deliver(payload: ByteArray): Boolean = synchronized(lock) {
            if (bounded && (ended || payload.size > 131_072 - buffered)) {
                if (failure == null) failure = java.io.IOException("UDP stream receive buffer is full")
                ended = true
                lock.notifyAll()
                return@synchronized false
            }
            inbox.addLast(payload)
            if (bounded) buffered += payload.size
            lock.notifyAll()
            true
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

        fun isOpen(): Boolean = synchronized(lock) { open }

        fun awaitOpen(timeoutMs: Int): Boolean = synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!open && failure == null && !ended) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left)
            }
            failure == null && open
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
                        if (bounded) buffered -= take
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
            session.sendData(child, bytes, blocking = true)
        }

        override fun sendIfPossible(bytes: ByteArray): ByteArray = session.sendData(child, bytes, blocking = false)

        override fun halfClose() {
            try {
                child.writeAndFlush(
                    io.netty.handler.codec.http2.DefaultHttp2DataFrame(io.netty.buffer.Unpooled.EMPTY_BUFFER, true)
                )
            } catch (_: Exception) {
                // The session may already be gone; finishing a write must not
                // surface a new failure to the caller.
            }
        }

        override fun teardown() {
            // Remove a UDP stream before sending RST so a concurrent stalled
            // capsule writer cannot resume after cancellation.
            if (bounded) session.removeStream(child)
            try {
                child.writeAndFlush(io.netty.handler.codec.http2.DefaultHttp2ResetFrame(io.netty.handler.codec.http2.Http2Error.CANCEL))
            } catch (_: Exception) {
            }
            try {
                child.close()
            } catch (_: Exception) {
            }
            if (!bounded) session.removeStream(child)
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
    @Volatile private var aborted = false
    private var closeCallback: (() -> Unit)? = null

    internal constructor(socket: SSLSocket, input: BufferedInputStream, output: BufferedOutputStream) :
        this(H1(socket, input, output))

    internal constructor(stream: H2) : this(stream as Link)

    internal fun setReadTimeout(timeout: Int) = link.setTimeout(timeout)

    /**
     * UDP capsules have a single serialized writer and must not share the
     * asynchronous TCP coalescer: a stalled partial capsule could otherwise
     * be interleaved with another flush on the same H2 byte stream.
     */
    internal fun writeCapsule(bytes: ByteArray) {
        synchronized(outputLock) {
            if (closed || aborted) throw java.io.IOException("the tunnel is closed")
        }
        link.sendBlocking(bytes)
    }

    /** Used only while transferring an H1 socket into the active registry. */
    internal fun pendingSocket(): SSLSocket? = (link as? H1)?.socket

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (aborted) throw java.io.IOException("the tunnel was reset")
        val timeout = (link as? H2)?.timeout() ?: 0
        flushQuietly()
        val count = link.read(buffer, offset, length, timeout)
        // A buffered HTTP/1 read may complete while reset is closing its socket.
        if (aborted) throw java.io.IOException("the tunnel was reset")
        return count
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
                    FLUSHER.schedule({ flushQuietly() }, coalesceDelayMs, TimeUnit.MILLISECONDS)
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

    // Register before publishing the duplex so reset can revoke even a socket
    // whose ordinary close is already flushing. No callback runs under lock.
    internal fun onClosed(callback: () -> Unit) {
        val done = synchronized(lock) {
            if (closed) true else {
                closeCallback = callback
                false
            }
        }
        if (done) callback()
    }

    private fun notifyClosed() {
        val callback = synchronized(lock) {
            val saved = closeCallback
            closeCallback = null
            saved
        }
        callback?.invoke()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        try {
            flushQuietly()
            link.teardown()
        } catch (_: Exception) {
        } finally {
            notifyClosed()
        }
    }

    // Transport reset is different from graceful close: discard unsent bytes
    // and close the socket even if a concurrent graceful close has started.
    internal fun abort() {
        synchronized(lock) {
            closed = true
            aborted = true
        }
        synchronized(outputLock) {
            outBuf.reset()
            remainder = ByteArray(0)
            flushScheduled = false
        }
        try {
            link.teardown()
        } catch (_: Exception) {
        } finally {
            notifyClosed()
        }
    }

    /** Runs the buffered bytes out; on a flow-control stall the rest is kept and retried. */
    private fun flushQuietly() {
        val bytes = synchronized(outputLock) {
            flushScheduled = false
            if (aborted) {
                outBuf.reset()
                remainder = ByteArray(0)
                ByteArray(0)
            } else if (outBuf.size() == 0 && remainder.isEmpty()) ByteArray(0) else take()
        }
        if (bytes.isEmpty()) return
        val left = try {
            link.sendIfPossible(bytes)
        } catch (_: Exception) {
            return
        }
        if (left.isNotEmpty()) {
            synchronized(outputLock) {
                if (!aborted) {
                    remainder = left + remainder
                    if (!flushScheduled) {
                        flushScheduled = true
                        FLUSHER.schedule({ flushQuietly() }, STALL_RETRY_MS, TimeUnit.MILLISECONDS)
                    }
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
        private const val POWER_SAVE_COALESCE_MS = 40L
        private const val STALL_RETRY_MS = 10L

        @Volatile
        private var coalesceDelayMs = COALESCE_DELAY_MS

        internal fun setCoalesceDelay(powerSaving: Boolean) {
            coalesceDelayMs = if (powerSaving) POWER_SAVE_COALESCE_MS else COALESCE_DELAY_MS
        }

        internal fun coalesceDelayed(): Boolean = coalesceDelayMs != COALESCE_DELAY_MS
        private val FLUSHER = Executors.newScheduledThreadPool(4) { task ->
            Thread(task, "fox-flush").apply { isDaemon = true }
        }
    }
}
class FoxProxyRefusal(val status: Int, message: String) : java.io.IOException(message)
object FoxTunnel {

    internal const val READ_TIMEOUT_MS = 15000
    private const val AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"
    private const val MAX_SESSIONS = 2
    private const val MAX_STREAMS = 16
    private const val SESSION_IDLE_MS = 60_000L

    // Idle sessions are probed with H2 PINGs so a silently dead path (carrier
    // NAT drop, middlebox reset) is discovered while idle, never by stalling
    // the next flow the user starts.
    private val sweeper = kotlin.concurrent.thread(isDaemon = true, name = "fox-h2-sweeper") {
        while (true) {
            try {
                Thread.sleep(20_000)
            } catch (_: InterruptedException) {
                return@thread
            }
            val now = System.currentTimeMillis()
            for (pool in listOf(sessions, udpSessions)) {
                val snapshot = synchronized(sessions) { pool.values.flatMap { it.toList() } }
                for (session in snapshot) {
                    if (!session.alive()) continue
                    if (session.activeCount() > 0 || now - session.lastUse() <= 25_000) continue
                    if (!session.probe(now)) session.shutdown()
                }
            }
        }
    }
    private const val UPLOAD_STALL_MS = 25_000L

    private class TransportReset : java.io.IOException("transport reset during session creation")

    private val adaptive = FoxAdaptive()
    private val sessions = HashMap<String, MutableList<H2Session>>()
    // UDP has a separate pool because it must decode every response header
    // block and enforce extended-CONNECT and capsule semantics.
    private val udpSessions = HashMap<String, MutableList<H2Session>>()
    private val h1Streams = HashSet<FoxDuplex>()
    private val h2Streams = HashSet<FoxDuplex>()
    private val pendingRelays = HashSet<Socket>()
    // Netty connections that are still dialing or handshaking and have not
    // reached the pool yet. A transport reset must revoke these exactly like
    // the raw relay sockets it already tracks, or the peer never sees EOF.
    private val pendingChannels = HashSet<io.netty.channel.Channel>()
    // TLS wraps the raw socket; close the raw socket on reset so buffered TLS
    // output is not intentionally flushed during revocation.
    private val pendingTls = HashMap<SSLSocket, Socket>()
    // Handshakes run outside the pool lock. A transport reset invalidates
    // in-flight handshakes so they cannot reinsert an obsolete session.
    private var sessionGeneration = 0L
    private var transportEnabled = true

    /**
     * Warms the session pool for [location] so the first real flow does not
     * pay the handshake. Best effort: failures are silent because the regular
     * open path reports them anyway.
     */
    fun prewarm(location: FoxLocation, timeout: Int = 15000, expectedTransport: Long? = null) {
        try {
            val key = location.host + ":" + location.port + "|" + FoxUpstream.text()
            acquireSession(location, key, timeout, expectedTransport).touch()
        } catch (_: Exception) {
        }
    }

    /**
     * On battery, coalesce uploads over a longer window: fewer radio wakeups,
     * at most a few tens of milliseconds of extra latency.
     */
    fun setPowerSaving(enabled: Boolean) = FoxDuplex.setCoalesceDelay(enabled)

    fun powerSaving(): Boolean = FoxDuplex.coalesceDelayed()

    fun resetTransport() { changeTransport(null) }

    /** Start a new generation and return the ticket that belongs to it. */
    fun startTransport(): Long = changeTransport(true)

    /** Revoke pooled sessions and established H1 streams, and reject new opens. */
    fun stopTransport() { changeTransport(false) }

    fun currentTransport(): Long? = synchronized(sessions) {
        if (transportEnabled) sessionGeneration else null
    }

    fun transportIsCurrent(ticket: Long): Boolean = synchronized(sessions) {
        transportEnabled && sessionGeneration == ticket
    }

    // Called under the pool lock (or re-entrantly with it): reset either sees
    // the channel in this set or sees the owning session pooled/dead afterwards.
    private fun untrackPendingChannel(channel: io.netty.channel.Channel?) {
        if (channel == null) return
        pendingChannels.remove(channel)
    }

    private fun releasePendingLocked(socket: SSLSocket) {
        pendingTls.remove(socket)?.let { pendingRelays.remove(it) }
    }

    private fun abortPending(socket: SSLSocket) {
        val raw = synchronized(sessions) { pendingTls[socket] }
        try { raw?.close() } catch (_: Exception) { }
        try { socket.close() } catch (_: Exception) { }
        synchronized(sessions) { releasePendingLocked(socket) }
    }

    private fun changeTransport(enabled: Boolean?): Long {
        val oldSessions: List<H2Session>
        val oldH1: List<FoxDuplex>
        val oldH2: List<FoxDuplex>
        val oldPending: List<Socket>
        val oldChannels: List<io.netty.channel.Channel>
        val ticket: Long
        synchronized(sessions) {
            sessionGeneration += 1
            if (enabled != null) transportEnabled = enabled
            ticket = sessionGeneration
            adaptive.clear()
            oldSessions = sessions.values.flatMap { it.toList() } + udpSessions.values.flatMap { it.toList() }
            oldH1 = h1Streams.toList()
            oldH2 = h2Streams.toList()
            oldPending = pendingRelays.toList()
            oldChannels = pendingChannels.toList()
            sessions.clear()
            udpSessions.clear()
            h1Streams.clear()
            h2Streams.clear()
            pendingRelays.clear()
            pendingChannels.clear()
            pendingTls.clear()
        }
        // Closing outside the pool lock permits callbacks and new generations
        // to progress without letting old resources be put back into the pool.
        for (socket in oldPending) {
            // A blocking relay read may hold this socket from another thread;
            // plain close() then never sends FIN. Shutdown both directions
            // first so the peer observes EOF and the reader wakes up.
            try { socket.shutdownInput() } catch (_: Exception) { }
            try { socket.shutdownOutput() } catch (_: Exception) { }
            try { socket.close() } catch (_: Exception) { }
        }
        // Close in-flight Netty relays so a pending proxy/TLS handshake does
        // not outlive the transport that owns it.
        for (channel in oldChannels) try { channel.close() } catch (_: Exception) { }
        // Close pooled sockets before aborting H2 streams, so their teardown
        // cannot flush an obsolete session while discarding queued writes.
        for (session in oldSessions) session.shutdown()
        for (stream in oldH2) stream.abort()
        for (stream in oldH1) stream.abort()
        return ticket
    }

    internal fun pooledSessionCount(): Int = synchronized(sessions) { sessions.values.sumOf { it.size } }
    internal fun activeH1Count(): Int = synchronized(sessions) { h1Streams.size }
    internal fun activeH2Count(): Int = synchronized(sessions) { h2Streams.size }
    internal fun pendingRelayCount(): Int = synchronized(sessions) { pendingRelays.size + pendingChannels.size }

    fun open(
        location: FoxLocation, host: String, port: Int, pass: String,
        timeout: Int = 15000, expectedTransport: Long? = null
    ): FoxDuplex {
        val key = location.host + ":" + location.port + "|" + FoxUpstream.text()
        val (generation, order) = synchronized(sessions) {
            if (!transportEnabled || (expectedTransport != null && expectedTransport != sessionGeneration)) throw TransportReset()
            sessionGeneration to adaptive.order(key)
        }
        var firstError: Exception? = null
        for (h2 in order) {
            synchronized(sessions) {
                if (generation != sessionGeneration) throw TransportReset()
            }
            val started = System.nanoTime()
            val result = try {
                if (h2) {
                    val pooled = acquireSession(location, key, timeout, generation)
                    try {
                        pooled.openStream(host, port, pass, timeout)
                    } catch (stale: Exception) {
                        if (stale is TransportReset || isCertificateFailure(stale) ||
                            stale is FoxError || stale is FoxProxyRefusal ||
                            pooled.bornAt >= started
                        ) throw stale
                        // The pooled session may have died silently (carrier
                        // NAT rebind, radio sleep, screen-off). Kill it and
                        // retry once on a brand-new connection so one stale
                        // socket cannot stall this flow - and poison the next.
                        pooled.shutdown()
                        acquireSession(location, key, timeout, generation).openStream(host, port, pass, timeout)
                    }
                } else {
                    openH1(location, host, port, pass, timeout, generation)
                }
            } catch (e: Exception) {
                val fatal = e is TransportReset || isCertificateFailure(e) || e is FoxError ||
                    e is FoxProxyRefusal && e.status in setOf(401, 403, 407, 429, 451)
                // A failed handshake or a closed H2 stream can race reset too:
                // do not turn that obsolete attempt into an HTTP/1 fallback.
                val current = synchronized(sessions) {
                    if (generation != sessionGeneration) false else {
                        if (!fatal) adaptive.failed(key, h2)
                        true
                    }
                }
                if (!current) throw TransportReset()
                if (fatal) throw e
                if (firstError == null) firstError = e
                continue
            }
            // A successful H1 or H2 open that completed after reset must not
            // escape to its caller. Close outside the pool lock.
            val accepted = synchronized(sessions) {
                if (generation != sessionGeneration) false else {
                    adaptive.success(key, h2, (System.nanoTime() - started) / 1_000_000L)
                    if (!h2) {
                        result.pendingSocket()?.let { releasePendingLocked(it) }
                        h1Streams.add(result)
                    } else {
                        h2Streams.add(result)
                    }
                    result.onClosed {
                        synchronized(sessions) {
                            if (h2) h2Streams.remove(result) else h1Streams.remove(result)
                        }
                    }
                    true
                }
            }
            if (!accepted) {
                try { result.abort() } catch (_: Exception) { }
                throw TransportReset()
            }
            return result
        }
        throw firstError ?: java.io.IOException("the proxy did not answer")
    }

    /** Open one UDP target through a validated authenticated CONNECT-UDP stream. */
    fun openUDP(
        location: FoxLocation, host: String, port: Int, pass: String,
        timeout: Int = 15000, expectedTransport: Long? = null
    ): FoxDatagramPipe {
        val path = udpPath(host, port)
        if (pass.isEmpty() || pass.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            throw java.io.IOException("invalid proxy credential")
        }
        val key = location.host + ":" + location.port + "|" + FoxUpstream.text()
        val generation = synchronized(sessions) {
            if (!transportEnabled || (expectedTransport != null && expectedTransport != sessionGeneration)) throw TransportReset()
            sessionGeneration
        }
        val udpStarted = System.nanoTime()
        val pooledUdp = acquireSession(location, key, timeout, generation, udp = true)
        val duplex = try {
            pooledUdp.openDatagram(path, pass, timeout)
        } catch (stale: Exception) {
            if (stale is TransportReset || isCertificateFailure(stale) ||
                stale is FoxError || stale is FoxProxyRefusal ||
                pooledUdp.bornAt >= udpStarted
            ) throw stale
            pooledUdp.shutdown()
            acquireSession(location, key, timeout, generation, udp = true).openDatagram(path, pass, timeout)
        }
        val accepted = synchronized(sessions) {
            if (!transportEnabled || generation != sessionGeneration) false else {
                h2Streams.add(duplex)
                duplex.onClosed { synchronized(sessions) { h2Streams.remove(duplex) } }
                true
            }
        }
        if (!accepted) {
            duplex.abort()
            throw TransportReset()
        }
        return FoxDatagram(duplex)
    }

    // Packet-engine destinations are IPv4 addresses. Never resolve a hostname
    // locally while choosing the authenticated UDP relay target.
    internal fun udpPath(host: String, port: Int): String {
        val parts = host.split('.')
        if (port !in 1..65535 || parts.size != 4 || parts.any {
            it.isEmpty() || it.length > 3 || it.any { digit -> digit !in '0'..'9' } ||
                (it.length > 1 && it[0] == '0') || (it.toIntOrNull() ?: 256) > 255
        }) throw java.io.IOException("invalid UDP destination")
        return "/.well-known/masque/udp/$host/$port/"
    }

    private fun acquireSession(location: FoxLocation, key: String, timeout: Int, expectedGeneration: Long? = null, udp: Boolean = false): H2Session {
        val pool = if (udp) udpSessions else sessions
        val generation = synchronized(sessions) {
            if (!transportEnabled || (expectedGeneration != null && expectedGeneration != sessionGeneration)) throw TransportReset()
            val list = pool.getOrPut(key) { mutableListOf() }
            val now = System.currentTimeMillis()
            list.removeAll { session ->
                val idle = session.activeCount() == 0
                val gone = !session.alive() ||
                    (idle && (now - session.lastUse() > SESSION_IDLE_MS || session.isDraining()))
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
            // than making the caller wait. Draining (GOAWAY) sessions do not
            // count toward capacity: a replacement is always allowed.
            val usable = list.filter { !it.isDraining() }
            if (usable.size >= MAX_SESSIONS) {
                val fallback = usable.minByOrNull { it.activeCount() }
                if (fallback != null) {
                    fallback.touch()
                    return fallback
                }
            }
            sessionGeneration
        }
        val fresh = H2Session(location, timeout, generation, udp)
        synchronized(sessions) {
            if (generation != sessionGeneration) {
                fresh.shutdown()
                throw TransportReset()
            }
            val list = pool.getOrPut(key) { mutableListOf() }
            list.removeAll { session ->
                val gone = !session.alive()
                if (gone) session.shutdown()
                gone
            }
            val candidates = list.filter { !it.isDraining() }
            if (candidates.size >= MAX_SESSIONS) {
                fresh.shutdown()
                val fallback = candidates.minByOrNull { it.activeCount() }!!
                fallback.touch()
                return fallback
            }
            list.add(fresh)
            // Hand-off complete: the session is pooled now, so reset finds it
            // through the pool, not through the pending set.
            untrackPendingChannel(fresh.parentChannel())
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

    private fun openH1(location: FoxLocation, host: String, port: Int, pass: String, timeout: Int, generation: Long): FoxDuplex {
        val socket = connect(location, arrayOf("http/1.1"), timeout, generation)
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
            throw if (status == 0) java.io.IOException(reason(status, headers)) else FoxProxyRefusal(status, reason(status, headers))
        } catch (e: Exception) {
            abortPending(socket)
            throw e
        }
    }

    /** WARP CONNECT-IP dial parameters, built by FoxWarp. */
    internal data class WarpTarget(
        val context: javax.net.ssl.SSLContext,
        val host: String,
        val port: Int,
        val proxyPort: Int
    )

    /**
     * One HTTP/2 connection to the proxy, shared by up to a handful of tunnels.
     * Netty owns the wire: the frame codec, HPACK, flow control, SETTINGS
     * acknowledgements and RST bookkeeping are all handled by the standard
     * HTTP/2 codec; this class keeps the blocking, stream-oriented surface
     * the rest of the app was written against.
     */
    internal class H2Session(
        location: FoxLocation?, timeout: Int, generation: Long,
        private val udp: Boolean = false,
        providedWarp: WarpTarget? = null,
        private val warp: Boolean = false
    ) {

        private val target: String
        private val targetPort: Int
        private val sniHost: String
        private val sslContext: javax.net.ssl.SSLContext

        init {
            if (providedWarp != null) {
                target = providedWarp.host
                targetPort = providedWarp.port
                sniHost = WARP_SNI
                sslContext = providedWarp.context
            } else {
                val relay = location ?: throw java.io.IOException("missing relay")
                target = relay.host
                targetPort = relay.port
                sniHost = relay.host
                sslContext = javax.net.ssl.SSLContext.getDefault()
            }
        }

        private val warpSide: WarpTarget? = providedWarp
        private val proxyAuthority = location?.let { authority(it.host, it.port) } ?: ""
        private val udpOpenLock = Any()
        private val streams = ConcurrentHashMap<io.netty.handler.codec.http2.Http2StreamChannel, FoxDuplex.H2>()
        private val stateLock = Object()
        @Volatile private var peerSettingsSeen = false
        @Volatile private var peerExtendedConnect = false
        @Volatile private var serverMaxStreams = Int.MAX_VALUE
        // Monotonic birth stamp: a session created by the current attempt is
        // not a stale pooled one, so its first-use failure must not trigger
        // the stale-session retry (that would double-dial honest refusals).
        internal val bornAt: Long = System.nanoTime()
        @Volatile private var dead = false
        // Set on a graceful GOAWAY: established streams may drain to EOF,
        // but no new stream is handed this connection.
        @Volatile private var draining = false
        @Volatile private var lastUse = System.currentTimeMillis()
        @Volatile private var pingSentAt = 0L
        @Volatile private var lastPong = 0L

        private lateinit var parent: io.netty.channel.Channel

        init {
            try {
                connectNetty(timeout)
            } catch (e: Exception) {
                dead = true
                try { if (::parent.isInitialized) parent.close() } catch (_: Exception) { }
                throw e
            }
        }

        /** Sharable handler for every child (stream) channel of this session. */
        @io.netty.channel.ChannelHandler.Sharable
        private inner class SessionChildRouter : io.netty.channel.SimpleChannelInboundHandler<io.netty.handler.codec.http2.Http2StreamFrame>() {
            override fun channelRead0(ctx: io.netty.channel.ChannelHandlerContext, frame: io.netty.handler.codec.http2.Http2StreamFrame) {
                val channel = ctx.channel() as io.netty.handler.codec.http2.Http2StreamChannel
                when (frame) {
                    is io.netty.handler.codec.http2.Http2HeadersFrame -> onHeaders(channel, frame)
                    is io.netty.handler.codec.http2.Http2DataFrame -> onData(channel, frame)
                    is io.netty.handler.codec.http2.Http2ResetFrame -> {
                        streams[channel]?.fail(java.io.IOException("the proxy reset the tunnel (${frame.errorCode()})"))
                    }
                    else -> {
                        // WINDOW_UPDATE / PRIORITY are handled by Netty's flow controller.
                    }
                }
            }

            override fun userEventTriggered(ctx: io.netty.channel.ChannelHandlerContext, evt: Any) {
                when (evt) {
                    is io.netty.handler.codec.http2.Http2GoAwayFrame -> {
                        // The server relays a retained duplicate of GOAWAY to every stream it
                        // will never process. Unblock that stream instead of hanging until
                        // the read timeout; the caller may retry on a fresh session.
                        val channel = ctx.channel() as? io.netty.handler.codec.http2.Http2StreamChannel
                        if (channel != null) {
                            streams[channel]?.fail(java.io.IOException("the proxy will not process this stream"))
                        }
                        evt.release()
                    }
                    is io.netty.util.ReferenceCounted -> evt.release()
                    else -> ctx.fireUserEventTriggered(evt)
                }
            }

            override fun exceptionCaught(ctx: io.netty.channel.ChannelHandlerContext, cause: Throwable) {
                val channel = ctx.channel() as? io.netty.handler.codec.http2.Http2StreamChannel
                if (channel != null) {
                    val error = cause as? Exception ?: java.io.IOException("the tunnel stream failed", cause)
                    streams[channel]?.fail(error)
                    try { ctx.close() } catch (_: Exception) { }
                }
            }

            override fun channelInactive(ctx: io.netty.channel.ChannelHandlerContext) {
                val channel = ctx.channel() as? io.netty.handler.codec.http2.Http2StreamChannel
                if (channel != null) {
                    streams.remove(channel)?.fail(java.io.IOException("the tunnel stream closed"))
                }
                ctx.fireChannelInactive()
            }
        }

        /** Parent-pipeline handler for connection-level frames Netty does not consume. */
        private inner class SessionObserver : io.netty.channel.ChannelInboundHandlerAdapter() {
            override fun channelRead(ctx: io.netty.channel.ChannelHandlerContext, msg: Any) {
                when (msg) {
                    is io.netty.handler.codec.http2.Http2SettingsFrame -> onPeerSettings(msg.settings())
                    is io.netty.handler.codec.http2.Http2PingFrame -> if (msg.ack()) lastPong = System.currentTimeMillis()
                    is io.netty.handler.codec.http2.Http2GoAwayFrame -> {
                        // Graceful GOAWAY: stop handing out new streams and
                        // let established ones drain until the socket closes.
                        draining = true
                        msg.release()
                    }
                    else -> {
                        if (msg is io.netty.util.ReferenceCounted) msg.release()
                    }
                }
            }

            override fun exceptionCaught(ctx: io.netty.channel.ChannelHandlerContext, cause: Throwable) {
                connectionError(cause as? Exception ?: java.io.IOException("the HTTP/2 connection failed", cause))
            }

            override fun channelInactive(ctx: io.netty.channel.ChannelHandlerContext) {
                dead = true
                failAll(java.io.IOException("the proxy closed the connection"))
                synchronized(stateLock) { stateLock.notifyAll() }
                ctx.fireChannelInactive()
            }
        }

        private fun onPeerSettings(settings: io.netty.handler.codec.http2.Http2Settings) {
            settings.maxConcurrentStreams()?.let { value ->
                serverMaxStreams = if (value <= 0L) 0 else value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            if (udp) {
                val raw = try { settings.getIntValue(8.toChar()) } catch (_: Exception) { null }
                if (raw != null) {
                    if (raw != 0 && raw != 1) {
                        connectionError(java.io.IOException("invalid extended CONNECT setting"))
                        return
                    }
                    if (peerExtendedConnect && raw == 0) {
                        connectionError(java.io.IOException("extended CONNECT was disabled"))
                        return
                    }
                    peerExtendedConnect = raw == 1
                    peerSettingsSeen = true
                }
            }
            synchronized(stateLock) { stateLock.notifyAll() }
        }

        private fun connectionError(error: Exception) {
            dead = true
            failAll(error)
            synchronized(stateLock) { stateLock.notifyAll() }
            try { if (::parent.isInitialized) parent.close() } catch (_: Exception) { }
        }

        private fun remaining(deadline: Long): Int =
            (deadline - System.currentTimeMillis()).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        private fun connectNetty(timeout: Int) {
            val warpConn = warpSide
            val deadline = System.currentTimeMillis() + timeout
            val handshake = java.util.concurrent.CountDownLatch(1)
            val handshakeFailure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)

            // Wrap the app's SSLContext in a Netty JSSE context with ALPN pinned
            // to h2. This keeps every provider property (default vs WARP-pinned
            // contexts, trust managers, session tickets) identical to v1.16.0 while
            // making the negotiated protocol visible through the SslHandler.
            val alpn = io.netty.handler.ssl.ApplicationProtocolConfig(
                io.netty.handler.ssl.ApplicationProtocolConfig.Protocol.ALPN,
                io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                "h2"
            )
            val jdkContext = try {
                io.netty.handler.ssl.JdkSslContext(
                    sslContext,
                    true,
                    null as Iterable<String>?,
                    io.netty.handler.ssl.IdentityCipherSuiteFilter.INSTANCE,
                    alpn,
                    io.netty.handler.ssl.ClientAuth.NONE
                )
            } catch (cause: Exception) {
                throw java.io.IOException("HTTP/2 negotiation is unavailable", cause)
            }
            val engine = jdkContext.newEngine(io.netty.buffer.UnpooledByteBufAllocator.DEFAULT, sniHost, targetPort)
            engine.useClientMode = true
            val parameters = engine.sslParameters
            parameters.serverNames = listOf(javax.net.ssl.SNIHostName(sniHost))
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            engine.sslParameters = parameters
            val sslHandler = io.netty.handler.ssl.SslHandler(engine)
            sslHandler.setHandshakeTimeoutMillis(timeout.toLong())
            // While a proxy CONNECT is still pending, the ProxyHandler holds
            // the outbound close_notify hostage, which stalls the real socket
            // close for the default 3s closeNotifyFlushTimeoutMillis - longer
            // than the reset contract allows. Bound the grace period so a
            // stalled relay connection is torn down promptly (0 is invalid:
            // it would disable the force-close watchdog entirely).
            sslHandler.setCloseNotifyFlushTimeoutMillis(500)

            fun selectedAlpn(): String = try {
                // JdkSslEngine implements ApplicationProtocolAccessor when ALPN is
                // supported; otherwise fail closed (default negotiator -> no wrapper).
                sslHandler.applicationProtocol() ?: ""
            } catch (_: Throwable) {
                ""
            }

            val childRouter = SessionChildRouter()
            val multiplexCodec = io.netty.handler.codec.http2.Http2MultiplexCodecBuilder.forClient(childRouter)
                .initialSettings(
                    io.netty.handler.codec.http2.Http2Settings.defaultSettings()
                        .headerTableSize(4096)
                        .pushEnabled(false)
                        .initialWindowSize(65535)
                        .maxConcurrentStreams(100)
                        .maxFrameSize(16384)
                        .maxHeaderListSize(65536L)
                        .connectProtocolEnabled(true)
                )
                .encoderEnforceMaxConcurrentStreams(false)
                .autoAckPingFrame(true)
                .autoAckSettingsFrame(true)
                .build()

            val bootstrap = io.netty.bootstrap.Bootstrap()
                .group(H2_EVENT_LOOP)
                .channel(io.netty.channel.socket.nio.NioSocketChannel::class.java)
                .option(io.netty.channel.ChannelOption.TCP_NODELAY, true)
                .option(io.netty.channel.ChannelOption.SO_KEEPALIVE, false)
                .handler(object : io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                    override fun initChannel(ch: io.netty.channel.socket.SocketChannel) {
                        val pipeline = ch.pipeline()
                        if (warpConn != null) {
                            // The WARP endpoint is reached through the loopback Mozilla
                            // CONNECT proxy, exactly like the old raw-socket dial.
                            pipeline.addLast(io.netty.handler.proxy.HttpProxyHandler(java.net.InetSocketAddress("127.0.0.1", warpConn.proxyPort)))
                        } else {
                            for (hop in FoxUpstream.hops) {
                                if (hop.type == "http") {
                                    pipeline.addLast(io.netty.handler.proxy.HttpProxyHandler(java.net.InetSocketAddress(hop.host, hop.port)))
                                } else {
                                    pipeline.addLast(io.netty.handler.proxy.Socks5ProxyHandler(java.net.InetSocketAddress(hop.host, hop.port)))
                                }
                            }
                        }
                        pipeline.addLast("ssl", sslHandler)
                        pipeline.addLast("alpn", object : io.netty.channel.ChannelInboundHandlerAdapter() {
                            // The reset path closes the channel while the relay or TLS
                            // handshake is in flight; unblock the waiter immediately
                            // instead of letting it sit out the full deadline. A normal
                            // handshake already counted the latch down, so this is a no-op.
                            private val settled = java.util.concurrent.atomic.AtomicBoolean(false)
                            override fun userEventTriggered(ctx: io.netty.channel.ChannelHandlerContext, evt: Any) {
                                if (evt is io.netty.handler.ssl.SslHandshakeCompletionEvent) {
                                    settled.set(true)
                                    if (evt.isSuccess) {
                                        if (selectedAlpn() != "h2") {
                                            handshakeFailure.compareAndSet(null, java.io.IOException("the proxy did not negotiate HTTP/2"))
                                        }
                                    } else {
                                        handshakeFailure.compareAndSet(null, evt.cause() as? Exception ?: java.io.IOException("TLS handshake failed", evt.cause()))
                                    }
                                    handshake.countDown()
                                    return
                                }
                                ctx.fireUserEventTriggered(evt)
                            }
                            override fun channelInactive(ctx: io.netty.channel.ChannelHandlerContext) {
                                if (!settled.get()) {
                                    handshakeFailure.compareAndSet(null, java.io.IOException("the proxy closed the connection"))
                                    handshake.countDown()
                                }
                                ctx.fireChannelInactive()
                            }
                        })
                        pipeline.addLast("h2", multiplexCodec)
                        pipeline.addLast("observer", SessionObserver())
                    }
                })

            val isWarp = warpConn != null
            val isProxied = isWarp || FoxUpstream.hops.isNotEmpty()
            val connectAddress = if (isProxied) {
                // Proxies tunnel to the target; keep its name unresolved locally and let
                // the ProxyHandlers redirect the TCP connection to the first hop.
                java.net.InetSocketAddress.createUnresolved(target, targetPort)
            } else {
                java.net.InetSocketAddress(target, targetPort)
            }
            if (isProxied) bootstrap.disableResolver()
            // Register the in-flight connection before the reset snapshot can
            // pass us: whoever closes it first wins, both paths converge.
            val connectFuture = synchronized(sessions) {
                bootstrap.connect(connectAddress).also { future -> pendingChannels.add(future.channel()) }
            }
            var channel: io.netty.channel.Channel? = null
            try {
                if (!connectFuture.awaitUninterruptibly(remaining(deadline).toLong())) {
                    connectFuture.channel()?.close()
                    throw java.io.IOException("the proxy did not answer in time")
                }
                if (!connectFuture.isSuccess) {
                    val cause = connectFuture.cause()
                    connectFuture.channel()?.close()
                    throw mapConnectFailure(cause)
                }
                channel = connectFuture.channel()
                if (!handshake.await(remaining(deadline).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    channel.close()
                    throw java.io.IOException("the proxy did not answer in time")
                }
                handshakeFailure.get()?.let {
                    channel.close()
                    throw it
                }
                this.parent = channel
            } catch (e: Exception) {
                try { untrackPendingChannel(connectFuture.channel()) } catch (_: Exception) { }
                try { channel?.close() } catch (_: Exception) { }
                throw e
            }
        }

        private fun mapConnectFailure(cause: Throwable?): Exception = when (cause) {
            is io.netty.handler.proxy.ProxyConnectException ->
                FoxError("The proxy chain did not answer: ${cause.message ?: "no reply"}")
            is Exception -> cause
            else -> java.io.IOException("the proxy did not answer", cause)
        }

        private fun childChannel(): io.netty.handler.codec.http2.Http2StreamChannel {
            val parentChannel = if (::parent.isInitialized) parent else throw java.io.IOException("the tunnel session is gone")
            // The codec's forClient(childRouter) is registered only on remotely-created
            // stream channels; outbound streams created by this bootstrap get an empty
            // pipeline otherwise and every inbound response frame would be dropped
            // silently. Attach the router explicitly (it is @Sharable).
            val future = io.netty.handler.codec.http2.Http2StreamChannelBootstrap(parentChannel)
                .handler(SessionChildRouter())
                .open()
            if (!future.awaitUninterruptibly(10_000)) {
                future.cancel(true)
                throw java.io.IOException("the HTTP/2 stream did not open")
            }
            if (!future.isSuccess) {
                val cause = future.cause()
                throw if (cause is Exception) cause else java.io.IOException("the HTTP/2 stream did not open", cause)
            }
            return future.get()
        }

        private fun openChild(bounded: Boolean): FoxDuplex.H2 {
            val stream = FoxDuplex.H2(this, childChannel(), bounded)
            streams[stream.child] = stream
            return stream
        }

        private fun cancelStream(child: io.netty.handler.codec.http2.Http2StreamChannel) {
            try {
                child.writeAndFlush(io.netty.handler.codec.http2.DefaultHttp2ResetFrame(io.netty.handler.codec.http2.Http2Error.CANCEL))
            } catch (_: Exception) {
            }
        }

        internal fun parentChannel(): io.netty.channel.Channel? =
            if (::parent.isInitialized) parent else null

        fun alive(): Boolean = !dead && ::parent.isInitialized && parent.isActive
        fun isDraining(): Boolean = draining
        fun activeCount(): Int = streams.size
        fun hasRoom(softLimit: Int): Boolean = !draining && activeCount() < softLimit && activeCount() < serverMaxStreams
        fun lastUse(): Long = lastUse
        fun touch() {
            lastUse = System.currentTimeMillis()
        }

        /**
         * Liveness probe for idle sessions: send one H2 PING and expect the
         * ACK inside fifteen seconds. A path that died silently (carrier NAT
         * drop, middlebox reset) is therefore discovered while idle instead
         * of stalling the next flow the user starts.
         */
        fun probe(now: Long): Boolean {
            if (!alive()) return false
            if (pingSentAt != 0L && lastPong >= pingSentAt) pingSentAt = 0L
            return if (pingSentAt == 0L) {
                pingSentAt = now
                try {
                    parent.writeAndFlush(io.netty.handler.codec.http2.DefaultHttp2PingFrame(now))
                    true
                } catch (_: Exception) {
                    false
                }
            } else {
                now - pingSentAt < 15_000
            }
        }

        fun shutdown() {
            dead = true
            synchronized(stateLock) { stateLock.notifyAll() }
            failAll(java.io.IOException("the tunnel session was closed"))
            try {
                if (::parent.isInitialized) {
                    untrackPendingChannel(parent)
                    parent.close()
                }
            } catch (_: Exception) { }
        }

        fun openStream(host: String, port: Int, pass: String, timeout: Int): FoxDuplex {
            if (dead || draining) throw java.io.IOException("the tunnel session is gone")
            if (activeCount() >= serverMaxStreams) throw java.io.IOException("the proxy limits concurrent streams")
            val deadline = System.currentTimeMillis() + timeout
            val stream = synchronized(udpOpenLock) {
                val created = openChild(bounded = false)
                try {
                    val headers = io.netty.handler.codec.http2.DefaultHttp2Headers()
                        .method("CONNECT")
                        .authority(authority(host, port))
                        .add("proxy-authorization", "Bearer $pass")
                        .add("user-agent", AGENT)
                    created.child.writeAndFlush(io.netty.handler.codec.http2.DefaultHttp2HeadersFrame(headers, false))
                } catch (error: Exception) {
                    streams.remove(created.child)
                    cancelStream(created.child)
                    throw error
                }
                created
            }
            touch()
            try {
                if (!stream.awaitOpen(remaining(deadline))) {
                    val refusal = stream.refusal()
                    streams.remove(stream.child)
                    cancelStream(stream.child)
                    throw refusal ?: java.io.IOException("the proxy did not answer in time")
                }
                return FoxDuplex(stream)
            } catch (e: Exception) {
                streams.remove(stream.child)
                cancelStream(stream.child)
                try { stream.child.close() } catch (_: Exception) { }
                throw e
            }
        }

        fun openDatagram(path: String, pass: String, timeout: Int): FoxDuplex {
            if (!udp) throw java.io.IOException("UDP requires a dedicated HTTP/2 session")
            val deadline = System.currentTimeMillis() + timeout.coerceAtLeast(1)
            val stream = synchronized(udpOpenLock) {
                val created = synchronized(stateLock) {
                    while (!peerSettingsSeen && !dead) {
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) throw java.io.IOException("the proxy did not advertise HTTP/2 settings")
                        stateLock.wait(left)
                    }
                    if (dead || draining || !peerExtendedConnect) throw java.io.IOException("the proxy did not enable extended CONNECT")
                    if (System.currentTimeMillis() >= deadline) throw java.io.IOException("the UDP open timed out")
                    if (streams.size >= serverMaxStreams) throw java.io.IOException("the proxy limits concurrent streams")
                    openChild(bounded = true)
                }
                try {
                    val headers = io.netty.handler.codec.http2.DefaultHttp2Headers()
                        .method("CONNECT")
                        .add(":protocol", "connect-udp")
                        .scheme("https")
                        .authority(proxyAuthority)
                        .path(path)
                        .add("capsule-protocol", "?1")
                        .add("proxy-authorization", "Bearer $pass")
                        .add("user-agent", AGENT)
                    created.child.writeAndFlush(io.netty.handler.codec.http2.DefaultHttp2HeadersFrame(headers, false))
                } catch (error: Exception) {
                    streams.remove(created.child)
                    cancelStream(created.child)
                    throw error
                }
                created
            }
            touch()
            try {
                if (!stream.awaitOpen(remaining(deadline))) {
                    throw stream.refusal() ?: java.io.IOException("the UDP proxy did not answer in time")
                }
                return FoxDuplex(stream)
            } catch (error: Exception) {
                removeStream(stream.child)
                cancelStream(stream.child)
                try { stream.child.close() } catch (_: Exception) { }
                throw error
            }
        }

        /** Cloudflare WARP's classic CONNECT-IP uses bare IP capsules, not CONNECT-UDP. */
        fun openWarpIP(timeout: Int): FoxDuplex {
            if (!udp || !warp) throw java.io.IOException("not a WARP session")
            val deadline = System.currentTimeMillis() + timeout.coerceAtLeast(1)
            val stream = synchronized(udpOpenLock) {
                val created = synchronized(stateLock) {
                    if (dead || draining || streams.size >= serverMaxStreams) throw java.io.IOException("WARP session unavailable")
                    openChild(bounded = true)
                }
                try {
                    val headers = io.netty.handler.codec.http2.DefaultHttp2Headers()
                        .method("CONNECT")
                        .authority("cloudflareaccess.com:443")
                        .add("cf-connect-proto", "cf-connect-ip")
                        .add("pq-enabled", "false")
                        .add("user-agent", "")
                    created.child.writeAndFlush(io.netty.handler.codec.http2.DefaultHttp2HeadersFrame(headers, false))
                } catch (error: Exception) {
                    removeStream(created.child)
                    throw error
                }
                created
            }
            touch()
            try {
                if (!stream.awaitOpen(remaining(deadline))) throw stream.refusal() ?: java.io.IOException("WARP CONNECT-IP timed out")
                return FoxDuplex(stream)
            } catch (error: Exception) {
                removeStream(stream.child)
                try { stream.child.close() } catch (_: Exception) { }
                throw error
            }
        }

        fun removeStream(child: io.netty.handler.codec.http2.Http2StreamChannel) {
            removeStreamState(child)
        }

        private fun removeStreamState(child: io.netty.handler.codec.http2.Http2StreamChannel) {
            streams.remove(child)
            synchronized(stateLock) {
                stateLock.notifyAll()
            }
        }

        private fun onData(channel: io.netty.handler.codec.http2.Http2StreamChannel, frame: io.netty.handler.codec.http2.Http2DataFrame) {
            val stream = streams[channel] ?: return
            val content = frame.content()
            if (content.readableBytes() > 0) {
                val bytes = ByteArray(content.readableBytes())
                content.readBytes(bytes)
                // Deliver the payload before flagging the end: a reader woken by
                // end() with an empty inbox would return EOF and truncate the
                // final frame's data. read() drains the inbox before honoring
                // ended, so this order is race-free.
                if (!stream.deliver(bytes)) {
                    removeStream(channel)
                    cancelStream(channel)
                    return
                }
            }
            if (frame.isEndStream) stream.end()
        }

        private fun onHeaders(channel: io.netty.handler.codec.http2.Http2StreamChannel, frame: io.netty.handler.codec.http2.Http2HeadersFrame) {
            val stream = streams[channel] ?: return
            val headers = frame.headers()
            val statusValue = headers.status()?.toString()
            val ended = frame.isEndStream
            if (stream.isOpen()) {
                // Trailers: after the response a HEADERS frame must carry no
                // status and must close the stream.
                if (!udp) {
                    if (ended) stream.end()
                    return
                }
                if (statusValue != null || !ended) {
                    connectionError(java.io.IOException("invalid UDP stream trailers"))
                    return
                }
                stream.end()
                return
            }
            if (statusValue == null) {
                val error = java.io.IOException(if (udp) "missing UDP proxy status" else "the proxy sent unparsable HTTP/2 headers")
                if (udp) connectionError(error) else stream.fail(error)
                return
            }
            val reply = statusValue.toIntOrNull()?.takeIf { it in 100..599 }
            if (reply == null) {
                val error = java.io.IOException(if (udp) "invalid HTTP/2 response status" else "the proxy sent unparsable HTTP/2 headers")
                if (udp) connectionError(error) else stream.fail(error)
                return
            }
            if (reply in 100..199) {
                if (udp && ended) connectionError(java.io.IOException("informational response ended UDP stream"))
                return
            }
            if (!udp) {
                if (reply in 200..299) stream.markOpen()
                else stream.fail(FoxProxyRefusal(reply, reason(reply, mapOf())))
                return
            }
            if (reply !in 200..299) {
                stream.fail(FoxProxyRefusal(reply, reason(reply, mapOf())))
                return
            }
            var invalidBodyHeaders = false
            for (name in headers.names()) {
                if (name.toString().lowercase() in FORBIDDEN_BODY_HEADERS) {
                    invalidBodyHeaders = true
                    break
                }
            }
            var capsuleCount = 0
            var capsule: String? = null
            for (value in headers.getAll("capsule-protocol")) {
                capsuleCount++
                capsule = value.toString().trim()
            }
            if ((warp && (reply != 200 || ended)) ||
                (!warp && (reply in setOf(204, 205, 206) || ended || invalidBodyHeaders ||
                    capsuleCount != 1 || capsule != "?1"))) {
                stream.fail(java.io.IOException("the proxy did not establish a valid UDP capsule stream"))
            } else {
                stream.markOpen()
            }
        }

        private fun failAll(error: Exception) {
            for (stream in streams.values) stream.fail(error)
            streams.clear()
        }

        companion object {
            internal const val WARP_SNI = "consumer-masque.cloudflareclient.com"

            private val FORBIDDEN_BODY_HEADERS = setOf(
                "content-length", "content-type", "content-encoding", "transfer-encoding",
                "connection", "keep-alive", "proxy-connection", "upgrade"
            )

            private const val UPLOAD_STALL_MS = 25_000L

            private val H2_EVENT_LOOP = io.netty.channel.nio.NioEventLoopGroup(2, io.netty.util.concurrent.DefaultThreadFactory("fox-h2", true))
        }

        /** Sends [bytes] as DATA of [child]; non-blocking sends return the tail that did not fit. */
        internal fun sendData(
            child: io.netty.handler.codec.http2.Http2StreamChannel,
            bytes: ByteArray,
            blocking: Boolean
        ): ByteArray {
            if (dead) throw java.io.IOException("the tunnel is closed")
            if (udp && !streams.containsKey(child)) throw java.io.IOException("the tunnel is closed")
            val buffer = io.netty.buffer.Unpooled.wrappedBuffer(bytes)
            if (blocking) {
                val response = child.writeAndFlush(io.netty.handler.codec.http2.DefaultHttp2DataFrame(buffer, false))
                if (!response.awaitUninterruptibly(UPLOAD_STALL_MS)) {
                    throw java.io.IOException("upload stalled")
                }
                if (!response.isSuccess) {
                    val cause = response.cause()
                    throw if (cause is java.io.IOException) cause else java.io.IOException("the tunnel write failed", cause)
                }
                return ByteArray(0)
            }
            if (!child.isWritable) {
                buffer.release()
                return bytes
            }
            child.writeAndFlush(io.netty.handler.codec.http2.DefaultHttp2DataFrame(buffer, false))
            return ByteArray(0)
        }
    }
    fun verify(location: FoxLocation, pass: String, expectedTransport: Long? = null) {
        RelayCheck.verify { target -> open(location, target, 443, pass, 8000, expectedTransport) }
    }

    fun passRejected(error: Exception): Boolean = RelayCheck.passRejected(error)

    fun probe(location: FoxLocation, pass: String, expectedTransport: Long? = null): String {
        val tunnel = open(location, "ifconfig.me", 80, pass, 8000, expectedTransport)
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

    internal fun connect(location: FoxLocation, protocols: Array<String>, timeout: Int, generation: Long): SSLSocket {
        val raw = Socket()
        val admitted = synchronized(sessions) {
            if (!transportEnabled || generation != sessionGeneration) false else pendingRelays.add(raw)
        }
        if (!admitted) {
            try { raw.close() } catch (_: Exception) { }
            throw TransportReset()
        }
        var tls: SSLSocket? = null
        var handedOff = false
        try {
            chain(location.host, location.port, timeout, raw)
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
            val ready: SSLSocket = tls
            synchronized(sessions) {
                if (!transportEnabled || generation != sessionGeneration) throw TransportReset()
                // Keep the underlying raw socket tracked through H1 response
                // or H2 preface/session registration, not just the handshake.
                pendingTls[ready] = raw
                handedOff = true
            }
            return ready
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) { }
            try { tls?.close() } catch (_: Exception) { }
            throw e
        } finally {
            if (!handedOff) synchronized(sessions) { pendingRelays.remove(raw) }
        }
    }

    internal fun chain(host: String, port: Int, timeout: Int, raw: Socket = Socket()): Socket {
        val hops = FoxUpstream.hops
        try {
            raw.keepAlive = false
            raw.tcpNoDelay = true
            val first = if (hops.isEmpty()) host to port else hops.first().host to hops.first().port
            raw.connect(InetSocketAddress(first.first, first.second), timeout)
            raw.soTimeout = timeout
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) { }
            // Preserve the original exception type; the adaptive fallback
            // policy distinguishes I/O failures from explicit proxy errors.
            throw e
        }
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
