package com.gozar

import java.security.cert.CertificateFactory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxRelayDiscoveryTest {
    private val seed = ByteArray(32) { 7 }
    private val public = FoxSign.publicFromSeed(seed)
    private val prefix = "GOZAR-RELAY-ENDPOINTS-V1\n".toByteArray(Charsets.UTF_8)
    private val document = "{\"version\":1,\"relays\":[\"https://gozar-guardian-a.gozarfox7.workers.dev\",\"https://gozar-guardian-b.gozarfox7.workers.dev\"]}"
    private val appsScript = "https://script.google.com/macros/s/AKfycbwMXiE-wFBEaC_ZpZOode1yvgPG1xIHsrty-sM4czaFz6ZBabAWuCd9udiPW4PLH2GR/exec"

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun signature(raw: String): String = hex(FoxSign.sign(seed, prefix + raw.toByteArray(Charsets.UTF_8)))

    @Test fun validSignatureIsAcceptedAndMissingOrTamperedSignatureFailsClosed() {
        val signed = signature(document)
        assertTrue(FoxRelayDiscovery.verify(document, signed, hex(public)))
        assertFalse(FoxRelayDiscovery.verify(document, "", hex(public)))
        assertFalse(FoxRelayDiscovery.verify(document.replace('"', '\''), signed, hex(public)))
        assertFalse(FoxRelayDiscovery.verify(document.replace("guardian-a", "guardian-x"), signed, hex(public)))
        assertFalse(FoxRelayDiscovery.verify(document, signed, hex(FoxSign.publicFromSeed(ByteArray(32) { 8 }))))
        val previous = FoxRelayDiscovery.relays()
        assertFalse(FoxRelayDiscovery.acceptPublished(document, signed))
        assertEquals(previous, FoxRelayDiscovery.relays())
    }

    @Test fun endpointSchemaRejectsArbitraryDestinationsAndDuplicates() {
        val valid = FoxRelayDiscovery.parse(document)
        assertEquals(2, valid?.size)
        assertTrue(FoxRelayDiscovery.validUrl(appsScript))
        assertFalse(FoxRelayDiscovery.validUrl(appsScript.removeSuffix("/exec") + "/dev"))
        assertFalse(FoxRelayDiscovery.validUrl("https://script.google.com/macros/s/short/exec"))
        val invalid = listOf(
            "http://gozar-guardian-a.gozarfox7.workers.dev",
            "https://example.org",
            "https://gozar-guardian-a.gozarfox7.workers.dev.evil.invalid",
            "https://user@gozar-guardian-a.gozarfox7.workers.dev",
            "https://gozar-guardian-a.gozarfox7.workers.dev:444",
            "https://gozar-guardian-a.gozarfox7.workers.dev/v1/guardian/pass",
            "https://gozar-guardian-a.gozarfox7.workers.dev/?next=evil"
        )
        for (url in invalid) assertFalse(url, FoxRelayDiscovery.validUrl(url))
        assertNull(FoxRelayDiscovery.parse("{\"version\":1,\"relays\":[\"https://gozar-guardian-a.gozarfox7.workers.dev\",\"https://gozar-guardian-a.gozarfox7.workers.dev/\"]}"))
        assertNull(FoxRelayDiscovery.parse("{\"version\":1,\"relays\":[],\"extra\":true}"))
        assertNull(FoxRelayDiscovery.parse("{\"version\":2,\"relays\":[\"https://gozar-guardian-a.gozarfox7.workers.dev\"]}"))
    }

    @Test fun cacheExpiresAfterTwentyFourHoursAndRejectsFutureOrTamperedData() {
        val now = 1_800_000_000_000L
        val relays = listOf("https://gozar-guardian-a.gozarfox7.workers.dev")
        val raw = FoxRelayDiscovery.encodeCache(relays, now)
        assertNotNull(raw)
        assertEquals(relays, FoxRelayDiscovery.decodeCache(raw!!, now + FoxRelayDiscovery.TTL_MS - 1)?.first)
        assertNull(FoxRelayDiscovery.decodeCache(raw, now + FoxRelayDiscovery.TTL_MS))
        assertNull(FoxRelayDiscovery.decodeCache(raw, now - FoxRelayDiscovery.MAX_SKEW_MS - 1))
        assertNull(FoxRelayDiscovery.decodeCache(raw.replace("guardian-a", "guardian-x.evil"), now))
        assertTrue(FoxRelayDiscovery.restoreCache(raw, now))
        assertEquals(listOf(appsScript) + relays, FoxRelayDiscovery.relays())
    }

    @Test fun invalidRefreshKeepsThePreviousVerifiedList() {
        val previous = listOf("https://gozar-guardian-c.gozarfox7.workers.dev")
        FoxRelayDiscovery.installForTest(previous)
        assertFalse(FoxRelayDiscovery.acceptPublished(document, "00"))
        assertEquals(previous, FoxRelayDiscovery.relays())
        val array = JSONArray().put(previous.single())
        val cached = JSONObject().put("version", 1).put("fetched_at", 1000L).put("relays", array).toString()
        assertNull(FoxRelayDiscovery.decodeCache(cached, 1000L + FoxRelayDiscovery.TTL_MS))
    }

    @Test fun spkiPinAcceptsOnlyTheExpectedLeafPublicKey() {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            FoxRelayDiscoveryTest::class.java.getResourceAsStream("/tls/test-ca.pem")
        ) as java.security.cert.X509Certificate
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        assertTrue(FoxPinnedTls.matches(arrayOf(certificate), digest))
        assertFalse(FoxPinnedTls.matches(arrayOf(certificate), ByteArray(32)))
        assertFalse(FoxPinnedTls.matches(emptyArray(), digest))
        assertTrue(FoxPinnedTls.WORKER_SPKI_SHA256_PINS.all { it.matches(Regex("[0-9a-f]{64}")) })
    }
}
