package com.gozar

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** IPv4/UDP and Cloudflare's bare-IP type-zero CONNECT-IP capsules. */
internal object FoxWarpPackets {
    const val MTU = 1280
    const val MAX_PAYLOAD = MTU - 28
    data class Datagram(val source: Int, val target: Int, val sourcePort: Int, val targetPort: Int, val payload: ByteArray)

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255)
    private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
    private fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte(); b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }
    private fun get32(b: ByteArray, i: Int): Int = ((b[i].toInt() and 255) shl 24) or
        ((b[i + 1].toInt() and 255) shl 16) or ((b[i + 2].toInt() and 255) shl 8) or (b[i + 3].toInt() and 255)
    private fun sum(b: ByteArray, start: Int, end: Int, seed: Long = 0): Long {
        var s = seed; var i = start
        while (i + 1 < end) { s += u16(b, i).toLong(); i += 2 }
        if (i < end) s += ((b[i].toInt() and 255) shl 8).toLong()
        return s
    }
    private fun fold(value: Long): Int {
        var s = value
        while (s ushr 16 != 0L) s = (s and 65535) + (s ushr 16)
        return s.toInt().inv() and 65535
    }
    fun ipv4(text: String): Int? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var result = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
            val n = part.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            result = (result shl 8) or n
        }
        return result
    }
    fun privateIPv4(value: Int): Boolean = (value ushr 24 == 10) || (value ushr 20 == 0xac1) || (value ushr 16 == 0xc0a8)
    fun build(source: Int, target: Int, sourcePort: Int, targetPort: Int, payload: ByteArray, id: Int): ByteArray {
        require(sourcePort in 1..65535 && targetPort in 1..65535 && payload.size <= MAX_PAYLOAD)
        val b = ByteArray(28 + payload.size)
        b[0] = 0x45; put16(b, 2, b.size); put16(b, 4, id); put16(b, 6, 0x4000)
        b[8] = 64; b[9] = 17; put32(b, 12, source); put32(b, 16, target)
        put16(b, 10, fold(sum(b, 0, 20)))
        put16(b, 20, sourcePort); put16(b, 22, targetPort); put16(b, 24, 8 + payload.size)
        payload.copyInto(b, 28)
        val checksum = fold(sum(b, 20, b.size, sum(b, 12, 20, (17 + 8 + payload.size).toLong())))
        put16(b, 26, if (checksum == 0) 65535 else checksum)
        return b
    }
    fun parse(b: ByteArray): Datagram? {
        if (b.size !in 28..MTU || (b[0].toInt() and 0xf0) != 0x40 || b[9].toInt() != 17) return null
        val header = (b[0].toInt() and 15) * 4
        if (header < 20 || header + 8 > b.size || u16(b, 2) != b.size || u16(b, 6) and 0xbfff != 0 || fold(sum(b, 0, header)) != 0) return null
        val length = u16(b, header + 4)
        if (length < 8 || length != b.size - header) return null
        if (u16(b, header + 6) != 0 && fold(sum(b, header, b.size, sum(b, 12, 20, (17 + length).toLong()))) != 0) return null
        return Datagram(get32(b, 12), get32(b, 16), u16(b, header), u16(b, header + 2), b.copyOfRange(header + 8, b.size))
    }
    fun encode(packet: ByteArray): ByteArray {
        require(packet.isNotEmpty() && packet.size <= MTU)
        return byteArrayOf(0) + FoxCapsule.varint(packet.size.toLong()) + packet
    }
    private fun number(input: InputStream, first: Int): Long {
        var n = (first and 63).toLong()
        repeat((1 shl (first ushr 6)) - 1) {
            val next = input.read(); if (next < 0) throw EOFException("truncated WARP capsule")
            n = (n shl 8) or next.toLong()
        }
        return n
    }
    fun read(input: InputStream): ByteArray? {
        while (true) {
            val first = input.read(); if (first < 0) return null
            val type = number(input, first)
            val next = input.read(); if (next < 0) throw EOFException("truncated WARP capsule")
            val size = number(input, next)
            if (size > 65535) throw IOException("WARP capsule too large")
            if (type == 0L && (size == 0L || size > MTU)) throw IOException("invalid WARP IP packet")
            val result = ByteArray(size.toInt())
            var offset = 0
            while (offset < result.size) {
                val count = input.read(result, offset, result.size - offset)
                if (count < 0) throw EOFException("truncated WARP packet")
                if (count == 0) continue
                offset += count
            }
            if (type == 0L) return result
        }
    }
}
