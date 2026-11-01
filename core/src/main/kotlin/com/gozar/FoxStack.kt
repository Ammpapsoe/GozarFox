package com.gozar

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

interface FoxPipe {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun finishWrite() = Unit
    fun close()
}

class FoxStack(
    private val incoming: InputStream,
    private val outgoing: OutputStream,
    private val local: Int,
    private val resolver: FoxResolver,
    private val dial: (String, String, Int) -> FoxPipe,
    private val meter: (Boolean, Long) -> Unit
) {

    companion object {
        const val MTU = 1400
        private const val MSS = MTU - 40
        private const val FIN = 0x01
        private const val SYN = 0x02
        private const val RST = 0x04
        private const val PSH = 0x08
        private const val ACK = 0x10
        private const val TCP = 6
        private const val UDP = 17
        private const val WINDOW = 64240
        private const val UPLOAD_CAP = 512 * 1024
        private const val FLIGHT_CAP = 256 * 1024
        private const val MASK = 0xFFFFFFFFL
    }

    private val flows = ConcurrentHashMap<Long, Flow>()
    private val names = ConcurrentHashMap<Int, String>()
    private val stamp = AtomicInteger(1)
    private val dnsWorkers = ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, ArrayBlockingQueue(128), { task ->
        Thread(task, "fox-dns").apply { isDaemon = true }
    })
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
            }
        }
    }

    fun stop() {
        running = false
        for (flow in flows.values) {
            try {
                flow.shutdown()
            } catch (_: Exception) {
            }
        }
        flows.clear()
        dnsWorkers.shutdownNow()
    }

    fun active(): Int = flows.size

    fun nameOf(address: Int): String? = names[address]

    internal fun dnsPipe(): FoxPipe = FoxDnsPipe(resolver, ::learn)

    private fun pump() {
        val buffer = ByteArray(65535)
        while (running) {
            val size = try {
                incoming.read(buffer)
            } catch (e: Exception) {
                break
            }
            if (size < 0) break
            if (size < 20 || (buffer[0].toInt() and 0xF0) != 0x40) continue
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
            val created = Flow(key, appPort, target, remotePort, (seq + 1) and MASK)
            flows[key] = created
            send(target, remotePort, appPort, created.sent, created.expected, SYN or ACK, WINDOW, null, 0, 0)
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

    private fun datagram(packet: ByteArray, header: Int, total: Int, target: Int) {
        val appPort = u16(packet, header)
        val serverPort = u16(packet, header + 2)
        val length = u16(packet, header + 4)
        val start = header + 8
        if (length < 20 || start + length - 8 > total) return
        if (serverPort != 53) {
            unreachable(target, packet, total)
            return
        }
        meter(false, length.toLong())
        val query = packet.copyOfRange(start, start + length - 8)
        try {
            dnsWorkers.execute {
                val answer = try {
                    resolver.answer(query)
                } catch (_: Exception) {
                    null
                } ?: return@execute
                learn(query, answer)
                val limited = resolver.truncate(query, answer, MTU - 28)
                if (limited.isNotEmpty()) replyUdp(target, appPort, serverPort, limited)
            }
        } catch (_: RejectedExecutionException) {
            val failure = resolver.unavailable(query)
            if (failure != null) replyUdp(target, appPort, serverPort, failure)
        }
    }

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
        length: Int
    ) {
        val tcpLength = 20 + length
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
        packet[32] = 0x50
        packet[33] = flags.toByte()
        putU16(packet, 34, window.coerceIn(0, 65535))
        if (length > 0 && data != null) System.arraycopy(data, offset, packet, 40, length)
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

    private inner class Flow(
        private val key: Long,
        private val appPort: Int,
        private val remote: Int,
        private val remotePort: Int,
        @Volatile var expected: Long
    ) {
        @Volatile var sent: Long = System.nanoTime() and MASK
        @Volatile private var acknowledged: Long = sent
        @Volatile private var peer: Int = WINDOW
        @Volatile var idleAt: Long = System.currentTimeMillis()
        @Volatile private var closed = false
        @Volatile private var pipe: FoxPipe? = null
        private val queue = ArrayDeque<ByteArray>()
        private val lock = Object()
        private var queued = 0
        private var appClosed = false
        private var readFinished = false
        private var writeFinished = false

        private fun available(): Int {
            val remaining = (UPLOAD_CAP - queued).coerceAtLeast(0)
            return if (remaining < MSS) 0 else minOf(remaining, WINDOW)
        }

        fun window(): Int = synchronized(lock) { available() }

        fun push(packet: ByteArray, offset: Int, length: Int, sequence: Long): Boolean = synchronized(lock) {
            if (closed || appClosed || sequence != expected || length > UPLOAD_CAP - queued) return@synchronized false
            queue.addLast(packet.copyOfRange(offset, offset + length))
            queued += length
            expected = (expected + length) and MASK
            lock.notifyAll()
            true
        }

        fun confirm(ack: Long, window: Int) {
            synchronized(lock) {
                val advance = gap(ack, acknowledged)
                val outstanding = gap(sent, acknowledged)
                if (advance < 0x80000000L && advance <= outstanding) acknowledged = ack
                peer = window
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
            thread(isDaemon = true, name = "fox-flow") {
                val destination = address(remote)
                val host = names[remote] ?: destination
                val link = try {
                    if (remotePort == 53) dnsPipe() else dial(host, destination, remotePort)
                } catch (e: Exception) {
                    if (!closed) send(remote, remotePort, appPort, sent, expected, RST or ACK, 0, null, 0, 0)
                    flows.remove(key, this)
                    return@thread
                }
                val accepted = synchronized(lock) {
                    if (closed) false else {
                        pipe = link
                        true
                    }
                }
                if (!accepted) {
                    try { link.close() } catch (_: Exception) { }
                    return@thread
                }
                thread(isDaemon = true, name = "fox-flow-up") { drain(link) }
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
                            reopened = before == 0 && available() > 0
                            lock.notifyAll()
                        }
                        else -> null
                    }
                } ?: break
                if (reopened) send(remote, remotePort, appPort, sent, expected, ACK, window(), null, 0, 0)
                var offset = 0
                while (offset < chunk.size) {
                    val size = minOf(MSS, chunk.size - offset)
                    try {
                        link.write(chunk, offset, size)
                    } catch (e: Exception) {
                        terminate()
                        return
                    }
                    offset += size
                }
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
            val buffer = ByteArray(32768)
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
                    val piece = minOf(MSS, size - offset)
                    if (!await(piece.toLong())) {
                        terminate()
                        return
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
            val deadline = System.currentTimeMillis() + 25000
            synchronized(lock) {
                while (true) {
                    val flight = gap(sent + extra, acknowledged)
                    val allowed = minOf(peer.toLong(), FLIGHT_CAP.toLong())
                    if (flight <= allowed) return true
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0 || closed) return false
                    lock.wait(left)
                }
            }
        }

        private fun flowDone() {
            synchronized(lock) { closed = true; lock.notifyAll() }
            try {
                pipe?.close()
            } catch (_: Exception) {
            }
            flows.remove(key, this)
        }

        fun shutdown() {
            synchronized(lock) { closed = true; lock.notifyAll() }
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
    private val endpoints: List<String> = DnsPolicy.endpoints(DnsPolicy.AUTO, "", false)
) {

    private class Entry(val body: ByteArray, val storedAt: Long, val expires: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val slots = Semaphore(8)

    fun answer(query: ByteArray): ByteArray? {
        if (query.size < 17 || query[2].toInt() and 0x80 != 0 || short(query, 4) != 1 || question(query) == null) return null
        val questionEnd = questionEnd(query, 12)
        if (questionEnd < 0 || questionEnd + 4 > query.size) return null
        val type = questionType(query)
        if (type == 28 || type == 65) return empty(query)
        val key = String(query, 2, query.size - 2, Charsets.ISO_8859_1)
        val time = now()
        val hit = cache[key]
        if (hit != null) {
            if (time < hit.expires) return retag(query, hit.body, (time - hit.storedAt).coerceAtLeast(0) / 1000)
            cache.remove(key, hit)
        }
        val body = exchange(query) ?: return failure(query)
        if (!valid(query, body)) return failure(query)
        val ttl = ttl(body)
        if (ttl > 0) {
            if (cache.size >= 512) {
                cache.entries.removeAll { it.value.expires <= time }
                if (cache.size >= 512) cache.clear()
            }
            cache[key] = Entry(body, time, time + ttl)
        }
        return retag(query, body)
    }

    fun directAddress(host: String): String? {
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
        val body = answer(query) ?: return null
        if (body.size < 12 || body[3].toInt() and 15 != 0) return null
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

    private fun exchange(query: ByteArray): ByteArray? {
        if (!slots.tryAcquire(2, TimeUnit.SECONDS)) return null
        try {
            for (url in endpoints) {
                val body = try { over(url, query) } catch (_: Exception) { null }
                if (body != null && valid(query, body) && body[3].toInt() and 0x0F != 2) return body
            }
            return null
        } finally {
            slots.release()
        }
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
        var minimum = 120L
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
        return minimum * 1000L
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
