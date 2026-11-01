package com.gozar

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class DirectPipe(
    private val pipe: FoxPipe,
    private val allowed: () -> Boolean
) : FoxPipe {
    private val closed = AtomicBoolean(false)

    init { active.add(this) }

    private fun checkAllowed() {
        if (!closed.get() && allowed()) return
        try { close() } catch (_: Exception) { }
        throw IOException("Direct routing is unavailable under Android VPN protection")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkAllowed()
        val size = pipe.read(buffer, offset, length)
        checkAllowed()
        return size
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        checkAllowed()
        pipe.write(buffer, offset, length)
    }

    override fun finishWrite() {
        checkAllowed()
        pipe.finishWrite()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.remove(this)
        pipe.close()
    }

    companion object {
        private val active = ConcurrentHashMap.newKeySet<DirectPipe>()

        fun closeAll() {
            for (pipe in active) try { pipe.close() } catch (_: Exception) { }
        }
    }
}
