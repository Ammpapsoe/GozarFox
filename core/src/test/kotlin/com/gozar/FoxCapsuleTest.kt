package com.gozar

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class FoxCapsuleTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun oneByteAtATime(value: ByteArray): InputStream = object : InputStream() {
        private val source = ByteArrayInputStream(value)
        override fun read(): Int = source.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            if (length == 0) 0 else source.read(buffer, offset, minOf(1, length))
    }

    private fun expectIo(block: () -> Unit) {
        try { block(); fail("expected an I/O error") } catch (_: IOException) { }
    }

    @Test
    fun emptyAndOrdinaryDatagramsHaveRfcCapsuleFraming() {
        assertArrayEquals(bytes(0, 1, 0), FoxCapsule.encodeDatagram(byteArrayOf()))
        val payload = bytes(0x61, 0x62, 0x63)
        assertArrayEquals(bytes(0, 4, 0, 0x61, 0x62, 0x63), FoxCapsule.encodeDatagram(payload))
        val stream = oneByteAtATime(FoxCapsule.encodeDatagram(byteArrayOf()) + FoxCapsule.encodeDatagram(payload))
        assertArrayEquals(byteArrayOf(), FoxCapsule.readDatagram(stream))
        assertArrayEquals(payload, FoxCapsule.readDatagram(stream))
        assertNull(FoxCapsule.readDatagram(stream))
    }

    @Test
    fun variableLengthIntegerBoundariesUseNetworkByteOrder() {
        assertArrayEquals(bytes(0x3f), FoxCapsule.varint(63))
        assertArrayEquals(bytes(0x40, 0x40), FoxCapsule.varint(64))
        assertArrayEquals(bytes(0x7f, 0xff), FoxCapsule.varint(16_383))
        assertArrayEquals(bytes(0x80, 0x00, 0x40, 0x00), FoxCapsule.varint(16_384))
        assertArrayEquals(bytes(0xbf, 0xff, 0xff, 0xff), FoxCapsule.varint((1L shl 30) - 1))
        assertArrayEquals(bytes(0xc0, 0, 0, 0, 0x40, 0, 0, 0), FoxCapsule.varint(1L shl 30))
        assertArrayEquals(bytes(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff), FoxCapsule.varint((1L shl 62) - 1))
        for (invalid in listOf(-1L, 1L shl 62)) {
            try { FoxCapsule.varint(invalid); fail("invalid integer accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun unknownCapsulesAndContextsAreSkippedAndNonminimalIntegersAreAccepted() {
        val unknownCapsule = bytes(5, 3, 0xaa, 0xbb, 0xcc)
        val unknownContext = bytes(0, 2, 17, 0x99)
        // Type 0, value length 4 and context 0 use two-byte QUIC integers.
        val padded = bytes(0x40, 0, 0x40, 4, 0x40, 0, 0x41, 0x42)
        val stream = oneByteAtATime(unknownCapsule + unknownContext + padded)
        assertArrayEquals(bytes(0x41, 0x42), FoxCapsule.readDatagram(stream))
        assertNull(FoxCapsule.readDatagram(stream))
    }

    @Test
    fun truncatedFramesAndExcessiveLengthsFailClosed() {
        for (value in listOf(bytes(0), bytes(0, 0), bytes(0, 1), bytes(0, 1, 0x40),
                bytes(0, 3, 0, 1), bytes(0x40), bytes(0, 0x40))) {
            expectIo { FoxCapsule.readDatagram(oneByteAtATime(value)) }
        }
        val tooLargeUnknown = bytes(5) + FoxCapsule.varint(1_048_577)
        expectIo { FoxCapsule.readDatagram(ByteArrayInputStream(tooLargeUnknown)) }
        val tooLargeUdp = bytes(0) + FoxCapsule.varint(FoxCapsule.MAX_UDP_PAYLOAD + 2L) + bytes(0)
        expectIo { FoxCapsule.readDatagram(ByteArrayInputStream(tooLargeUdp)) }
        try {
            FoxCapsule.encodeDatagram(ByteArray(FoxCapsule.MAX_UDP_PAYLOAD + 1))
            fail("oversize payload accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun maximumLegalPayloadRoundTripsAcrossShortReads() {
        val original = ByteArray(FoxCapsule.MAX_UDP_PAYLOAD) { (it % 251).toByte() }
        assertArrayEquals(original, FoxCapsule.readDatagram(oneByteAtATime(FoxCapsule.encodeDatagram(original))))
    }
}
