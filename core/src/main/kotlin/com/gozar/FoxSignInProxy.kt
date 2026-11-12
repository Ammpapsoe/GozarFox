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
        bound.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
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
            if (targetPort != 443) {
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
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (unknown: IOException) {
            trace("#$id name-unresolved host=$host")
            return null
        }
        if (addresses.isEmpty()) return null
        val deadline = System.currentTimeMillis() + dialBudgetMs
        val winner = AtomicReference<Socket?>()
        val latch = java.util.concurrent.CountDownLatch(1)
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
                } catch (_: IOException) {
                    try {
                        attempt.close()
                    } catch (_: IOException) {
                    }
                }
            })
        }
        latch.await(dialBudgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        racers.forEach { racer ->
            if (racer.isAlive) racer.interrupt()
        }
        return winner.get()
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
