package com.gozar

import com.twitter.hpack.Decoder
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Stateful HPACK response decoder for a dedicated UDP HTTP/2 connection.
 * A single reader owns it and must process every response block in wire order,
 * including blocks for streams that have already been cancelled.
 */
internal class FoxUdpHeaders {
    private val decoder = Decoder(MAX_HEADERS, 4096)

    data class Response(val status: Int?, val capsuleCount: Int, val capsule: String?, val invalidBodyHeaders: Boolean)

    fun decode(block: ByteArray, requireStatus: Boolean = true): Response {
        if (block.size > MAX_HEADERS) throw IOException("UDP proxy headers exceed the limit")
        validateEncoding(block)
        val fields = ArrayList<Pair<String, String>>()
        decoder.decode(ByteArrayInputStream(block)) { rawName, rawValue, _ ->
            if (rawName.isEmpty() || rawName.any { (it.toInt() and 255) !in 0x21..0x7e } ||
                rawValue.any { it == 0.toByte() || it == 10.toByte() || it == 13.toByte() }) {
                throw IOException("invalid UDP proxy header")
            }
            val name = String(rawName, Charsets.US_ASCII)
            if (name.any { it in 'A'..'Z' }) throw IOException("uppercase HTTP/2 header")
            val value = String(rawValue, Charsets.ISO_8859_1)
            fields.add(name to value)
        }
        if (decoder.endHeaderBlock()) throw IOException("UDP proxy header list exceeds the limit")
        var status: Int? = null
        var regularSeen = false
        var capsule: String? = null
        var capsuleCount = 0
        var invalidBodyHeaders = false
        for ((name, value) in fields) {
            if (name.startsWith(":")) {
                if (regularSeen || name != ":status" || status != null ||
                    value.length != 3 || value.any { it !in '0'..'9' }) {
                    throw IOException("invalid HTTP/2 response pseudo-header")
                }
                status = value.toIntOrNull()?.takeIf { it in 100..599 }
                    ?: throw IOException("invalid HTTP/2 response status")
            } else {
                regularSeen = true
                if (name.any { !token(it) }) throw IOException("invalid HTTP/2 header name")
                if (name == "capsule-protocol") {
                    capsuleCount++
                    capsule = value.trim()
                }
                if (name in FORBIDDEN) invalidBodyHeaders = true
            }
        }
        if (status == null && requireStatus) throw IOException("HTTP/2 response is missing a status")
        return Response(status, capsuleCount, capsule, invalidBodyHeaders)
    }

    /**
     * Twitter's decoder is streaming and its endHeaderBlock() resets an
     * unfinished field. Validate complete field boundaries first so a truncated
     * block cannot be accepted after an earlier valid status.
     */
    private fun validateEncoding(block: ByteArray) {
        var at = 0
        var fieldsStarted = false
        while (at < block.size) {
            val first = block[at].toInt() and 255
            when {
                first and 0x80 != 0 -> {
                    val (value, next) = number(block, at, 7)
                    if (value == 0) throw IOException("invalid HPACK index")
                    at = next
                    fieldsStarted = true
                }
                first and 0xe0 == 0x20 -> {
                    if (fieldsStarted) throw IOException("late HPACK table update")
                    val (value, next) = number(block, at, 5)
                    if (value > 4096) throw IOException("HPACK table is too large")
                    at = next
                }
                else -> {
                    val prefix = if (first and 0xc0 == 0x40) 6 else 4
                    val (nameIndex, next) = number(block, at, prefix)
                    at = next
                    if (nameIndex == 0) at = skipString(block, at)
                    at = skipString(block, at)
                    fieldsStarted = true
                }
            }
        }
    }

    private fun skipString(block: ByteArray, offset: Int): Int {
        val (length, start) = number(block, offset, 7)
        if (length > block.size - start) throw IOException("truncated HPACK string")
        return start + length
    }

    private fun number(block: ByteArray, offset: Int, prefix: Int): Pair<Int, Int> {
        if (offset >= block.size) throw IOException("truncated HPACK integer")
        val mask = (1 shl prefix) - 1
        var value = block[offset].toInt() and mask
        var at = offset + 1
        if (value < mask) return value to at
        var shift = 0
        while (at < block.size && shift <= 28) {
            val next = block[at++].toInt() and 255
            val add = (next and 127).toLong() shl shift
            if (value.toLong() + add > Int.MAX_VALUE) throw IOException("HPACK integer overflow")
            value += add.toInt()
            if (next and 128 == 0) return value to at
            shift += 7
        }
        throw IOException("truncated HPACK integer")
    }

    private fun token(ch: Char): Boolean = ch in 'a'..'z' || ch in '0'..'9' || ch in "!#$%&'*+-.^_`|~"

    companion object {
        private const val MAX_HEADERS = 65_536
        private val FORBIDDEN = setOf(
            "content-length", "content-type", "content-encoding", "transfer-encoding",
            "connection", "keep-alive", "proxy-connection", "upgrade"
        )
    }
}
