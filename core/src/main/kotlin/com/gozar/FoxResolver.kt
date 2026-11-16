package com.gozar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type

private const val MIN_TTL_SECONDS = 60L
private const val MAX_TTL_SECONDS = 300L
private const val NEGATIVE_TTL_MS = 30_000L
private const val RACE_HEAD_START_MS = 250L

/** Identifies a DNS owner's attempt and fails closed when it is no longer current. */
class FoxDnsScope(val key: Any, private val valid: () -> Boolean) {
    fun isCurrent(): Boolean = try { valid() } catch (_: Exception) { false }
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
        val name = runCatching { Name.fromString("$ascii.") }.getOrNull() ?: return null
        val queryRecord = Record.newRecord(name, Type.A, DClass.IN)
        val query = Message.newQuery(queryRecord).toWire()
        val body = answer(query, scope) ?: return null
        if (!scopeCurrent(scope)) return null
        val parsed = runCatching { Message(body) }.getOrNull() ?: return null
        if (parsed.header.getRcode() != 0) return null
        for (record in parsed.getSection(Section.ANSWER)) {
            if (record.getType() == Type.A && record.getDClass() == DClass.IN) {
                val address = (record as ARecord).getAddress().hostAddress
                if (address != "0.0.0.0") return address
            }
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

    private fun response(query: ByteArray, code: Int): ByteArray = try {
        val sent = Message(query)
        val reply = Message(sent.header.getID())
        reply.header.setFlag(Flags.QR.toInt())
        if (sent.header.getFlag(Flags.RD.toInt())) reply.header.setFlag(Flags.RD.toInt())
        reply.header.setFlag(Flags.RA.toInt())
        reply.header.setOpcode(sent.header.getOpcode())
        reply.header.setRcode(code)
        sent.getQuestion()?.let { reply.addRecord(it, Section.QUESTION) }
        reply.toWire()
    } catch (_: Exception) {
        val end = questionEnd(query, 12)
        val copy = if (end >= 0 && end + 4 <= query.size) query.copyOf(end + 4) else query.copyOf()
        if (copy.size >= 4) {
            copy[2] = ((copy[2].toInt() or 0x80) and 0xFF).toByte()
            copy[3] = ((copy[3].toInt() and 0x70) or 0x80 or code).toByte()
            putShort(copy, 6, 0)
            putShort(copy, 8, 0)
            putShort(copy, 10, 0)
        }
        copy
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
        // DNS travels inside the tunnel: through the app's own loopback HTTP
        // proxy, exactly like before, now on OkHttp.
        val message = object : okhttp3.RequestBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength(): Long = query.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) {
                sink.write(query)
            }
        }
        val httpRequest = okhttp3.Request.Builder()
            .url(url)
            .post(message)
            .header("content-type", "application/dns-message")
            .header("accept", "application/dns-message")
            .build()
        return try {
            FoxHttp.builder(proxyPort, 8000).build().newCall(httpRequest).execute().use { response ->
                if (response.code != 200) return null
                val stream = response.body?.byteStream() ?: return null
                val body = FoxHttp.readBounded(stream, 65535)
                if (body.size in 12..65535) body else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun valid(query: ByteArray, body: ByteArray): Boolean {
        val sent = runCatching { Message(query) }.getOrNull() ?: return false
        val received = runCatching { Message(body) }.getOrNull() ?: return false
        if (!received.header.getFlag(Flags.QR.toInt())) return false
        if (received.header.getID() != sent.header.getID()) return false
        if (sent.getSection(Section.QUESTION).size != 1) return false
        if (received.getSection(Section.QUESTION).size != 1) return false
        val asked = sent.getQuestion() ?: return false
        val answered = received.getQuestion() ?: return false
        if (asked.getType() != answered.getType() || asked.getDClass() != answered.getDClass()) return false
        return asked.getName().toString().equals(answered.getName().toString(), true)
    }

    private fun ttl(body: ByteArray): Long {
        val message = runCatching { Message(body) }.getOrNull() ?: return 0
        if (message.header.getRcode() != 0) return 0
        val answers = message.getSection(Section.ANSWER)
        if (answers.isEmpty() || answers.size > 256) return 0
        val minimum = answers.minOf { it.getTTL() }
        // Honor the server's TTL but stay inside sane bounds: a 60 second floor
        // avoids pointless refetches, a 5 minute cap bounds stale answers.
        return minimum.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS) * 1000L
    }


    // Address-to-name learning, moved with the resolver: every valid answer the
    // resolver serves is fed back into this map by callers (the UDP DNS path and
    // the TCP DNS pipe), so flows can dial the owning hostname exactly like the
    // hand-rolled stack did. Bounded; a full table is dropped wholesale.
    private val nameLock = Any()
    private val names: MutableMap<Int, String> =
        java.util.Collections.synchronizedMap(object : LinkedHashMap<Int, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean =
                size > MAX_LEARNED_NAMES
        })

    /** Learn the A-record names carried by a query/answer pair. */
    fun learn(query: ByteArray, answer: ByteArray) {
        val name = questionName(query) ?: return
        val learned = addresses(answer)
        if (learned.isEmpty()) return
        synchronized(nameLock) {
            for (address in learned) {
                if (address != 0) names[address] = name
            }
        }
    }

    /** The hostname learned for an IPv4 address, or null when unknown. */
    fun nameOf(address: Int): String? = synchronized(nameLock) { names[address] }

    companion object {
        private const val MAX_LEARNED_NAMES = 4096
    }


    private fun questionName(query: ByteArray): String? = runCatching {
        val question = Message(query).getQuestion() ?: return null
        question.getName().toString().removeSuffix(".").ifEmpty { null }
    }.getOrNull()

    private fun addresses(answer: ByteArray): List<Int> = runCatching {
        Message(answer).getSection(Section.ANSWER)
            .filter { it.getType() == Type.A && it.getDClass() == DClass.IN }
            .map { record ->
                val bytes = (record as ARecord).getAddress().address
                ((bytes[0].toInt() and 255) shl 24) or ((bytes[1].toInt() and 255) shl 16) or
                    ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
            }
    }.getOrDefault(emptyList())

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

    private fun question(query: ByteArray): String? = runCatching {
        val question = Message(query).getQuestion() ?: return null
        question.getName().toString().removeSuffix(".").lowercase().ifEmpty { null }
    }.getOrNull()

    private fun questionType(query: ByteArray): Int = runCatching {
        Message(query).getQuestion()?.getType() ?: 1
    }.getOrDefault(1)

    private fun putShort(bytes: ByteArray, index: Int, value: Int) {
        if (index + 1 >= bytes.size) return
        bytes[index] = ((value ushr 8) and 0xFF).toByte()
        bytes[index + 1] = (value and 0xFF).toByte()
    }
}
