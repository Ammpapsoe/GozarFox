package com.gozar

import com.twitter.hpack.Encoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FoxUdpHeadersTest {
    private fun block(vararg fields: Pair<String, String>): ByteArray = ByteArrayOutputStream().apply {
        fields.forEach { (name, value) -> write(FoxTunnel.pair(name, value)) }
    }.toByteArray()

    @Test
    fun decodesStatusAndCapsuleHeader() {
        val headers = FoxUdpHeaders().decode(block(":status" to "200", "capsule-protocol" to "?1"))
        assertEquals(200, headers.status)
        assertEquals(1, headers.capsuleCount)
        assertEquals("?1", headers.capsule)
        assertFalse(headers.invalidBodyHeaders)
    }

    @Test
    fun keepsHpackDynamicTableAcrossResponseBlocks() {
        val encoder = Encoder(4096)
        val decoder = FoxUdpHeaders()
        fun nextBlock(): ByteArray = ByteArrayOutputStream().apply {
            for ((name, value) in listOf(":status" to "200", "capsule-protocol" to "?1", "x-server" to "example")) {
                encoder.encodeHeader(this, name.toByteArray(), value.toByteArray(), false)
            }
        }.toByteArray()
        val first = nextBlock()
        val second = nextBlock()
        assertTrue("encoder did not exercise indexed fields", second.size < first.size)
        for (encoded in listOf(first, second)) {
            val parsed = decoder.decode(encoded)
            assertEquals(200, parsed.status)
            assertEquals("?1", parsed.capsule)
            assertEquals(1, parsed.capsuleCount)
        }
    }

    @Test
    fun reportsDuplicateCapsuleAndForbiddenBodyHeaders() {
        val headers = FoxUdpHeaders().decode(block(":status" to "200", "capsule-protocol" to "?1", "capsule-protocol" to "?1", "content-length" to "0"))
        assertEquals(2, headers.capsuleCount)
        assertTrue(headers.invalidBodyHeaders)
    }

    @Test
    fun rejectsTruncatedFieldsAndInvalidPseudoHeaders() {
        val bad = listOf(
            byteArrayOf(0x88.toByte(), 0x00, 0x05, 'x'.code.toByte()),
            byteArrayOf(0x88.toByte(), 0xff.toByte()),
            block("capsule-protocol" to "?1", ":status" to "200"),
            block(":status" to "200", ":status" to "200"),
            block(":status" to "200", "Capsule-Protocol" to "?1"),
            block(":status" to "200", "bad name" to "value")
        )
        for (value in bad) {
            try {
                FoxUdpHeaders().decode(value)
                fail("accepted malformed headers")
            } catch (_: IOException) {
            }
        }
    }
}
