package com.gozar

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * One SOCKS5 UDP ASSOCIATE relay (RFC 1928) for the tunnel, tuned for hev.
 *
 * hev multiplexes every UDP datagram the device sends into a single
 * association per app source port; the relay header on each datagram carries
 * the destination. Delivery rules, identical to the retired hand-rolled
 * stack:
 *
 *  - Destinations on port 53 are answered locally by [FoxDnsBridge] — DoH
 *    race, custom profiles, BlockAds, TTL patching, anti-poison, epoch fence.
 *    They never leave the device.
 *  - Every other destination is tunneled through the authenticated
 *    CONNECT-UDP relay (currently WARP, when the user enabled it). There is
 *    deliberately no direct-UDP fallback: an unreachable relay drops the
 *    datagram.
 *  - A stale transport attempt (epoch bump or stop) shuts the association
 *    down; in-flight flow openings are abandoned instead of borrowing the
 *    next attempt.
 *
 * The association owns exactly one reader thread. Warp receive paths get one
 * thread per open flow. Both are daemons and vanish on [close].
 */
internal class FoxUdpAssociate(
    private val socket: DatagramSocket,
    private val mtu: Int,
    // Read per query, never captured once: the bridge is replaced (and the
    // old one closed) on every reconnect, and an association may outlive it.
    private val dns: () -> FoxDnsBridge?,
    private val openUdp: (String, Int) -> FoxDatagramPipe?,
    private val count: ((Boolean, Long) -> Unit)?,
    private val stale: () -> Boolean
) : AutoCloseable {

    companion object {
        private const val MAX_FLOWS = 64
        private const val QUEUE = 32
        private const val BYTES_CAP = 8L * 1024 * 1024
        private const val MAX_ASSOCIATIONS = 256
        private const val IDLE_MS = 90_000L
        private const val SWEEP_MS = 5_000L
        private val open = AtomicLong(0)

        /** Reserve a slot; false when the device already runs too many associations. */
        fun reserve(): Boolean {
            while (true) {
                val current = open.get()
                if (current >= MAX_ASSOCIATIONS) return false
                if (open.compareAndSet(current, current + 1)) return true
            }
        }
        fun release() { open.updateAndGet { if (it > 0) it - 1 else 0 } }
    }

    private val lock = Any()
    private val flows = LinkedHashMap<String, UdpFlow>()
    private val bytesQueued = AtomicLong(0)
    @Volatile private var peer: SocketAddress? = null
    private val running = AtomicBoolean(false)
    private val pendingBytesBudget = BYTES_CAP

    fun start() {
        running.set(true)
        socket.soTimeout = SWEEP_MS.toInt()
        thread(isDaemon = true, name = "fox-udp") { readLoop() }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        release()
        try { socket.close() } catch (_: Exception) { }
        val snapshot = synchronized(lock) {
            val copy = flows.values.toList()
            flows.clear()
            copy
        }
        for (flow in snapshot) flow.close()
    }

    private fun readLoop() {
        val max = 65535
        val stash = ByteArray(max)
        while (running.get()) {
            val packet = DatagramPacket(stash, stash.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                if (stale()) { close(); return }
                sweep()
                continue
            } catch (_: Exception) {
                return
            }
            if (!running.get()) return
            peer = packet.socketAddress
            if (stale()) { close(); return }
            // Copy out: handling may hand the payload to background workers.
            val data = packet.data.copyOf(packet.length)
            handle(data, packet.length)
        }
    }

    private fun handle(data: ByteArray, length: Int) {
        if (length < 10) return
        if (data[0].toInt() != 0 || data[1].toInt() != 0) return
        if (data[2].toInt() != 0) return // FRAG must be zero; there is no reassembly here.
        val atyp = data[3].toInt() and 255
        val host: String
        val addressBytes: ByteArray
        var at = 4
        when (atyp) {
            0x01 -> {
                if (length < at + 4 + 2) return
                addressBytes = data.copyOfRange(at, at + 4)
                host = "${addressBytes[0].toInt() and 255}.${addressBytes[1].toInt() and 255}." +
                    "${addressBytes[2].toInt() and 255}.${addressBytes[3].toInt() and 255}"
                at += 4
            }
            0x04 -> {
                if (length < at + 16 + 2) return
                addressBytes = data.copyOfRange(at, at + 16)
                host = InetAddress.getByAddress(addressBytes).hostAddress ?: return
                at += 16
            }
            0x03 -> {
                val size = data[at].toInt() and 255
                if (size <= 0 || length < at + 1 + size + 2) return
                addressBytes = data.copyOfRange(at, at + 1 + size)
                host = String(addressBytes, 1, size, Charsets.UTF_8)
                at += 1 + size
            }
            else -> return
        }
        val port = ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
        if (port !in 1..65535) return
        val payload = data.copyOfRange(at + 2, length)
        // A UDP payload the tunnel cannot carry is dropped; with a fragile
        // path the old stack answered with ICMP port unreachable.
        if (payload.size > mtu - 28) return
        count?.invoke(false, payload.size.toLong())
        if (port == 53) {
            val replyHeader = replyHeader(atyp, addressBytes, port)
            val bridge = dns()
            if (bridge == null || !bridge.isOpen()) {
                // No live DNS plane for this attempt: drop the query instead
                // of answering from a bridge another attempt already closed.
                return
            }
            bridge.answer(payload) { answer -> sendSocks(answer, replyHeader) }
            return
        }
        val flow = synchronized(lock) {
            val key = "$host:$port"
            flows[key]?.let { return@synchronized it }
            if (flows.size >= MAX_FLOWS) {
                val oldest = flows.entries.minByOrNull { it.value.idleAt }
                if (oldest != null) {
                    flows.remove(oldest.key)
                    oldest.value.close()
                }
            }
            UdpFlow(host, port, atyp, addressBytes).also { flows[key] = it }
        }
        flow.enqueue(payload)
    }

    private fun replyHeader(atyp: Int, addressBytes: ByteArray, port: Int): ByteArray {
        val out = ByteArray(4 + addressBytes.size + 2)
        out[3] = atyp.toByte()
        addressBytes.copyInto(out, 4)
        out[out.size - 2] = (port ushr 8).toByte()
        out[out.size - 1] = port.toByte()
        return out
    }

    private fun sendSocks(payload: ByteArray, replyHeader: ByteArray) {
        val target = peer ?: return
        // replyHeader already carries the RFC 1928 RSV/FRAG prefix; do not add another.
        val out = ByteArray(replyHeader.size + payload.size)
        replyHeader.copyInto(out, 0)
        payload.copyInto(out, replyHeader.size)
        try {
            socket.send(DatagramPacket(out, out.size, target))
            count?.invoke(true, payload.size.toLong())
        } catch (_: Exception) {
        }
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        val idle = synchronized(lock) {
            val staleFlows = flows.values.filter { now - it.idleAt > IDLE_MS }
            staleFlows.forEach { flows.remove("${it.host}:${it.port}", it) }
            staleFlows
        }
        for (flow in idle) flow.close()
    }

    private inner class UdpFlow(
        val host: String,
        val port: Int,
        private val atyp: Int,
        private val addressBytes: ByteArray
    ) {
        private val queue = ArrayBlockingQueue<ByteArray>(QUEUE)
        @Volatile var idleAt: Long = System.currentTimeMillis()
            private set
        private val closed = AtomicBoolean(false)
        private var pipe: FoxDatagramPipe? = null

        init {
            thread(isDaemon = true, name = "fox-udp-flow") { pump() }
        }

        fun enqueue(payload: ByteArray): Boolean {
            if (closed.get() || !running.get()) return false
            val size = payload.size.toLong()
            while (true) {
                val queued = bytesQueued.get()
                if (size > pendingBytesBudget - queued) return false
                if (bytesQueued.compareAndSet(queued, queued + size)) break
            }
            val accepted = queue.offer(payload)
            if (accepted) idleAt = System.currentTimeMillis() else bytesQueued.addAndGet(-size)
            return accepted
        }

        private fun pump() {
            // Never borrow a pipe for an attempt that has already died: a
            // stale attempt must fail closed without opening anything.
            if (stale() || !running.get() || closed.get()) { close(); return }
            val opened = try {
                openUdp(host, port)
            } catch (_: Exception) {
                null
            }
            if (opened == null || closed.get() || !running.get() || stale()) {
                try { opened?.close() } catch (_: Exception) { }
                close()
                return
            }
            pipe = opened
            val header = replyHeader(atyp, addressBytes, port)
            thread(isDaemon = true, name = "fox-udp-in") {
                while (running.get() && !closed.get()) {
                    val payload = try {
                        opened.receive()
                    } catch (_: Exception) {
                        null
                    } ?: break
                    if (!running.get() || closed.get()) break
                    sendSocks(payload, header)
                    idleAt = System.currentTimeMillis()
                }
                close()
            }
            while (running.get() && !closed.get()) {
                val payload = try {
                    queue.poll(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                bytesQueued.addAndGet(-payload.size.toLong())
                try {
                    opened.send(payload)
                } catch (_: Exception) {
                    close()
                    return
                }
                idleAt = System.currentTimeMillis()
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            while (true) {
                val leftover = queue.poll() ?: break
                bytesQueued.addAndGet(-leftover.size.toLong())
            }
            try { pipe?.close() } catch (_: Exception) { }
            synchronized(lock) { flows.remove("$host:$port", this) }
        }
    }
}
