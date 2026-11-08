package com.gozar

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

interface FoxPipe {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun finishWrite() = Unit
    fun close()
}

/** Identifies a DNS owner's attempt and fails closed when it is no longer current. */
class FoxDnsScope(val key: Any, private val valid: () -> Boolean) {
    fun isCurrent(): Boolean = try { valid() } catch (_: Exception) { false }
}

private const val MIN_TTL_SECONDS = 60L
private const val MAX_TTL_SECONDS = 300L
private const val NEGATIVE_TTL_MS = 30_000L
private const val RACE_HEAD_START_MS = 250L

class FoxStack(
    private val incoming: InputStream,
    private val outgoing: OutputStream,
    private val local: Int,
    private val resolver: FoxResolver,
    private val dial: (String, String, Int) -> FoxPipe,
    private val meter: (Boolean, Long) -> Unit,
    mtu: Int = MTU,
    // Capture a flow's lifecycle scope before it is submitted to a worker.
    private val prepareDial: ((String, String, Int) -> (() -> FoxPipe))? = null,
    // Capture DNS ownership at packet/flow admission, before worker dispatch.
    private val prepareDns: (() -> FoxDnsScope?)? = null,
    // Capture the owning transport attempt before a UDP flow is queued.
    // Null means non-DNS UDP is explicitly unavailable, never direct.
    private val prepareUdp: ((String, Int) -> (() -> FoxDatagramPipe)?)? = null
) {

    private val mtu = mtu.coerceIn(576, 65535)
    private val mss = this.mtu - 40

    companion object {
        const val MTU = 1400
        private const val FIN = 0x01
        private const val SYN = 0x02
        private const val RST = 0x04
        private const val PSH = 0x08
        private const val ACK = 0x10
        private const val TCP = 6
        private const val UDP = 17
        private const val WINDOW_SHIFT = 7
        private const val UPLOAD_CAP = 128 * 1024
        private const val GLOBAL_QUEUE_CAP = 8L * 1024 * 1024
        private const val FLIGHT_CAP = 256 * 1024
        private const val MASK = 0xFFFFFFFFL
        private const val MAX_FLOWS = 256
        private const val MAX_UDP_FLOWS = 64
        private const val MAX_UDP_QUEUED = 32
        private const val UDP_IDLE_MS = 90_000L
        private const val MAX_NAMES = 4096
        private const val SYN_BURST = 256
        private const val MAX_PENDING = 10
        private const val CHUNK_SIZE = 2048
        private const val RECEIVE_SIZE = 32768
    }

    private val flows = ConcurrentHashMap<Long, Flow>()
    private val udpLock = Any()
    private val udpFlows = HashMap<Long, UdpFlow>()

    /** Bounded LRU: the resolver learns a name per address and forgets the oldest instead of all. */
    private val names: MutableMap<Int, String> =
        Collections.synchronizedMap(object : LinkedHashMap<Int, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean =
                size > MAX_NAMES
        })
    private val stamp = AtomicInteger(1)
    private val synTokens = AtomicInteger(SYN_BURST)
    @Volatile private var synRefilledAt = System.currentTimeMillis()
    private val globalQueued = AtomicLong(0)
    private val chunkPool = ConcurrentLinkedQueue<ByteArray>()
    private val receivePool = ConcurrentLinkedQueue<ByteArray>()
    private val dnsWorkers = ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, ArrayBlockingQueue(128), { task ->
        Thread(task, "fox-dns").apply { isDaemon = true }
    })

    /** Every flow shares this pool instead of keeping two dedicated threads alive. */
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "fox-flow").apply { isDaemon = true }
    }
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(isDaemon = true, name = "fox-stack") { pump() }
        thread(isDaemon = true, name = "fox-reaper") {
            while (running) {
                sleep(15000)
                val limit = System.currentTimeMillis() - 180000
                for (entry in flows.entries.filter { it.value.idleAt < limit }) {
                    flows.remove(entry.key, entry.value)
                    entry.value.shutdown()
                }
                val udpLimit = System.currentTimeMillis() - UDP_IDLE_MS
                val expired = synchronized(udpLock) { udpFlows.values.filter { it.idleAt < udpLimit } }
                for (flow in expired) flow.shutdownIfIdle(udpLimit)
            }
        }
    }

    fun stop() {
        running = false
        val datagrams = synchronized(udpLock) { udpFlows.values.toList() }
        datagrams.forEach { it.shutdown() }
        for (flow in flows.values) {
            try {
                flow.shutdown()
            } catch (_: Exception) {
            }
        }
        flows.clear()
        dnsWorkers.shutdownNow()
        workers.shutdownNow()
    }

    fun active(): Int = flows.size

    fun nameOf(address: Int): String? = names[address]

    internal fun dnsPipe(scope: FoxDnsScope? = null): FoxPipe = FoxDnsPipe(resolver, scope, ::learn)

    private fun pump() {
        val buffer = ByteArray(65535)
        while (running) {
            val size = try {
                incoming.read(buffer)
            } catch (e: Exception) {
                break
            }
            if (size < 0) break
            if (size < 20 || (buffer[0].toInt() and 0xF0) != 0x40) {
                // Count what the guard stopped instead of letting it leak.
                FoxLeakGuard.note(
                    if (size > 0 && (buffer[0].toInt() and 0xF0) == 0x60) FoxLeakGuard.IPV6_DROPPED
                    else FoxLeakGuard.MALFORMED_DROPPED
                )
                continue
            }
            val header = (buffer[0].toInt() and 0x0F) * 4
            if (header < 20 || size < header + 8) continue
            val total = u16(buffer, 2)
            if (total < header + 8 || total > size) continue
            if (u16(buffer, 6) and 0x3fff != 0) continue
            val source = i32(buffer, 12)
            if (source != local) continue
            val target = i32(buffer, 16)
            when (buffer[9].toInt() and 0xFF) {
                TCP -> if (total >= header + 20) segment(buffer, header, total, target)
                UDP -> datagram(buffer, header, total, target)
            }
        }
    }

    private fun segment(packet: ByteArray, header: Int, total: Int, target: Int) {
        val appPort = u16(packet, header)
        val remotePort = u16(packet, header + 2)
        val seq = u32(packet, header + 4)
        val ack = u32(packet, header + 8)
        val offset = ((packet[header + 12].toInt() and 0xF0) shr 4) * 4
        if (offset < 20) return
        val flags = packet[header + 13].toInt() and 0xFF
        val window = u16(packet, header + 14)
        val body = header + offset
        val length = total - body
        if (length < 0) return
        val key = key(appPort, target, remotePort)

        if (flags and RST != 0) {
            flows.remove(key)?.shutdown()
            return
        }

        var known = flows[key]
        if (known != null && flags and SYN != 0 && flags and ACK == 0) {
            known.shutdown()
            known = null
        }
        if (known == null) {
            if (flags and SYN == 0) {
                send(target, remotePort, appPort, 0L, (seq + length) and MASK, RST or ACK, 0, null, 0, 0)
                return
            }
            if (!admit()) {
                send(target, remotePort, appPort, 0L, (seq + length) and MASK, RST or ACK, 0, null, 0, 0)
                return
            }
            val (peerShift, peerMss) = tcpOptions(packet, header + 20, body)
            val created = Flow(key, appPort, target, remotePort, (seq + 1) and MASK, peerShift, peerMss)
            flows[key] = created
            val options = byteArrayOf(3, 3, WINDOW_SHIFT.toByte(), 0)
            send(target, remotePort, appPort, created.sent, created.expected, SYN or ACK, created.window(), null, 0, 0, options)
            created.sent = (created.sent + 1) and MASK
            created.begin()
            return
        }

        known.idleAt = System.currentTimeMillis()
        if (flags and ACK != 0) known.confirm(ack, window)

        if (length > 0 && known.push(packet, body, length, seq)) meter(false, length.toLong())
        if (flags and FIN != 0) known.finish((seq + length) and MASK)
        if (length > 0 || flags and FIN != 0) {
            send(target, remotePort, appPort, known.sent, known.expected, ACK, known.window(), null, 0, 0)
        }
    }

    private fun tcpOptions(packet: ByteArray, start: Int, end: Int): Pair<Int, Int> {
        var shift = 0
        var peerMss = mss
        var index = start
        while (index < end) {
            val kind = packet[index].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) {
                index++
                continue
            }
            if (index + 1 >= end) break
            val length = packet[index + 1].toInt() and 0xFF
            if (length < 2 || index + length > end) break
            when (kind) {
                2 -> if (length == 4) peerMss = minOf(peerMss, u16(packet, index + 2))
                3 -> if (length == 3) shift = (packet[index + 2].toInt() and 0xFF).coerceAtMost(14)
            }
            index += length
        }
        return shift to peerMss.coerceAtLeast(536)
    }

    private fun admit(): Boolean {
        val now = System.currentTimeMillis()
        if (now - synRefilledAt >= 1000) {
            synRefilledAt = now
            synTokens.set(SYN_BURST)
        }
        if (synTokens.getAndUpdate { if (it > 0) it - 1 else 0 } <= 0) return false
        if (flows.size >= MAX_FLOWS) {
            val oldest = flows.entries.minByOrNull { it.value.idleAt } ?: return false
            flows.remove(oldest.key, oldest.value)
            try {
                oldest.value.shutdown()
            } catch (_: Exception) {
            }
        }
        return true
    }

    private fun datagram(packet: ByteArray, header: Int, total: Int, target: Int) {
        val appPort = u16(packet, header)
        val serverPort = u16(packet, header + 2)
        val length = u16(packet, header + 4)
        val start = header + 8
        if (length < 8 || start + length - 8 > total) return
        if (serverPort != 53) {
            routeDatagram(packet, total, target, appPort, serverPort, start, length)
            return
        }
        if (length < 20) return // DNS has a 12-byte minimum message header.
        val scope = try { prepareDns?.invoke() } catch (_: Exception) { null }
        if (prepareDns != null && scope == null) return
        if (scope?.isCurrent() == false) return
        meter(false, length.toLong())
        val query = packet.copyOfRange(start, start + length - 8)
        try {
            dnsWorkers.execute {
                if (!running || scope?.isCurrent() == false) return@execute
                val answer = try {
                    resolver.answer(query, scope)
                } catch (_: Exception) {
                    null
                } ?: return@execute
                if (!running || scope?.isCurrent() == false) return@execute
                learn(query, answer)
                val limited = resolver.truncate(query, answer, mtu - 28)
                if (running && scope?.isCurrent() != false && limited.isNotEmpty()) replyUdp(target, appPort, serverPort, limited)
            }
        } catch (_: RejectedExecutionException) {
            val failure = resolver.unavailable(query)
            if (running && scope?.isCurrent() != false && failure != null) replyUdp(target, appPort, serverPort, failure)
        }
    }

    private fun routeDatagram(packet: ByteArray, total: Int, target: Int, appPort: Int, remotePort: Int, start: Int, length: Int) {
        if (!running) return
        val key = key(appPort, target, remotePort)
        var selected = synchronized(udpLock) { udpFlows[key] }
        var created = false
        if (selected == null) {
            val opener = try { prepareUdp?.invoke(address(target), remotePort) } catch (_: Exception) { null }
            if (opener == null) {
                unreachable(target, packet, total)
                return
            }
            synchronized(udpLock) {
                if (!running) return
                selected = udpFlows[key]
                if (selected == null && udpFlows.size < MAX_UDP_FLOWS) {
                    selected = UdpFlow(key, appPort, target, remotePort, opener)
                    udpFlows[key] = selected!!
                    created = true
                }
            }
        }
        val flow = selected
        if (flow == null) {
            unreachable(target, packet, total)
            return
        }
        val payload = packet.copyOfRange(start, start + length - 8)
        if (flow.enqueue(payload)) {
            meter(false, length.toLong())
            if (created) flow.begin()
        } else if (created) {
            flow.shutdown()
        }
    }

    private inner class UdpFlow(
        private val key: Long,
        private val appPort: Int,
        private val target: Int,
        private val remotePort: Int,
        private val opener: () -> FoxDatagramPipe
    ) {
        private val lock = Any()
        private val pending = ArrayBlockingQueue<ByteArray>(MAX_UDP_QUEUED)
        @Volatile var idleAt: Long = System.currentTimeMillis()
            private set
        @Volatile private var closed = false
        private var relay: FoxDatagramPipe? = null

        fun enqueue(payload: ByteArray): Boolean = synchronized(lock) {
            if (closed || !running) false else pending.offer(payload).also {
                if (it) idleAt = System.currentTimeMillis()
            }
        }

        fun begin() {
            try {
                workers.execute {
                    try {
                        val opened = opener()
                        val accepted = synchronized(lock) {
                            if (closed || !running) false else {
                                relay = opened
                                true
                            }
                        }
                        if (!accepted) {
                            opened.close()
                            return@execute
                        }
                        try {
                            workers.execute { receive(opened) }
                        } catch (_: RejectedExecutionException) {
                            shutdown()
                            return@execute
                        }
                        while (running && !closed) {
                            val payload = pending.poll(1, TimeUnit.SECONDS) ?: continue
                            if (!running || closed) break
                            opened.send(payload)
                        }
                    } catch (_: Exception) {
                        // A refused/failed relay never falls back to direct UDP.
                    } finally {
                        shutdown()
                    }
                }
            } catch (_: RejectedExecutionException) {
                shutdown()
            }
        }

        private fun receive(opened: FoxDatagramPipe) {
            try {
                while (running && !closed) {
                    val payload = opened.receive() ?: break
                    // The stack does not implement IP fragmentation; never
                    // emit a packet larger than the configured tunnel MTU.
                    if (payload.size > mtu - 28) continue
                    synchronized(lock) {
                        if (closed || !running) return
                        idleAt = System.currentTimeMillis()
                        replyUdp(target, appPort, remotePort, payload)
                    }
                }
            } catch (_: Exception) {
            } finally {
                shutdown()
            }
        }

        fun shutdownIfIdle(limit: Long) = close(limit)
        fun shutdown() = close(null)

        private fun close(idleLimit: Long?) {
            val old = synchronized(lock) {
                if (closed || (idleLimit != null && idleAt >= idleLimit)) return
                closed = true
                relay.also { relay = null }
            }
            synchronized(udpLock) {
                if (udpFlows[key] === this) udpFlows.remove(key)
            }
            pending.clear()
            try { old?.close() } catch (_: Exception) { }
        }
    }

    internal fun activeDatagrams(): Int = synchronized(udpLock) { udpFlows.size }

    private fun learn(query: ByteArray, answer: ByteArray) {
        val name = questionName(query) ?: return
        for (address in addresses(answer)) {
            if (address != 0) names[address] = name
        }
    }

    private fun questionName(query: ByteArray): String? {
        if (query.size < 13) return null
        val out = StringBuilder()
        var index = 12
        while (index < query.size) {
            val size = query[index].toInt() and 0xFF
            if (size == 0) break
            if (size > 63 || index + 1 + size > query.size) return null
            if (out.isNotEmpty()) out.append('.')
            out.append(String(query, index + 1, size, Charsets.UTF_8))
            index += size + 1
        }
        return if (out.isEmpty()) null else out.toString()
    }

    private fun addresses(answer: ByteArray): List<Int> {
        if (answer.size < 12) return emptyList()
        val found = mutableListOf<Int>()
        var questions = u16(answer, 4)
        var count = u16(answer, 6)
        var index = 12
        while (questions > 0) {
            index = skipName(answer, index)
            index += 4
            questions--
        }
        while (count > 0 && index + 12 <= answer.size) {
            index = skipName(answer, index)
            if (index + 10 > answer.size) return found
            val type = u16(answer, index)
            val size = u16(answer, index + 8)
            index += 10
            if (index + size > answer.size) return found
            if (type == 1 && size == 4) found.add(i32(answer, index))
            index += size
            count--
        }
        return found
    }

    private fun skipName(bytes: ByteArray, from: Int): Int {
        var index = from
        while (index < bytes.size) {
            val size = bytes[index].toInt() and 0xFF
            if (size == 0) return index + 1
            if (size and 0xC0 == 0xC0) return index + 2
            index += size + 1
        }
        return index
    }

    private fun replyUdp(destination: Int, destinationPort: Int, sourcePort: Int, payload: ByteArray) {
        val total = 28 + payload.size
        val packet = ByteArray(total)
        packet[0] = 0x45
        packet[8] = 64
        packet[9] = UDP.toByte()
        putI32(packet, 12, destination)
        putI32(packet, 16, local)
        putU16(packet, 2, total)
        putU16(packet, 4, stamp.incrementAndGet() and 0xFFFF)
        putU16(packet, 6, 0x4000)
        putU16(packet, 10, fold(sum(packet, 0, 20)))
        putU16(packet, 20, sourcePort)
        putU16(packet, 22, destinationPort)
        putU16(packet, 24, 8 + payload.size)
        System.arraycopy(payload, 0, packet, 28, payload.size)
        val check = fold(pseudo(destination, local, UDP, 8 + payload.size) + sum(packet, 20, 8 + payload.size))
        putU16(packet, 26, if (check == 0) 0xFFFF else check)
        emit(packet)
        meter(true, payload.size.toLong())
    }

    private fun unreachable(destination: Int, query: ByteArray, total: Int) {
        val quoted = minOf(total, 28)
        val packet = ByteArray(28 + quoted)
        packet[0] = 0x45
        packet[8] = 64
        packet[9] = 1
        putI32(packet, 12, destination)
        putI32(packet, 16, local)
        putU16(packet, 2, packet.size)
        putU16(packet, 4, stamp.incrementAndGet() and 0xFFFF)
        putU16(packet, 10, fold(sum(packet, 0, 20)))
        packet[20] = 3
        packet[21] = 3
        System.arraycopy(query, 0, packet, 28, quoted)
        putU16(packet, 22, fold(sum(packet, 20, 8 + quoted)))
        emit(packet)
    }

    private fun key(appPort: Int, remote: Int, remotePort: Int): Long =
        (appPort.toLong() shl 48) or (remotePort.toLong() shl 32) or (remote.toLong() and MASK)

    private fun send(
        destination: Int,
        destinationPort: Int,
        sourcePort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        data: ByteArray?,
        offset: Int,
        length: Int,
        options: ByteArray? = null
    ) {
        val optionLength = options?.size ?: 0
        val tcpLength = 20 + optionLength + length
        val total = 20 + tcpLength
        val packet = ByteArray(total)
        packet[0] = 0x45
        packet[8] = 64
        packet[9] = TCP.toByte()
        putI32(packet, 12, destination)
        putI32(packet, 16, local)
        putU16(packet, 2, total)
        putU16(packet, 4, stamp.incrementAndGet() and 0xFFFF)
        putU16(packet, 6, 0x4000)
        putU16(packet, 10, fold(sum(packet, 0, 20)))
        putU16(packet, 20, destinationPort)
        putU16(packet, 22, sourcePort)
        putU32(packet, 24, seq)
        putU32(packet, 28, ack)
        packet[32] = (((20 + optionLength) / 4) shl 4).toByte()
        packet[33] = flags.toByte()
        putU16(packet, 34, window.coerceIn(0, 65535))
        if (optionLength > 0 && options != null) System.arraycopy(options, 0, packet, 40, optionLength)
        if (length > 0 && data != null) System.arraycopy(data, offset, packet, 40 + optionLength, length)
        val check = fold(pseudo(destination, local, TCP, tcpLength) + sum(packet, 20, tcpLength))
        putU16(packet, 36, if (check == 0) 0xFFFF else check)
        emit(packet)
        if (length > 0) meter(true, length.toLong())
    }

    private fun emit(packet: ByteArray) {
        try {
            synchronized(outgoing) { outgoing.write(packet) }
        } catch (e: Exception) {
        }
    }

    private fun pseudo(source: Int, target: Int, protocol: Int, length: Int): Int =
        ((source ushr 16) and 0xFFFF) + (source and 0xFFFF) +
            ((target ushr 16) and 0xFFFF) + (target and 0xFFFF) + protocol + length

    private fun sum(bytes: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            total += ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < end) total += (bytes[index].toInt() and 0xFF) shl 8
        return total
    }

    private fun fold(value: Int): Int {
        var total = value
        while (total shr 16 != 0) total = (total and 0xFFFF) + (total shr 16)
        return total.inv() and 0xFFFF
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
        }
    }

    private fun u16(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, index: Int): Long =
        ((bytes[index].toLong() and 0xFF) shl 24) or ((bytes[index + 1].toLong() and 0xFF) shl 16) or
            ((bytes[index + 2].toLong() and 0xFF) shl 8) or (bytes[index + 3].toLong() and 0xFF)

    private fun i32(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xFF) shl 24) or ((bytes[index + 1].toInt() and 0xFF) shl 16) or
            ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)

    private fun putU16(bytes: ByteArray, index: Int, value: Int) {
        bytes[index] = ((value ushr 8) and 0xFF).toByte()
        bytes[index + 1] = (value and 0xFF).toByte()
    }

    private fun putU32(bytes: ByteArray, index: Int, value: Long) {
        bytes[index] = ((value ushr 24) and 0xFF).toByte()
        bytes[index + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[index + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[index + 3] = (value and 0xFF).toByte()
    }

    private fun putI32(bytes: ByteArray, index: Int, value: Int) {
        bytes[index] = ((value ushr 24) and 0xFF).toByte()
        bytes[index + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[index + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[index + 3] = (value and 0xFF).toByte()
    }

    private fun address(value: Int): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"

    private fun gap(newer: Long, older: Long): Long = (newer - older) and MASK

    private class Chunk(val bytes: ByteArray, val size: Int)

    private fun chunkOf(packet: ByteArray, offset: Int, length: Int): Chunk {
        if (length <= CHUNK_SIZE) {
            val bytes = chunkPool.poll() ?: ByteArray(CHUNK_SIZE)
            System.arraycopy(packet, offset, bytes, 0, length)
            return Chunk(bytes, length)
        }
        return Chunk(packet.copyOfRange(offset, offset + length), length)
    }

    private fun release(chunk: Chunk) {
        if (chunk.bytes.size == CHUNK_SIZE && chunkPool.size < 64) chunkPool.offer(chunk.bytes)
    }

    private inner class Flow(
        private val key: Long,
        private val appPort: Int,
        private val remote: Int,
        private val remotePort: Int,
        @Volatile var expected: Long,
        private val peerShift: Int,
        private val peerMss: Int
    ) {
        @Volatile var sent: Long = System.nanoTime() and MASK
        @Volatile private var acknowledged: Long = sent
        @Volatile private var peer: Long = 65535
        @Volatile var idleAt: Long = System.currentTimeMillis()
        @Volatile private var closed = false
        @Volatile private var pipe: FoxPipe? = null
        private val queue = ArrayDeque<Chunk>()
        private val pending = HashMap<Int, Chunk>()
        private val sendTimes = HashMap<Long, Long>()
        private var rttEwma = 0L
        private val lock = Object()
        private var queued = 0
        private var appClosed = false
        private var readFinished = false
        private var writeFinished = false

        private fun available(): Int {
            val remaining = (UPLOAD_CAP - queued).coerceAtLeast(0)
            return if (remaining < mss) 0 else remaining
        }

        fun window(): Int = synchronized(lock) { (available() ushr WINDOW_SHIFT).coerceIn(0, 65535) }

        fun push(packet: ByteArray, offset: Int, length: Int, sequence: Long): Boolean = synchronized(lock) {
            if (closed || appClosed || length == 0) return@synchronized false
            if (sequence == expected) {
                if (length > UPLOAD_CAP - queued || globalQueued.get() + length > GLOBAL_QUEUE_CAP) {
                    return@synchronized false
                }
                queue.addLast(chunkOf(packet, offset, length))
                queued += length
                globalQueued.addAndGet(length.toLong())
                expected = (expected + length) and MASK
                // Release any out-of-order segments this one completed.
                while (true) {
                    val next = pending.remove(expected.toInt()) ?: break
                    queue.addLast(next)
                    expected = (expected + next.size) and MASK
                }
                lock.notifyAll()
                true
            } else {
                // A small, bounded reorder window: near-future segments are kept
                // instead of forcing a retransmit of everything after the hole.
                val ahead = gap(sequence, expected)
                if (ahead in 1..0x40000000L && pending.size < MAX_PENDING &&
                    length <= UPLOAD_CAP - queued && globalQueued.get() + length <= GLOBAL_QUEUE_CAP
                ) {
                    val previous = pending.put(sequence.toInt(), chunkOf(packet, offset, length))
                    if (previous != null) {
                        queued -= previous.size
                        globalQueued.addAndGet(-previous.size.toLong())
                        release(previous)
                    }
                    queued += length
                    globalQueued.addAndGet(length.toLong())
                    true
                } else false
            }
        }

        fun confirm(ack: Long, window: Int) {
            synchronized(lock) {
                val advance = gap(ack, acknowledged)
                val outstanding = gap(sent, acknowledged)
                if (advance < 0x80000000L && advance <= outstanding) {
                    acknowledged = ack
                    val nowMs = System.currentTimeMillis()
                    val iterator = sendTimes.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (gap(ack, entry.key) in 1..0x7fffffffL) {
                            iterator.remove()
                            val sample = nowMs - entry.value
                            if (sample in 1..60000) {
                                rttEwma = if (rttEwma <= 0) sample else (4 * rttEwma + sample) / 5
                            }
                        }
                    }
                }
                peer = (window.toLong() shl peerShift).coerceAtMost(1L shl 20)
                lock.notifyAll()
            }
        }

        fun finish(sequence: Long) {
            synchronized(lock) {
                if (closed || appClosed || sequence != expected) return
                expected = (expected + 1) and MASK
                appClosed = true
                lock.notifyAll()
            }
        }

        fun begin() {
            val destination = address(remote)
            val host = names[remote] ?: destination
            val open: () -> FoxPipe = if (remotePort == 53) {
                val scope = try { prepareDns?.invoke() } catch (_: Exception) { null }
                if (prepareDns != null && (scope == null || !scope.isCurrent())) {
                    terminate()
                    return
                }
                { dnsPipe(scope) }
            } else {
                try {
                    prepareDial?.invoke(host, destination, remotePort) ?: { dial(host, destination, remotePort) }
                } catch (e: Exception) {
                    if (!closed) send(remote, remotePort, appPort, sent, expected, RST or ACK, 0, null, 0, 0)
                    flows.remove(key, this)
                    return
                }
            }
            workers.execute {
                if (closed) return@execute
                val link = try {
                    open()
                } catch (e: Exception) {
                    if (!closed) send(remote, remotePort, appPort, sent, expected, RST or ACK, 0, null, 0, 0)
                    flows.remove(key, this)
                    return@execute
                }
                val accepted = synchronized(lock) {
                    if (closed) false else {
                        pipe = link
                        true
                    }
                }
                if (!accepted) {
                    try { link.close() } catch (_: Exception) { }
                    return@execute
                }
                workers.execute { drain(link) }
                receive(link)
            }
        }

        private fun drain(link: FoxPipe) {
            while (true) {
                var reopened = false
                val chunk = synchronized(lock) {
                    while (queue.isEmpty() && !closed && !appClosed) lock.wait(180000)
                    when {
                        closed -> null
                        queue.isNotEmpty() -> queue.removeFirst().also {
                            val before = available()
                            queued -= it.size
                            globalQueued.addAndGet(-it.size.toLong())
                            reopened = before == 0 && available() > 0
                            lock.notifyAll()
                        }
                        else -> null
                    }
                } ?: break
                if (reopened) send(remote, remotePort, appPort, sent, expected, ACK, window(), null, 0, 0)
                var offset = 0
                while (offset < chunk.size) {
                    val size = minOf(mss, chunk.size - offset)
                    try {
                        link.write(chunk.bytes, offset, size)
                    } catch (e: Exception) {
                        release(chunk)
                        terminate()
                        return
                    }
                    offset += size
                }
                release(chunk)
            }
            if (appClosed && !closed) {
                try {
                    link.finishWrite()
                    val done = synchronized(lock) {
                        writeFinished = true
                        readFinished
                    }
                    if (done) flowDone()
                } catch (_: Exception) {
                    terminate()
                }
            }
        }

        private fun receive(link: FoxPipe) {
            val buffer = receivePool.poll() ?: ByteArray(RECEIVE_SIZE)
            try {
                receiveWith(link, buffer)
            } finally {
                if (receivePool.size < 16) receivePool.offer(buffer)
            }
        }

        private fun receiveWith(link: FoxPipe, buffer: ByteArray) {
            while (true) {
                val size = try {
                    link.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    -1
                }
                if (size < 0) break
                if (size == 0) continue
                var offset = 0
                while (offset < size) {
                    val piece = minOf(peerMss, size - offset)
                    if (!await(piece.toLong())) {
                        terminate()
                        return
                    }
                    synchronized(lock) {
                        if (sendTimes.size >= 64) sendTimes.clear()
                        sendTimes[sent] = System.currentTimeMillis()
                    }
                    send(remote, remotePort, appPort, sent, expected, ACK or PSH, window(), buffer, offset, piece)
                    synchronized(lock) { sent = (sent + piece) and MASK }
                    offset += piece
                }
            }
            if (synchronized(lock) { closed }) return
            send(remote, remotePort, appPort, sent, expected, FIN or ACK, window(), null, 0, 0)
            val done = synchronized(lock) {
                sent = (sent + 1) and MASK
                readFinished = true
                writeFinished
            }
            if (done) flowDone()
        }

        private fun await(extra: Long): Boolean {
            // Scale the stall budget with the measured RTT; a dead path fails
            // in a few seconds instead of always burning the full timeout.
            val rtt = synchronized(lock) { rttEwma }
            val budget = if (rtt <= 0) 25000L else (3 * rtt).coerceIn(3000L, 25000L)
            val deadline = System.currentTimeMillis() + budget
            synchronized(lock) {
                while (true) {
                    val flight = gap(sent + extra, acknowledged)
                    val allowed = minOf(peer, FLIGHT_CAP.toLong())
                    if (flight <= allowed) return true
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0 || closed) return false
                    lock.wait(left)
                }
            }
        }

        private fun discard() {
            while (queue.isNotEmpty()) {
                val chunk = queue.removeFirst()
                queued -= chunk.size
                globalQueued.addAndGet(-chunk.size.toLong())
                release(chunk)
            }
            for (chunk in pending.values) {
                queued -= chunk.size
                globalQueued.addAndGet(-chunk.size.toLong())
                release(chunk)
            }
            pending.clear()
        }

        private fun flowDone() {
            synchronized(lock) { closed = true; discard(); lock.notifyAll() }
            try {
                pipe?.close()
            } catch (_: Exception) {
            }
            flows.remove(key, this)
        }

        fun shutdown() {
            synchronized(lock) { closed = true; discard(); lock.notifyAll() }
            try {
                pipe?.close()
            } catch (_: Exception) {
            }
            flows.remove(key, this)
        }

        private fun terminate() {
            send(remote, remotePort, appPort, sent, expected, RST or ACK, 0, null, 0, 0)
            shutdown()
        }

        private fun address(value: Int): String =
            "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
    }
}

class FoxResolver(
    private val proxyPort: Int,
    private val request: ((String, ByteArray) -> ByteArray?)? = null,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val endpoints: List<String> = DnsPolicy.endpoints(DnsPolicy.AUTO, "", false),
    private val raceHeadStartMs: Long = RACE_HEAD_START_MS
) {

    private data class LookupKey(val question: String, val scope: Any?)
    private class Entry(val body: ByteArray, val storedAt: Long, val expires: Long)
    private class LookupFlight {
        val finished = CountDownLatch(1)
        @Volatile var body: ByteArray? = null
    }

    private val cache = ConcurrentHashMap<LookupKey, Entry>()
    private val flights = ConcurrentHashMap<LookupKey, LookupFlight>()
    private val slots = Semaphore(8)

    fun answer(query: ByteArray, scope: FoxDnsScope? = null): ByteArray? {
        if (!scopeCurrent(scope)) return null
        if (query.size < 17 || query[2].toInt() and 0x80 != 0 || short(query, 4) != 1 || question(query) == null) return null
        val questionEnd = questionEnd(query, 12)
        if (questionEnd < 0 || questionEnd + 4 > query.size) return null
        val type = questionType(query)
        if (type == 28 || type == 65) return if (scopeCurrent(scope)) empty(query) else null
        // Different attempts never join an older in-flight request or consume
        // an answer cached by that request after the owner changed.
        val key = LookupKey(String(query, 2, query.size - 2, Charsets.ISO_8859_1), scope?.key)
        cached(key, query)?.let { return if (scopeCurrent(scope)) it else null }

        // Share even uncacheable responses and failures among callers already
        // waiting for this lookup. A later request can still retry a failure;
        // it is never installed as a DNS cache entry.
        val mine = LookupFlight()
        val existing = flights.putIfAbsent(key, mine)
        if (existing != null) {
            val complete = try {
                existing.finished.await(35, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!scopeCurrent(scope)) return null
            return if (complete) existing.body?.let { retag(query, it) } ?: failure(query) else failure(query)
        }
        var result: ByteArray? = null
        try {
            // The previous flight may have filled the cache between our first
            // cache check and becoming the next leader.
            if (!scopeCurrent(scope)) return null
            result = cached(key, query)
            if (result == null) {
                val started = now()
                val body = exchange(query, scope)
                if (body != null && scopeCurrent(scope) && valid(query, body)) {
                    store(key, body, started)
                    result = body
                }
            }
            if (!scopeCurrent(scope)) return null
            return result?.let { retag(query, it) } ?: failure(query)
        } finally {
            mine.body = result
            mine.finished.countDown()
            flights.remove(key, mine)
        }
    }

    private fun scopeCurrent(scope: FoxDnsScope?): Boolean = scope?.isCurrent() ?: true

    private fun cached(key: LookupKey, query: ByteArray): ByteArray? {
        val hit = cache[key] ?: return null
        val time = now()
        if (time < hit.expires) return retag(query, hit.body, (time - hit.storedAt).coerceAtLeast(0) / 1000)
        cache.remove(key, hit)
        return null
    }

    private fun store(key: LookupKey, body: ByteArray, time: Long) {
        val lifetime = when (body[3].toInt() and 0x0F) {
            0 -> ttl(body)
            3 -> NEGATIVE_TTL_MS
            else -> 0L
        }
        if (lifetime <= 0) return
        if (cache.size >= 512) {
            cache.entries.removeAll { it.value.expires <= time }
            if (cache.size >= 512) cache.clear()
        }
        // Rewrite the record TTLs to the enforced lifetime so cache hits can
        // count the age down against the value that actually bounds the entry.
        cache[key] = Entry(normalizeTtl(body, lifetime / 1000), time, time + lifetime)
    }

    private fun normalizeTtl(body: ByteArray, seconds: Long): ByteArray {
        if (body.size < 12) return body
        val copy = body.copyOf()
        var at = 12
        repeat(short(copy, 4)) {
            at = questionEnd(copy, at)
            if (at < 0 || at + 4 > copy.size) return body
            at += 4
        }
        val records = short(copy, 6) + short(copy, 8) + short(copy, 10)
        if (records > 512) return body
        repeat(records) {
            at = questionEnd(copy, at)
            if (at < 0 || at + 10 > copy.size) return body
            val type = short(copy, at)
            val size = short(copy, at + 8)
            if (at + 10 + size > copy.size) return body
            if (type != 41) {
                for (part in 0..3) copy[at + 4 + part] = (seconds ushr (24 - part * 8)).toByte()
            }
            at += 10 + size
        }
        return copy
    }

    fun directAddress(host: String, scope: FoxDnsScope? = null): String? {
        if (!scopeCurrent(scope)) return null
        val raw = host.trim().removeSurrounding("[", "]").trimEnd('.')
        if (raw.isEmpty()) return null
        if (raw.equals("localhost", true) || raw.endsWith(".localhost", true)) return "127.0.0.1"
        if (raw.endsWith(".local", true)) return null
        if (raw.contains(':')) {
            if (!raw.matches(Regex("[0-9a-fA-F:.]+"))) return null
            return try { java.net.InetAddress.getByName(raw).hostAddress } catch (_: Exception) { null }
        }
        val parts = raw.split('.')
        if (parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            return if (numbers.all { it in 0..255 }) numbers.joinToString(".") else null
        }
        val ascii = try { java.net.IDN.toASCII(raw, java.net.IDN.USE_STD3_ASCII_RULES).lowercase(java.util.Locale.ROOT) }
            catch (_: Exception) { return null }
        val labels = ascii.split('.')
        if (ascii.length > 253 || labels.any { it.isEmpty() || it.length > 63 }) return null
        val id = java.util.concurrent.ThreadLocalRandom.current().nextInt(65536)
        val query = ByteArrayOutputStream().apply {
            write(byteArrayOf((id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                write(bytes.size)
                write(bytes)
            }
            write(byteArrayOf(0, 0, 1, 0, 1))
        }.toByteArray()
        val body = answer(query, scope) ?: return null
        if (!scopeCurrent(scope) || body.size < 12 || body[3].toInt() and 15 != 0) return null
        var at = questionEnd(body, 12)
        if (at < 0 || at + 4 > body.size) return null
        at += 4
        repeat(short(body, 6).coerceAtMost(256)) {
            at = questionEnd(body, at)
            if (at < 0 || at + 10 > body.size) return null
            val type = short(body, at)
            val recordClass = short(body, at + 2)
            val size = short(body, at + 8)
            at += 10
            if (at + size > body.size) return null
            if (type == 1 && recordClass == 1 && size == 4) {
                val address = (0..3).joinToString(".") { index -> (body[at + index].toInt() and 255).toString() }
                if (address != "0.0.0.0") return address
            }
            at += size
        }
        return null
    }

    private fun retag(query: ByteArray, body: ByteArray, age: Long = 0): ByteArray {
        if (body.size < 12 || query.size < 2) return body
        val copy = body.copyOf()
        copy[0] = query[0]
        copy[1] = query[1]
        if (age == 0L) return copy
        var at = 12
        repeat(short(copy, 4)) {
            at = questionEnd(copy, at)
            if (at < 0 || at + 4 > copy.size) return copy
            at += 4
        }
        val records = short(copy, 6) + short(copy, 8) + short(copy, 10)
        if (records > 512) return copy
        repeat(records) {
            at = questionEnd(copy, at)
            if (at < 0 || at + 10 > copy.size) return copy
            val type = short(copy, at)
            val ttl = ((copy[at + 4].toLong() and 255) shl 24) or
                ((copy[at + 5].toLong() and 255) shl 16) or
                ((copy[at + 6].toLong() and 255) shl 8) or (copy[at + 7].toLong() and 255)
            val size = short(copy, at + 8)
            if (at + 10 + size > copy.size) return copy
            if (type != 41) {
                val remaining = (ttl - age).coerceAtLeast(0)
                for (part in 0..3) copy[at + 4 + part] = (remaining ushr (24 - part * 8)).toByte()
            }
            at += 10 + size
        }
        return copy
    }

    fun truncate(query: ByteArray, answer: ByteArray, maximum: Int): ByteArray {
        if (answer.size <= maximum) return answer
        val end = questionEnd(query, 12)
        if (end < 0 || end + 4 > query.size || end + 4 > maximum) return ByteArray(0)
        val copy = query.copyOf(end + 4)
        copy[2] = (answer[2].toInt() or 0x82).toByte()
        copy[3] = answer[3]
        putShort(copy, 6, 0)
        putShort(copy, 8, 0)
        putShort(copy, 10, 0)
        return copy
    }

    fun unavailable(query: ByteArray): ByteArray? {
        if (query.size < 17 || short(query, 4) != 1) return null
        val end = questionEnd(query, 12)
        if (end < 0 || end + 4 > query.size) return null
        return failure(query)
    }

    private fun empty(query: ByteArray): ByteArray = response(query, 0)

    private fun failure(query: ByteArray): ByteArray = response(query, 2)

    private fun response(query: ByteArray, code: Int): ByteArray {
        val end = questionEnd(query, 12)
        val copy = if (end >= 0 && end + 4 <= query.size) query.copyOf(end + 4) else query.copyOf()
        copy[2] = ((copy[2].toInt() or 0x80) and 0xFF).toByte()
        copy[3] = ((copy[3].toInt() and 0x70) or 0x80 or code).toByte()
        putShort(copy, 6, 0)
        putShort(copy, 8, 0)
        putShort(copy, 10, 0)
        return copy
    }

    /**
     * The primary endpoint gets a short head start; if it has not answered the
     * rest race in parallel and the first valid reply wins. A slow or blocked
     * endpoint therefore costs milliseconds, not its whole timeout.
     */
    private fun exchange(query: ByteArray, scope: FoxDnsScope?): ByteArray? {
        if (endpoints.isEmpty() || !scopeCurrent(scope)) return null
        if (!slots.tryAcquire(2, TimeUnit.SECONDS)) return null
        try {
            if (!scopeCurrent(scope)) return null
            val winner = AtomicReference<ByteArray?>(null)
            val settled = CountDownLatch(1)
            val remaining = AtomicInteger(endpoints.size)
            fun settle(body: ByteArray?) {
                if (body != null && winner.compareAndSet(null, body)) settled.countDown()
                if (remaining.decrementAndGet() == 0) settled.countDown()
            }
            val rest = endpoints.drop(1)
            val gate = CountDownLatch(1)
            val stagger = thread(isDaemon = true, name = "fox-dns-race") {
                try {
                    gate.await(raceHeadStartMs, TimeUnit.MILLISECONDS)
                    if (winner.get() != null || !scopeCurrent(scope)) {
                        repeat(rest.size) { if (remaining.decrementAndGet() == 0) settled.countDown() }
                        return@thread
                    }
                    val others = ArrayList<Thread>(rest.size)
                    for (url in rest) {
                        if (winner.get() != null || !scopeCurrent(scope)) break
                        others.add(thread(isDaemon = true) { settle(attempt(url, query, scope)) })
                    }
                    repeat(rest.size - others.size) { if (remaining.decrementAndGet() == 0) settled.countDown() }
                    for (worker in others) worker.join(30000)
                } catch (_: Exception) {
                }
            }
            thread(isDaemon = true, name = "fox-dns-first") {
                val body = attempt(endpoints[0], query, scope)
                settle(body)
                if (body == null) gate.countDown()
            }
            settled.await(30, TimeUnit.SECONDS)
            return if (scopeCurrent(scope)) winner.get() else null
        } finally {
            slots.release()
        }
    }

    private fun attempt(url: String, query: ByteArray, scope: FoxDnsScope?): ByteArray? {
        if (!scopeCurrent(scope)) return null
        val body = try { over(url, query) } catch (_: Exception) { null }
        return if (scopeCurrent(scope) && body != null && valid(query, body) && body[3].toInt() and 0x0F != 2) body else null
    }

    private fun over(url: String, query: ByteArray): ByteArray? {
        request?.let { return it(url, query) }
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
        val connection = URL(url).openConnection(proxy) as HttpsURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/dns-message")
            connection.setRequestProperty("accept", "application/dns-message")
            connection.outputStream.use { it.write(query) }
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > 65535) return null
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            return if (body.size in 12..65535) body else null
        } finally {
            connection.disconnect()
        }
    }

    private fun valid(query: ByteArray, body: ByteArray): Boolean {
        if (body.size < 12 || body[0] != query[0] || body[1] != query[1] || body[2].toInt() and 0x80 == 0) return false
        if (short(body, 4) != 1 || short(query, 4) != 1) return false
        val queryEnd = questionEnd(query, 12)
        val bodyEnd = questionEnd(body, 12)
        if (queryEnd < 0 || bodyEnd < 0 || queryEnd + 4 > query.size || bodyEnd + 4 > body.size) return false
        return query.copyOfRange(12, queryEnd + 4).contentEquals(body.copyOfRange(12, bodyEnd + 4))
    }

    private fun ttl(body: ByteArray): Long {
        if (body.size < 12 || body[3].toInt() and 0x0F != 0) return 0
        val count = short(body, 6)
        if (count !in 1..256) return 0
        var at = 12
        repeat(short(body, 4)) {
            at = questionEnd(body, at)
            if (at < 0 || at + 4 > body.size) return 0
            at += 4
        }
        var minimum = Long.MAX_VALUE
        repeat(count) {
            at = questionEnd(body, at)
            if (at < 0 || at + 10 > body.size) return 0
            val seconds = ((body[at + 4].toLong() and 255) shl 24) or
                ((body[at + 5].toLong() and 255) shl 16) or
                ((body[at + 6].toLong() and 255) shl 8) or (body[at + 7].toLong() and 255)
            val size = short(body, at + 8)
            if (at + 10 + size > body.size) return 0
            minimum = minOf(minimum, seconds)
            at += 10 + size
        }
        // Honor the server's TTL but stay inside sane bounds: a 60 second floor
        // avoids pointless refetches, a 5 minute cap bounds stale answers.
        return minimum.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS) * 1000L
    }

    private fun questionEnd(data: ByteArray, start: Int): Int {
        var at = start
        var steps = 0
        while (at < data.size && steps++ < 128) {
            val size = data[at].toInt() and 255
            if (size == 0) return at + 1
            if (size and 0xC0 == 0xC0) return if (at + 1 < data.size) at + 2 else -1
            if (size > 63 || at + 1 + size > data.size) return -1
            at += 1 + size
        }
        return -1
    }

    private fun short(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)

    private fun question(query: ByteArray): String? {
        val out = StringBuilder()
        var index = 12
        while (index < query.size) {
            val size = query[index].toInt() and 0xFF
            if (size == 0) break
            if (size > 63 || index + 1 + size > query.size) return null
            if (out.isNotEmpty()) out.append('.')
            out.append(String(query, index + 1, size, Charsets.UTF_8).lowercase())
            index += size + 1
        }
        return if (out.isEmpty()) null else out.toString()
    }

    private fun questionType(query: ByteArray): Int {
        var index = 12
        while (index < query.size) {
            val size = query[index].toInt() and 0xFF
            if (size == 0) {
                index += 1
                break
            }
            index += size + 1
        }
        if (index + 2 > query.size) return 1
        return ((query[index].toInt() and 0xFF) shl 8) or (query[index + 1].toInt() and 0xFF)
    }

    private fun putShort(bytes: ByteArray, index: Int, value: Int) {
        if (index + 1 >= bytes.size) return
        bytes[index] = ((value ushr 8) and 0xFF).toByte()
        bytes[index + 1] = (value and 0xFF).toByte()
    }
}
