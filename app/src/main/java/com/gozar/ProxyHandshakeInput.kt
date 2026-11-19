package com.gozar

import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Limits the complete request handshake, including a slow trickle of bytes.
 * Once the destination is parsed, finish() removes the timeout so an idle TCP
 * tunnel or UDP control association is not mistaken for a stalled handshake. */
internal class ProxyHandshakeInput(
    private val socket: Socket,
    private val input: InputStream,
    timeoutMillis: Int
) : InputStream() {
    init { require(timeoutMillis > 0) }
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
    private var finished = false

    private fun prepareRead() {
        if (finished) return
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw SocketTimeoutException("proxy handshake timed out")
        // Round up so a positive sub-millisecond remainder is not interpreted
        // as an infinite socket timeout. Recompute on every read.
        val millis = TimeUnit.NANOSECONDS.toMillis(remaining - 1) + 1
        socket.soTimeout = millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun finish() {
        socket.soTimeout = 0
        finished = true
    }

    override fun read(): Int {
        prepareRead()
        return input.read()
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        prepareRead()
        return input.read(buffer, offset, length)
    }
    override fun mark(readlimit: Int) = input.mark(readlimit)
    override fun reset() = input.reset()
    override fun markSupported(): Boolean = input.markSupported()
    override fun close() = input.close()
}
