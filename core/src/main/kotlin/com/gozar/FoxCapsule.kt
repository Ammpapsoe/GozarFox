package com.gozar

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Bounded RFC 9297 DATAGRAM-capsule framing for an RFC 9298 UDP tunnel.
 * This codec alone does not imply production proxy support. A caller must
 * first establish a validated CONNECT-UDP stream and negotiate
 * the Capsule Protocol; ordinary TCP CONNECT streams must not use it.
 *
 * Reads are streaming, so a capsule may cross any number of HTTP/2 DATA
 * frames. A decoder is not a shared concurrent reader: one owner must read a
 * particular stream, and callers must serialize writes on that stream.
 */
internal object FoxCapsule {
    const val MAX_UDP_PAYLOAD = 65_527
    private const val MAX_CAPSULE_VALUE = 1_048_576L
    private const val DATAGRAM = 0L
    private const val UDP_CONTEXT = 0L

    /** Encode one UDP payload, including an empty payload, as one capsule. */
    fun encodeDatagram(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_UDP_PAYLOAD) { "UDP payload is too large" }
        return ByteArrayOutputStream(payload.size + 10).apply {
            write(varint(DATAGRAM))
            write(varint(payload.size.toLong() + 1)) // context zero is one byte
            write(varint(UDP_CONTEXT))
            write(payload)
        }.toByteArray()
    }

    /**
     * Read the next context-zero datagram. Unknown capsule types and contexts
     * are skipped without buffering their values. A clean EOF between capsules
     * is null; truncated headers/values or excessive lengths fail the stream.
     */
    @Throws(IOException::class)
    fun readDatagram(input: InputStream): ByteArray? {
        while (true) {
            val first = input.read()
            if (first < 0) return null
            val type = readVarint(input, first)
            val length = readRequiredVarint(input)
            if (length > MAX_CAPSULE_VALUE) throw IOException("capsule exceeds the configured limit")
            if (type != DATAGRAM) {
                skipExactly(input, length)
                continue
            }
            if (length == 0L) throw IOException("DATAGRAM capsule has no context ID")
            val contextFirst = input.read()
            if (contextFirst < 0) throw EOFException("truncated context ID")
            val contextWidth = 1 shl (contextFirst ushr 6)
            if (contextWidth.toLong() > length) throw IOException("context ID exceeds capsule length")
            val context = readVarint(input, contextFirst)
            val remaining = length - contextWidth
            if (context != UDP_CONTEXT) {
                skipExactly(input, remaining)
                continue
            }
            if (remaining > MAX_UDP_PAYLOAD) throw IOException("UDP payload exceeds RFC 9298 limit")
            val result = ByteArray(remaining.toInt())
            readExactly(input, result, result.size)
            return result
        }
    }

    internal fun varint(value: Long): ByteArray {
        require(value >= 0 && value < (1L shl 62)) { "QUIC integer out of range" }
        val width = when {
            value < (1L shl 6) -> 1
            value < (1L shl 14) -> 2
            value < (1L shl 30) -> 4
            else -> 8
        }
        val prefix = when (width) { 1 -> 0x00; 2 -> 0x40; 4 -> 0x80; else -> 0xc0 }
        val bytes = ByteArray(width)
        var left = value
        for (index in width - 1 downTo 0) {
            bytes[index] = left.toByte()
            left = left ushr 8
        }
        bytes[0] = (bytes[0].toInt() or prefix).toByte()
        return bytes
    }

    private fun readRequiredVarint(input: InputStream): Long {
        val first = input.read()
        if (first < 0) throw EOFException("truncated capsule header")
        return readVarint(input, first)
    }

    private fun readVarint(input: InputStream, first: Int): Long {
        val width = 1 shl (first ushr 6)
        var result = (first and 0x3f).toLong()
        repeat(width - 1) {
            val next = input.read()
            if (next < 0) throw EOFException("truncated QUIC integer")
            result = (result shl 8) or next.toLong()
        }
        return result
    }

    private fun skipExactly(input: InputStream, length: Long) {
        val buffer = ByteArray(4096)
        var left = length
        while (left > 0) {
            val count = minOf(left, buffer.size.toLong()).toInt()
            readExactly(input, buffer, count)
            left -= count
        }
    }

    private fun readExactly(input: InputStream, buffer: ByteArray, count: Int) {
        var at = 0
        while (at < count) {
            val read = input.read(buffer, at, count - at)
            when {
                read < 0 -> throw EOFException("truncated capsule value")
                read == 0 -> {
                    val one = input.read()
                    if (one < 0) throw EOFException("truncated capsule value")
                    buffer[at++] = one.toByte()
                }
                else -> at += read
            }
        }
    }
}
