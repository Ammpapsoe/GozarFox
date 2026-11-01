package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPolicyTest {
    private fun query() = byteArrayOf(
        0, 42, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 3, 119, 119, 119,
        7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1
    )

    @Test
    fun customResolverRequiresSafeHttpsUrl() {
        assertTrue(DnsPolicy.validCustom("https://dns.example.test/dns-query"))
        assertTrue(DnsPolicy.validCustom("https://dns.example.test:8443/dns-query?id=1"))
        for (value in listOf("", "http://dns.example.test/dns-query", "https://u:p@dns.example.test/", "https://dns.example.test/#fragment", "https:///dns-query", " https://dns.example.test/", "https://dns.example.test:99999/")) {
            assertFalse(value, DnsPolicy.validCustom(value))
        }
    }

    @Test
    fun blockingOverridesSelectedProviderAndNeverFallsBackToUnfilteredDns() {
        assertEquals(listOf(DnsPolicy.ADGUARD), DnsPolicy.endpoints(DnsPolicy.CUSTOM, "https://dns.example.test/", true))
        val seen = mutableListOf<String>()
        val resolver = FoxResolver(0, { endpoint, _ -> seen.add(endpoint); null }, endpoints = DnsPolicy.endpoints(DnsPolicy.AUTO, "", true))
        assertEquals(2, resolver.answer(query())!![3].toInt() and 15)
        assertEquals(listOf(DnsPolicy.ADGUARD), seen)
    }

    @Test
    fun invalidCustomAndUnknownProfilesFailClosed() {
        for (endpoints in listOf(DnsPolicy.endpoints(DnsPolicy.CUSTOM, "http://wrong", false), DnsPolicy.endpoints("unknown", "", false))) {
            var calls = 0
            val resolver = FoxResolver(0, { _, _ -> calls++; null }, endpoints = endpoints)
            assertEquals(2, resolver.answer(query())!![3].toInt() and 15)
            assertEquals(0, calls)
        }
    }

    @Test
    fun customProfileUsesExactlyTheConfiguredResolver() {
        val url = "https://dns.example.test/dns-query"
        val seen = mutableListOf<String>()
        val resolver = FoxResolver(0, { endpoint, request ->
            seen.add(endpoint)
            request.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte() }
        }, endpoints = DnsPolicy.endpoints(DnsPolicy.CUSTOM, url, false))
        assertEquals(0, resolver.answer(query())!![3].toInt() and 15)
        assertEquals(listOf(url), seen)
    }
}
