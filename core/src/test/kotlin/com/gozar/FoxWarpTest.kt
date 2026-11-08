package com.gozar

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class FoxWarpTest {
    @Test fun packetChecksumsCapsulesAndLimits() {
        val source = FoxWarpPackets.ipv4("172.16.0.2")!!
        val target = FoxWarpPackets.ipv4("1.1.1.1")!!
        val payload = "hello".toByteArray()
        val packet = FoxWarpPackets.build(source, target, 40000, 53, payload, 17)
        val framed = FoxWarpPackets.encode(packet)
        assertEquals(0, framed[0].toInt())
        assertArrayEquals(packet, FoxWarpPackets.read(ByteArrayInputStream(framed)))
        val decoded = FoxWarpPackets.parse(packet)!!
        assertEquals(source, decoded.source); assertEquals(target, decoded.target)
        assertEquals(40000, decoded.sourcePort); assertEquals(53, decoded.targetPort)
        assertArrayEquals(payload, decoded.payload)
        val corrupted = packet.clone(); corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
        assertNull(FoxWarpPackets.parse(corrupted))
        try { FoxWarpPackets.build(source, target, 40000, 53, ByteArray(FoxWarpPackets.MAX_PAYLOAD + 1), 0); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun base64AndClientCertificate() {
        for (length in 1..65) {
            val raw = ByteArray(length).also { SecureRandom().nextBytes(it) }
            assertArrayEquals(raw, WarpBase64.decode(WarpBase64.encode(raw)))
        }
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val device = gen.generateKeyPair(); val server = gen.generateKeyPair()
        val identity = WarpIdentity("device-1", "private-token", WarpBase64.encode(device.private.encoded),
            WarpBase64.encode(device.public.encoded), WarpBase64.encode(server.public.encoded), "172.16.0.2")
        assertTrue(identity.ready())
        assertTrue(WarpIdentity.parse(identity.json()).ready())
        assertNotNull(WarpTLS.context(identity))
        assertFalse(identity.copy(ipv4 = "8.8.8.8").ready())
        assertFalse(identity.copy(token = "bad\r\nvalue").baseValid())
    }
}
