package com.gozar

import java.io.IOException
import java.io.InputStream

/** A message-preserving, authenticated datagram stream. */
interface FoxDatagramPipe {
    /** Returns null only when the peer cleanly closes between capsules. */
    fun receive(): ByteArray?
    fun send(payload: ByteArray)
    /** Must revoke buffered work and unblock a blocked receive. */
    fun close()
}

/** Wraps one validated CONNECT-UDP stream without any direct socket fallback. */
internal class FoxDatagram(private val pipe: FoxDuplex) : FoxDatagramPipe {
    private val receiveLock = Any()
    private val sendLock = Any()
    private val input = object : InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int {
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            return pipe.read(bytes, offset, length)
        }
    }

    init {
        // Idle expiry is owned by the packet flow, not an arbitrary read timer.
        pipe.setReadTimeout(0)
    }

    override fun receive(): ByteArray? = synchronized(receiveLock) {
        try {
            FoxCapsule.readDatagram(input)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun send(payload: ByteArray) = synchronized(sendLock) {
        try {
            val encoded = FoxCapsule.encodeDatagram(payload)
            pipe.writeCapsule(encoded)
        } catch (error: Exception) {
            close()
            throw IOException("unable to write UDP capsule", error)
        }
    }

    override fun close() = pipe.abort()
}
