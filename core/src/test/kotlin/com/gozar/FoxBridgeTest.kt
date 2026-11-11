package com.gozar

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxBridgeTest {
    private val fingerprint = "A".repeat(40)
    private val certificate = "A".repeat(86)
    private val obfs4 = "obfs4 8.8.8.8:443 $fingerprint cert=$certificate iat-mode=0"
    private val webtunnel = "webtunnel 1.1.1.1:443 ${"B".repeat(40)} url=https://bridge.example.com/a1B2 ver=0.0.1"
    private val seed = ByteArray(32) { it.toByte() }
    private val key = FoxSign.publicFromSeed(seed).joinToString("") { "%02x".format(it) }

    private fun signature(json: String): String = Base64.getEncoder().encodeToString(FoxSign.sign(seed, json.toByteArray()))

    private fun feed(
        bridges: List<String> = listOf(obfs4, webtunnel),
        issued: Long = 1_000,
        expires: Long = 2_000,
        sources: String = "[\"tor-moat-builtin\",\"community-onionhop\"]"
    ): String = "{\"schema\":1,\"issued_at\":$issued,\"expires_at\":$expires,\"sources\":$sources,\"bridges\":[${bridges.joinToString(",") { "\"$it\"" }}]}"

    @Test
    fun manualAcceptsOnlyCompletePublicObfs4() {
        assertEquals(obfs4, FoxBridge.manual(obfs4)?.line)
        assertNull(FoxBridge.manual(webtunnel))
        assertNull(FoxBridge.manual(obfs4.replace("8.8.8.8", "127.0.0.1")))
        assertNull(FoxBridge.manual(obfs4.replace("8.8.8.8", "192.168.1.1")))
        assertNull(FoxBridge.manual(obfs4 + "\nSocksPort 0.0.0.0:9050"))
        assertNull(FoxBridge.manual(obfs4 + '"'))
    }

    @Test
    fun webtunnelRejectsUnsafeEndpointsAndUrls() {
        assertEquals(webtunnel, FoxBridge.parse(webtunnel)?.line)
        assertNull(FoxBridge.parse(webtunnel.replace("1.1.1.1", "127.0.0.1")))
        assertNull(FoxBridge.parse(webtunnel.replace("https://", "http://")))
        assertNull(FoxBridge.parse(webtunnel.replace("bridge.example.com", "localhost")))
        assertNull(FoxBridge.parse(webtunnel.replace("/a1B2", "/a%2Fb")))
        assertNull(FoxBridge.parse(webtunnel.replace("ver=0.0.1", "ver=0.0.1\"")))
    }

    @Test
    fun exactSignedBytesProduceValidatedCandidates() {
        val json = feed()
        val accepted = FoxBridge.verify(json, signature(json), nowSeconds = 1_500, keyHex = key)
        assertEquals(listOf(obfs4, webtunnel), accepted?.map { it.line })
        assertNull(FoxBridge.verify(json + "\n", signature(json), nowSeconds = 1_500, keyHex = key))
        assertNull(FoxBridge.verify(json, signature(json), nowSeconds = 2_000, keyHex = key))
        assertNull(FoxBridge.verify(feed(issued = 2_000), signature(feed(issued = 2_000)), nowSeconds = 1_500, keyHex = key))
        assertNull(FoxBridge.verify(json, signature(json), nowSeconds = 1_500))
    }

    @Test
    fun oneInvalidFeedEntryRejectsTheWholeFeed() {
        val malicious = obfs4.replace("8.8.8.8", "127.0.0.1")
        val json = feed(listOf(obfs4, malicious))
        assertNull(FoxBridge.verify(json, signature(json), nowSeconds = 1_500, keyHex = key))
        val injected = feed(sources = "[\"https://untrusted.example/feed\"]")
        assertNull(FoxBridge.verify(injected, signature(injected), nowSeconds = 1_500, keyHex = key))
    }

    @Test
    fun rotationLimitsAutomaticAttemptsAndPrioritizesObfs4() {
        val many = (1..12).map { index ->
            "obfs4 8.8.8.$index:443 ${index.toString(16).padStart(40, '0')} cert=$certificate iat-mode=0"
        }
        val selected = FoxBridge.rotation(many.mapNotNull(FoxBridge::parse) + listOfNotNull(FoxBridge.parse(webtunnel)))
        assertEquals(10, selected.size)
        assertTrue(selected.all { it.mode == FoxTorPlan.Mode.OBFS4 })
        assertFalse(selected.any { it.line.contains("1.1.1.1") })
    }
}
