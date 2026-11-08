package com.gozar

import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/**
 * A WARP CONNECT-IP transport entirely inside the force-tunnel Mozilla proxy.
 * It never opens a direct internet socket and never substitutes CONNECT-UDP.
 * Its owner must call close when the VPN attempt or setting is revoked.
 */
class FoxWarp(
    proxyPort: Int,
    private val load: () -> String?,
    private val save: (String) -> Boolean,
    private val current: () -> Boolean
) {
    private val sockets = WarpSockets(proxyPort, current)
    private val lock = Any()
    @Volatile private var closed = false
    private var identity: WarpIdentity? = null
    private var ssl: SSLContext? = null
    private var session: WarpSession? = null

    /** Enroll (if necessary), then choose only an endpoint with a real UDP reply. */
    fun start() {
        if (!sockets.valid()) throw IOException("WARP stopped")
        val registered = WarpEnrollment(sockets, save).obtain(load())
        val context = WarpTLS.context(registered)
        val selected = scan(registered, context)
        synchronized(lock) {
            if (closed || !sockets.valid()) { selected.close(); throw IOException("WARP stopped") }
            identity = registered; ssl = context; session = selected
        }
    }

    fun openUDP(host: String, port: Int): FoxDatagramPipe = synchronized(lock) {
        if (closed || !sockets.valid()) throw IOException("WARP stopped")
        var active = session
        if (active == null || !active.alive()) {
            val registered = identity ?: throw IOException("WARP endpoint scan is not ready")
            val context = ssl ?: throw IOException("WARP endpoint scan is not ready")
            active?.close()
            active = scan(registered, context)
            if (closed || !sockets.valid()) { active.close(); throw IOException("WARP stopped") }
            session = active
        }
        active.open(host, port)
    }

    fun close() {
        // Interrupt handshakes/scans before waiting for the session lock.
        closed = true
        sockets.close()
        synchronized(lock) { session?.close(); session = null; identity = null; ssl = null }
    }

    private fun scan(identity: WarpIdentity, ssl: SSLContext): WarpSession {
        val endpoints = listOf("162.159.198.2" to 443, "162.159.198.3" to 443,
            "162.159.198.50" to 443, "162.159.198.2" to 8443)
        val monitor = Object()
        var remaining = endpoints.size
        var decided = false
        var winner: WarpSession? = null
        for ((host, port) in endpoints) {
            thread(isDaemon = true, name = "warp-scan") {
                var candidate: WarpSession? = null
                try {
                    val (raw, secured) = WarpTLS.connect(sockets, ssl, host, port)
                    candidate = try { WarpSession.open(sockets, identity, raw, secured) }
                    catch (e: Exception) { sockets.release(raw); throw e }
                    if (!candidate.verify() || !sockets.valid()) throw IOException("WARP UDP probe failed")
                    synchronized(monitor) {
                        if (!decided) { winner = candidate; candidate = null; decided = true; monitor.notifyAll() }
                    }
                } catch (_: Exception) {
                    // Candidate errors are intentionally not logged with identity data.
                } finally {
                    candidate?.close()
                    synchronized(monitor) {
                        remaining--
                        if (remaining == 0) { decided = true; monitor.notifyAll() }
                    }
                }
            }
        }
        val deadline = System.currentTimeMillis() + 16_000L
        synchronized(monitor) {
            while (!decided && sockets.valid()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                monitor.wait(left)
            }
            decided = true
            return winner ?: throw IOException("no WARP endpoint passed an authenticated UDP round trip")
        }
    }
}

private class WarpSession private constructor(
    private val sockets: WarpSockets,
    private val source: Int,
    private val raw: Socket,
    private val h2: FoxTunnel.H2Session,
    private val stream: FoxDuplex
) {
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val flowLock = Any()
    private val flows = HashMap<Int, WarpFlow>()
    private val sequence = AtomicInteger(SecureRandom().nextInt(65536))
    private var nextPort = 32768 + SecureRandom().nextInt(32768)
    private val input = object : InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int { val n = read(one, 0, 1); return if (n < 0) -1 else one[0].toInt() and 255 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = if (length == 0) 0 else stream.read(buffer, offset, length)
    }
    init {
        stream.setReadTimeout(0)
        thread(isDaemon = true, name = "warp-packets") {
            try {
                while (!closed.get()) {
                    val packet = FoxWarpPackets.read(input) ?: break
                    val data = FoxWarpPackets.parse(packet) ?: continue
                    if (data.target != source) continue
                    val flow = synchronized(flowLock) { flows[data.targetPort] }
                    if (flow != null && flow.target == data.source && flow.remotePort == data.sourcePort) flow.deliver(data.payload)
                }
            } catch (_: Exception) { } finally { close() }
        }
    }
    fun alive() = !closed.get() && h2.alive() && sockets.valid()
    fun open(host: String, port: Int): WarpFlow {
        val target = FoxWarpPackets.ipv4(host) ?: throw IOException("WARP requires an IPv4 target")
        val first = target ushr 24
        if (first == 0 || first == 127 || first >= 224 || port !in 1..65535) throw IOException("invalid WARP target")
        synchronized(flowLock) {
            if (!alive() || flows.size >= 64) throw IOException("WARP session unavailable")
            repeat(32768) {
                nextPort++; if (nextPort > 65535) nextPort = 32768
                if (!flows.containsKey(nextPort)) {
                    val flow = WarpFlow(this, target, port, nextPort)
                    flows[nextPort] = flow
                    return flow
                }
            }
        }
        throw IOException("WARP source ports exhausted")
    }
    fun send(flow: WarpFlow, payload: ByteArray) {
        val packet = FoxWarpPackets.build(source, flow.target, flow.localPort, flow.remotePort, payload, sequence.incrementAndGet())
        val capsule = FoxWarpPackets.encode(packet)
        synchronized(writeLock) {
            if (!alive() || flow.isClosed()) throw IOException("WARP flow closed")
            try { stream.writeCapsule(capsule) }
            catch (e: Exception) { close(); throw IOException("WARP send failed", e) }
        }
    }
    fun forget(flow: WarpFlow) { synchronized(flowLock) { if (flows[flow.localPort] === flow) flows.remove(flow.localPort) } }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val all = synchronized(flowLock) { flows.values.toList().also { flows.clear() } }
        all.forEach { it.close() }
        try { stream.abort() } catch (_: Exception) { }
        try { h2.shutdown() } catch (_: Exception) { }
        sockets.release(raw)
    }
    fun verify(): Boolean {
        val flow = open("1.1.1.1", 53)
        try {
            val nonce = SecureRandom().nextInt(65536)
            val query = byteArrayOf((nonce ushr 8).toByte(), nonce.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 1, 0, 1)
            flow.send(query)
            val deadline = System.currentTimeMillis() + 9000L
            while (true) {
                val remaining = deadline - System.currentTimeMillis(); if (remaining <= 0) return false
                val reply = flow.poll(remaining) ?: return false
                if (reply.size >= 12 && ((reply[0].toInt() and 255) shl 8 or (reply[1].toInt() and 255)) == nonce &&
                    reply[2].toInt() and 0x80 != 0 && reply[3].toInt() and 15 == 0) return true
            }
        } finally { flow.close() }
    }
    companion object {
        fun open(sockets: WarpSockets, identity: WarpIdentity, raw: Socket, ssl: javax.net.ssl.SSLSocket): WarpSession {
            val h2 = FoxTunnel.H2Session(null, 12000, 0L, udp = true, providedSocket = ssl, warp = true)
            try {
                val stream = h2.openWarpIP(12000)
                return WarpSession(sockets, FoxWarpPackets.ipv4(identity.ipv4) ?: throw IOException("invalid WARP address"), raw, h2, stream)
            } catch (e: Exception) { h2.shutdown(); throw e }
        }
    }
}

private class WarpFlow(
    private val session: WarpSession,
    val target: Int,
    val remotePort: Int,
    val localPort: Int
) : FoxDatagramPipe {
    private data class Event(val payload: ByteArray?)
    private val lock = Any()
    private val queue = ArrayBlockingQueue<Event>(32)
    private var closed = false
    private var sending = false
    fun isClosed(): Boolean = synchronized(lock) { closed }
    fun deliver(payload: ByteArray) { synchronized(lock) { if (!closed) queue.offer(Event(payload)) } }
    fun poll(timeout: Long): ByteArray? {
        val event = queue.poll(timeout, TimeUnit.MILLISECONDS) ?: return null
        return synchronized(lock) { if (closed) null else event.payload }
    }
    override fun receive(): ByteArray? {
        val event = queue.take()
        return synchronized(lock) { if (closed) null else event.payload }
    }
    override fun send(payload: ByteArray) {
        synchronized(lock) { if (closed) throw IOException("WARP flow closed"); sending = true }
        try { session.send(this, payload) } finally { synchronized(lock) { sending = false } }
    }
    override fun close() {
        val inFlight = synchronized(lock) {
            if (closed) return
            closed = true; queue.clear(); queue.offer(Event(null)); sending
        }
        session.forget(this)
        // A partially sent capsule cannot safely outlive its flow.
        if (inFlight) session.close()
    }
}
