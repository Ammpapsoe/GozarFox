package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Split-tunnel list signatures. The list is accepted only when its detached
 * Ed25519 signature verifies with the pinned list key over the exact bytes,
 * with a domain-separation prefix so an update or other signature cannot be
 * replayed as a list signature.
 */
class FoxGeoListTest {

    // RFC 8032 test seed; its public key is never the pinned production key.
    private val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val otherSeed = ByteArray(32) { 7 }
    private val publicKey = FoxSign.publicFromSeed(seed)
    private val list = "example.ir\nbank.example\n".toByteArray(Charsets.UTF_8)

    private fun signed(key: ByteArray, body: ByteArray): String =
        FoxSign.sign(key, "GOZAR-IR-DOMAINS-V1\n".toByteArray(Charsets.UTF_8) + body).toHex()

    @Test
    fun acceptsValidSignatureOverExactBytes() {
        assertTrue(FoxGeo.acceptSigned(list, signed(seed, list), publicKey))
    }

    @Test
    fun rejectsTamperedList() {
        val sig = signed(seed, list)
        val tampered = "example.ir\nevil.example\n".toByteArray(Charsets.UTF_8)
        assertFalse(FoxGeo.acceptSigned(tampered, sig, publicKey))
    }

    @Test
    fun rejectsMissingOrMalformedSignature() {
        assertFalse(FoxGeo.acceptSigned(list, "", publicKey))
        assertFalse(FoxGeo.acceptSigned(list, "not-hex", publicKey))
        assertFalse(FoxGeo.acceptSigned(list, signed(seed, list).substring(2), publicKey))
        assertFalse(FoxGeo.acceptSigned(list, "+" + signed(seed, list).substring(1), publicKey))
    }

    @Test
    fun rejectsWrongKey() {
        assertFalse(FoxGeo.acceptSigned(list, signed(otherSeed, list), publicKey))
    }

    @Test
    fun rejectsSignatureWithoutDomainPrefix() {
        // A raw signature over the list (or an update-style message) is not a list signature.
        val raw = FoxSign.sign(seed, list).toHex()
        assertFalse(FoxGeo.acceptSigned(list, raw, publicKey))
    }

    @Test
    fun pinnedKeyIsThirtyTwoBytes() {
        assertEquals(64, FoxGeo.LIST_KEY_HEX.length)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { i ->
        text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
