package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDnsTest {
    @Test
    fun directHostResolutionUsesConfiguredEncryptedResolver() {
        val urls = mutableListOf<String>()
        val questions = mutableListOf<ByteArray>()
        val endpoint = "https://dns.example.test/dns-query"
        val resolver = FoxResolver(0, { url, query ->
            urls.add(url)
            questions.add(query)
            query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1 } +
                byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4)
        }, endpoints = listOf(endpoint))
        assertEquals("1.2.3.4", resolver.directAddress("www.example.test"))
        assertEquals(listOf(endpoint), urls)
        assertTrue(String(questions.single(), Charsets.ISO_8859_1).contains("example"))
    }

    @Test
    fun localLiteralsDoNotQueryAndFailureNeverFallsBackToSystemDns() {
        var calls = 0
        val resolver = FoxResolver(0, { _, _ -> calls++; null }, endpoints = listOf("https://dns.example.test/"))
        assertEquals("127.0.0.1", resolver.directAddress("localhost"))
        assertEquals("192.168.1.1", resolver.directAddress("192.168.1.1"))
        assertNull(resolver.directAddress("printer.local"))
        assertNull(resolver.directAddress("bad name"))
        assertEquals(0, calls)
        assertNull(resolver.directAddress("not-resolved.example"))
        assertEquals(1, calls)
    }

    @Test
    fun filteredZeroAddressDoesNotConnectToTheLocalMachine() {
        val resolver = FoxResolver(0, { _, query ->
            query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1 } +
                byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 0, 0, 0, 0)
        }, endpoints = listOf("https://dns.example.test/"))
        assertNull(resolver.directAddress("ads.example"))
    }

    @Test
    fun malformedAnswersDoNotProduceADirectDestination() {
        val resolver = FoxResolver(0, { _, query ->
            query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1 } +
                byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2)
        }, endpoints = listOf("https://dns.example.test/"))
        assertNull(resolver.directAddress("broken.example"))
    }
}
