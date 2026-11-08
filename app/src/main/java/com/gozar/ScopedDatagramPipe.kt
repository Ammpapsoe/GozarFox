package com.gozar

import java.io.IOException

/** Revokes a packet stream when the service lifetime or transport ticket changes. */
internal class ScopedDatagramPipe(
    private val inner: FoxDatagramPipe,
    private val valid: () -> Boolean
) : FoxDatagramPipe {
    private fun ensureCurrent() {
        val current = try { valid() } catch (_: Exception) { false }
        if (!current) {
            inner.close()
            throw IOException("UDP transport stopped")
        }
    }

    override fun receive(): ByteArray? {
        ensureCurrent()
        val packet = inner.receive()
        ensureCurrent()
        return packet
    }

    override fun send(payload: ByteArray) {
        ensureCurrent()
        inner.send(payload)
        ensureCurrent()
    }

    override fun close() = inner.close()
}
