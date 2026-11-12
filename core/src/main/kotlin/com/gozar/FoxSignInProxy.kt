package com.gozar

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A loopback CONNECT proxy for the sign-in WebView, and nothing else.
 *
 * Why it exists: a WebView left to itself dials the single address the
 * system resolver returns, with a plain ClientHello, and waits as long as
 * the network wants. On networks that blackhole or shape some of Mozilla's
 * addresses that means a page whose load never completes. Through this
 * proxy the same page gets three things the bare view does not:
 *
 *  - every address of a name is tried at once and the first answer wins,
 *    so one dead address cannot decide the outcome;
 *  - the TLS ClientHello is written in two fragments split inside the SNI
 *    name, so a middlebox matching the name in one packet sees none;
 *  - every wait is bounded, so a host that never answers becomes an error
 *    the screen can show, not a spinner that never stops.
 *
 * It is not a VPN and not a general proxy: it serves only while [start]
 * has been called and [stop] has not, only HTTPS ports, and only the
 * hosts in [allowed]. Anything else is refused with a status and closed.
 *
 * Pure JVM on purpose - the same race and bounds are exercised by unit
 * tests without a device.
 */
class FoxSignInProxy(
    private val allowed: Set<String> = SIGN_IN_HOSTS,
    private val tlsPort: Int = 443,
    private val dialBudgetMs: Long = DEFAULT_DIAL_BUDGET_MS,
    private val handshakeReadMs: Int = DEFAULT_HANDSHAKE_READ_MS,
    private val trace: (String) -> Unit = {}
) {
    companion object {
        /** The hosts a Mozilla sign-in page and its fonts really use. */
        val SIGN_IN_HOSTS: Set<String> = setOf(
            "accounts.firefox.com",
            "oauth.accounts.firefox.com",
            "fonts.gstatic.com",
            "fonts.googleapis.com"
        )

        const val DEFAULT_DIAL_BUDGET_MS: Long = 30_000L

        /** How long the independent resolvers may take before the race goes on without them. */
        const val RESOLVER_BUDGET_MS: Long = 4_000L
        const val DEFAULT_HANDSHAKE_READ_MS: Int = 30_000

        /** How many bytes of the SNI name stay in the first fragment. */
        private const val SNI_PREFIX = 3

        private const val MAX_REQUEST_BYTES = 8 * 1024
        private const val PUMP_BLOCK = 16 * 1024
    }

    private var server: ServerSocket? = null
    private val stopped = AtomicBoolean(false)
    private val tunnels = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    private var sequence = 0

    /** The loopback port, once [start] has run. */
    val port: Int
        get() = server?.localPort ?: error("the sign-in proxy is not listening")

    /**
     * Binds an ephemeral loopback port and accepts in the background.
     * Calling it twice keeps the first listener.
     */
    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val bound = ServerSocket()
        bound.reuseAddress = true
        // IPv4 loopback, named: the override rule says 127.0.0.1, so the
        // listener must not depend on which loopback the platform prefers.
        bound.bind(InetSocketAddress("127.0.0.1", 0))
        server = bound
        stopped.set(false)
        thread("accept") { acceptLoop(bound) }
        trace("listening port=${bound.localPort}")
        return bound.localPort
    }

    /**
     * Closes the listener and every open tunnel. Safe to call twice, and
     * safe to call from `onDestroy` inside a finally: a proxy that leaks
     * its port would make the next sign-in impossible.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try {
            server?.close()
        } catch (gone: IOException) {
            trace("stop listener ${gone.javaClass.simpleName}")
        } finally {
            server = null
        }
        tunnels.forEach { socket ->
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
        tunnels.clear()
        trace("stopped")
    }

    private fun acceptLoop(bound: ServerSocket) {
        while (!stopped.get()) {
            val client = try {
                bound.accept()
            } catch (_: IOException) {
                return
            }
            val id = ++sequence
            thread("tunnel-$id") { handle(client, id) }
        }
    }

    private fun handle(client: Socket, id: Int) {
        tunnels.add(client)
        try {
            client.soTimeout = handshakeReadMs
            val request = readRequest(client.getInputStream()) ?: run {
                trace("#$id handshake unreadable")
                return
            }
            val (host, targetPort) = request
            trace("#$id connect $host:$targetPort")
            if (targetPort != tlsPort) {
                trace("#$id refused port=$targetPort")
                answer(client, 403)
                return
            }
            if (!allowed.contains(host)) {
                trace("#$id blocked host=$host")
                answer(client, 403)
                return
            }
            val target = dial(host, targetPort, id) ?: run {
                answer(client, 504)
                return
            }
            tunnels.add(target)
            client.soTimeout = 0
            answer(client, 200)
            tunnel(client, target, host, id)
        } catch (broken: IOException) {
            trace("#$id closed ${broken.javaClass.simpleName}")
        } finally {
            try {
                client.close()
            } catch (_: IOException) {
            }
            tunnels.remove(client)
        }
    }

    /** `CONNECT host:port`, parsed; null when the request is not one. */
    private fun readRequest(input: InputStream): Pair<String, Int>? {
        val buffer = ByteArray(MAX_REQUEST_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val byte = input.read()
            if (byte < 0) return null
            buffer[filled++] = byte.toByte()
            if (filled >= 4 && endsHandshake(buffer, filled)) break
        }
        val head = String(buffer, 0, filled, Charsets.ISO_8859_1)
        val line = head.substringBefore("\r\n")
        val parts = line.split(" ")
        if (parts.size < 2 || !parts[0].equals("CONNECT", true)) return null
        val authority = parts[1]
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return null
        val host = authority.substring(0, colon).lowercase().trimEnd('.')
        val targetPort = authority.substring(colon + 1).toIntOrNull() ?: return null
        return host to targetPort
    }

    private fun endsHandshake(buffer: ByteArray, filled: Int): Boolean =
        buffer[filled - 4] == '\r'.code.toByte() &&
            buffer[filled - 3] == '\n'.code.toByte() &&
            buffer[filled - 2] == '\r'.code.toByte() &&
            buffer[filled - 1] == '\n'.code.toByte()

    /**
     * Races every address of the name at once inside the budget. The first
     * connection wins; the rest are closed behind it. Null when no address
     * answered in time, which the caller reports as 504.
     */
    internal fun dial(host: String, targetPort: Int, id: Int): Socket? {
        val system = try {
            InetAddress.getAllByName(host).toList()
        } catch (unknown: IOException) {
            trace("#$id name-unresolved host=$host")
            emptyList()
        }
        val extra = resolveIndependently(host, id)
        val addresses = (system + extra).distinctBy { it.hostAddress }
        if (addresses.isEmpty()) return null
        trace("#$id racing ${addresses.size} address(es) for $host")
        val deadline = System.currentTimeMillis() + dialBudgetMs
        val winner = AtomicReference<Socket?>()
        val latch = java.util.concurrent.CountDownLatch(1)
        val remaining = java.util.concurrent.atomic.AtomicInteger(addresses.size)
        val racers = addresses.map { address ->
            thread("dial-$id-${address.hostAddress}") {
                val left = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
                val attempt = Socket()
                try {
                    attempt.connect(InetSocketAddress(address, targetPort), left.toInt())
                    if (winner.compareAndSet(null, attempt)) {
                        trace("#$id dialed ${address.hostAddress}")
                        latch.countDown()
                    } else {
                        attempt.close()
                    }
                } catch (failed: IOException) {
                    // A refused or unreachable address is an answer too: say
                    // so, and when every address has answered without a
                    // winner the wait ends now instead of at the budget.
                    trace("#$id dial-failed ${address.hostAddress} ${failed.javaClass.simpleName}")
                    try {
                        attempt.close()
                    } catch (_: IOException) {
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0) latch.countDown()
                }
            }
        }
        latch.await(dialBudgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        racers.forEach { racer ->
            if (racer.isAlive) racer.interrupt()
        }
        return winner.get()
    }

    /**
     * Asks public resolvers over plain DNS, in parallel with everything
     * else, because the system resolver is exactly what a filtering network
     * poisons first. Every failure here is silent in effect: the union with
     * the system answers is what gets raced, and a resolver that cannot be
     * reached simply adds nothing.
     */
    internal fun resolveIndependently(host: String, id: Int): List<InetAddress> {
        // An IP literal needs no resolver, poisoned or otherwise.
        if (host.isEmpty() || host.all { it.isDigit() || it == '.' }) return emptyList()
        val servers = listOf("1.1.1.1", "8.8.8.8")
        val found = java.util.Collections.synchronizedList(mutableListOf<InetAddress>())
        val done = java.util.concurrent.CountDownLatch(servers.size * 2)
        for (server in servers) {
            thread("dns-udp-$id-$server") {
                try {
                    found.addAll(parseAnswers(queryUdp(server, buildQuery(host))))
                } catch (_: Exception) {
                } finally {
                    done.countDown()
                }
            }
            thread("dns-tcp-$id-$server") {
                try {
                    found.addAll(parseAnswers(queryTcp(server, buildQuery(host))))
                } catch (_: Exception) {
                } finally {
                    done.countDown()
                }
            }
        }
        done.await(RESOLVER_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (found.isNotEmpty()) trace("#$id independent-resolver ${found.map { it.hostAddress }}")
        return found.toList()
    }

    /** A minimal A-record query: one question, recursion desired. */
    internal fun buildQuery(host: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x4f, 0x2a, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        for (label in host.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0, 1, 0, 1))
        return out.toByteArray()
    }

    /** The A answers of a response, ignoring everything else. */
    internal fun parseAnswers(message: ByteArray): List<InetAddress> {
        if (message.size < 12) return emptyList()
        val questions = ((message[4].toInt() and 0xff) shl 8) or (message[5].toInt() and 0xff)
        val answers = ((message[6].toInt() and 0xff) shl 8) or (message[7].toInt() and 0xff)
        var at = 12
        repeat(questions) {
            at = skipName(message, at)
            at += 4
        }
        val found = mutableListOf<InetAddress>()
        repeat(answers) {
            if (at >= message.size) return found
            at = skipName(message, at)
            if (at + 10 > message.size) return found
            val type = ((message[at].toInt() and 0xff) shl 8) or (message[at + 1].toInt() and 0xff)
            val length = ((message[at + 8].toInt() and 0xff) shl 8) or (message[at + 9].toInt() and 0xff)
            at += 10
            if (at + length > message.size) return found
            if (type == 1 && length == 4) {
                found.add(InetAddress.getByAddress(message.copyOfRange(at, at + 4)))
            }
            at += length
        }
        return found
    }

    private fun skipName(message: ByteArray, from: Int): Int {
        var at = from
        while (at < message.size) {
            val length = message[at].toInt() and 0xff
            if (length == 0) return at + 1
            if (length and 0xc0 == 0xc0) return at + 2
            at += length + 1
        }
        return message.size
    }

    private fun queryUdp(server: String, query: ByteArray): ByteArray {
        java.net.DatagramSocket().use { socket ->
            socket.soTimeout = RESOLVER_BUDGET_MS.toInt()
            val target = InetSocketAddress(server, 53)
            socket.send(java.net.DatagramPacket(query, query.size, target))
            val back = ByteArray(4096)
            val packet = java.net.DatagramPacket(back, back.size)
            socket.receive(packet)
            return back.copyOf(packet.length)
        }
    }

    private fun queryTcp(server: String, query: ByteArray): ByteArray {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server, 53), RESOLVER_BUDGET_MS.toInt())
            socket.soTimeout = RESOLVER_BUDGET_MS.toInt()
            val out = socket.getOutputStream()
            out.write(byteArrayOf((query.size shr 8).toByte(), (query.size and 0xff).toByte()))
            out.write(query)
            out.flush()
            val input = socket.getInputStream()
            val high = input.read()
            val low = input.read()
            if (high < 0 || low < 0) return ByteArray(0)
            val length = (high shl 8) or low
            val back = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val read = input.read(back, filled, length - filled)
                if (read < 0) break
                filled += read
            }
            return back.copyOf(filled)
        }
    }

    /**
     * Copies both ways, but the first client block - the ClientHello, still
     * plain bytes at this layer - goes out in two fragments split inside
     * the SNI name when one is present.
     */
    private fun tunnel(client: Socket, target: Socket, host: String, id: Int) {
        val out = target.getOutputStream()
        val first = readFirstBlock(client.getInputStream())
        if (first != null && first.isNotEmpty()) {
            val at = splitPoint(first, host)
            out.write(first, 0, at)
            out.flush()
            out.write(first, at, first.size - at)
            out.flush()
            trace("#$id hello split at $at of ${first.size}")
        }
        val down = thread("pump-$id-up") { pump(client.getInputStream(), out) }
        pump(target.getInputStream(), client.getOutputStream())
        down.interrupt()
        try {
            target.close()
        } catch (_: IOException) {
        }
        tunnels.remove(target)
    }

    private fun readFirstBlock(input: InputStream): ByteArray? {
        val block = ByteArray(PUMP_BLOCK)
        val read = try {
            input.read(block)
        } catch (_: IOException) {
            return null
        }
        if (read < 0) return null
        return block.copyOf(read)
    }

    /**
     * Splits inside the server name so no single fragment carries it whole.
     * Falls back to the middle of a handshake-shaped block, and to no split
     * at all for anything too small to matter.
     */
    internal fun splitPoint(block: ByteArray, host: String): Int {
        if (block.size < 16) return block.size
        val name = host.toByteArray(Charsets.US_ASCII)
        val at = indexOf(block, name)
        if (at >= 0 && at + SNI_PREFIX < block.size) return at + SNI_PREFIX
        return if (block[0] == 0x16.toByte()) block.size / 2 else block.size
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        for (i in 0..hay.size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val block = ByteArray(PUMP_BLOCK)
        try {
            while (true) {
                val read = input.read(block)
                if (read < 0) return
                output.write(block, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
        }
    }

    private fun answer(client: Socket, status: Int) {
        val reason = when (status) {
            200 -> "Connection Established"
            403 -> "Forbidden"
            else -> "Gateway Timeout"
        }
        client.getOutputStream().write(
            "HTTP/1.1 $status $reason\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        )
        client.getOutputStream().flush()
    }

    private fun thread(name: String, body: () -> Unit): Thread {
        val worker = Thread(body, "FoxSignInProxy-$name")
        worker.isDaemon = true
        worker.start()
        return worker
    }
}
