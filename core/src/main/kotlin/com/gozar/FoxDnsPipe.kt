package com.gozar

import java.io.IOException
import java.util.ArrayDeque

class FoxDnsPipe(
    private val resolver: FoxResolver,
    private val onAnswer: (ByteArray, ByteArray) -> Unit = { _, _ -> }
) : FoxPipe {
    private val lock = Object()
    private var incoming = ByteArray(0)
    private val outgoing = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var queued = 0
    private var finished = false
    private var closed = false

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val messages = ArrayList<ByteArray>()
        synchronized(lock) {
            if (closed || finished) throw IOException("DNS connection is closed")
            incoming += buffer.copyOfRange(offset, offset + length)
            while (incoming.size >= 2) {
                val size = ((incoming[0].toInt() and 255) shl 8) or (incoming[1].toInt() and 255)
                if (size < 12) throw IOException("invalid DNS message")
                if (incoming.size < size + 2) break
                messages.add(incoming.copyOfRange(2, size + 2))
                incoming = incoming.copyOfRange(size + 2, incoming.size)
            }
            if (incoming.size > 65537) throw IOException("DNS message is too large")
        }
        for (query in messages) {
            val answer = resolver.answer(query) ?: throw IOException("invalid DNS query")
            if (answer.size > 65535) throw IOException("DNS reply is too large")
            val framed = ByteArray(answer.size + 2)
            framed[0] = (answer.size ushr 8).toByte()
            framed[1] = answer.size.toByte()
            answer.copyInto(framed, 2)
            synchronized(lock) {
                if (closed) throw IOException("DNS connection is closed")
                // Publishing the learned name and checking closure must be
                // atomic: a slow lookup cannot teach a flow after it closes.
                onAnswer(query, answer)
                while (queued + framed.size > 512 * 1024 && !closed) lock.wait()
                if (closed) throw IOException("DNS connection is closed")
                outgoing.addLast(framed)
                queued += framed.size
                lock.notifyAll()
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        synchronized(lock) {
            while (outgoing.isEmpty() && !closed && !finished) lock.wait()
            if (outgoing.isEmpty()) return -1
            val first = outgoing.first()
            val count = minOf(length, first.size - headOffset)
            System.arraycopy(first, headOffset, buffer, offset, count)
            headOffset += count
            queued -= count
            if (headOffset == first.size) {
                outgoing.removeFirst()
                headOffset = 0
            }
            lock.notifyAll()
            return count
        }
    }

    override fun finishWrite() {
        synchronized(lock) {
            if (incoming.isNotEmpty()) throw IOException("incomplete DNS message")
            finished = true
            lock.notifyAll()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            outgoing.clear()
            incoming = ByteArray(0)
            queued = 0
            lock.notifyAll()
        }
    }
}
