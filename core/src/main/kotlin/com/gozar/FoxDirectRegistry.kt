package com.gozar

import java.io.IOException
import java.net.Socket

/**
 * Owns direct TCP sockets from before connect() until their pipes are closed.
 * Stopping an attempt closes both in-progress and established sockets. This
 * cannot retract a packet that was already sent before or during cancellation.
 */
class FoxDirectRegistry(private val socketFactory: () -> Socket = { Socket() }) {
    private val lock = Any()
    private var ticket: Long? = null
    private val sockets = mutableSetOf<Socket>()

    fun start(next: Long) {
        val retired = synchronized(lock) {
            ticket = next
            sockets.toList().also { sockets.clear() }
        }
        close(retired)
    }

    fun stop() {
        val retired = synchronized(lock) {
            ticket = null
            sockets.toList().also { sockets.clear() }
        }
        close(retired)
    }

    fun connect(host: String, port: Int, expected: Long, timeout: Int = 8000): FoxPipe? {
        if (synchronized(lock) { ticket != expected }) return null
        val socket = try { socketFactory() } catch (_: Exception) { return null }
        val accepted = synchronized(lock) {
            if (ticket != expected) false else sockets.add(socket)
        }
        if (!accepted) {
            try { socket.close() } catch (_: Exception) { }
            return null
        }
        val pipe = FoxSocketPipe.connect(socket, host, port, timeout)
        if (pipe == null) {
            synchronized(lock) { sockets.remove(socket) }
            return null
        }
        val tracked = object : FoxPipe {
            private fun check() {
                val current = synchronized(lock) { ticket == expected && sockets.contains(socket) }
                if (!current) throw IOException("Direct connection was revoked")
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                check()
                val result = pipe.read(buffer, offset, length)
                check()
                return result
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                check()
                pipe.write(buffer, offset, length)
            }
            override fun finishWrite() {
                check()
                pipe.finishWrite()
            }
            override fun close() {
                synchronized(lock) { sockets.remove(socket) }
                pipe.close()
            }
        }
        val current = synchronized(lock) { ticket == expected && sockets.contains(socket) }
        if (!current) {
            tracked.close()
            return null
        }
        return tracked
    }

    private fun close(sockets: List<Socket>) {
        for (socket in sockets) try { socket.close() } catch (_: Exception) { }
    }
}
