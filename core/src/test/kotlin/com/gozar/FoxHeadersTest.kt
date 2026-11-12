package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxHeadersTest {

    @Test
    fun mozillaHostsReceiveTheBrowserProfile() {
        for (url in listOf(
            "https://accounts.firefox.com/v1/oauth/token",
            "https://api.accounts.firefox.com/v1/account/device",
            "https://oauth.accounts.firefox.com/v1/token",
            "https://profile.accounts.firefox.com/v1/profile",
            "https://vpn.mozilla.org/api/v1/fpn/token",
            "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"
        )) {
            val profile = FoxHeaders.forUrl(url)
            assertTrue("$url must get a profile", profile.isNotEmpty())
            assertEquals("identity", profile["Accept-Encoding"])
            assertEquals("empty", profile["Sec-Fetch-Dest"])
            assertEquals("no-cors", profile["Sec-Fetch-Mode"])
            assertEquals("none", profile["Sec-Fetch-Site"])
            assertEquals("no-cache", profile["Cache-Control"])
            assertEquals("no-cache", profile["Pragma"])
            assertEquals("u=4", profile["Priority"])
            assertTrue((profile["Accept"] ?: "").startsWith("application/json"))
            // A credential header is the caller's business, never ours.
            assertFalse(profile.containsKey("Authorization"))
        }
    }

    @Test
    fun hostsMozillaDoesNotOwnReceiveNothing() {
        for (url in listOf(
            "https://example.com/api",
            "https://1.1.1.1/dns-query",
            "https://script.googleusercontent.com/macros/echo",
            "https://cloudflare-dns.com/dns-query"
        )) {
            assertTrue("$url must not be profiled", FoxHeaders.forUrl(url).isEmpty())
        }
    }

    @Test
    fun lookalikeHostsAreNotTreatedAsMozilla() {
        for (host in listOf(
            "accounts.firefox.com.evil.example",
            "mozilla.org.evil.example",
            "evil-mozilla.org",
            "notmozilla.com",
            "firefox.com",
            "vpn.mozilla.org.evil.example",
            ""
        )) {
            assertTrue("$host must not be profiled", FoxHeaders.forHost(host).isEmpty())
        }
    }

    @Test
    fun aMalformedUrlYieldsNoProfileInsteadOfThrowing() {
        assertTrue(FoxHeaders.forUrl("not a url").isEmpty())
        assertTrue(FoxHeaders.forUrl("").isEmpty())
    }

    @Test
    fun callerHeadersOverrideTheProfileCaseInsensitively() {
        val merged = FoxHeaders.merge(
            "https://api.accounts.firefox.com/v1/oauth/token",
            mapOf("accept-encoding" to "gzip", "Authorization" to "Hawk x")
        )
        // The caller asked for gzip, so gzip it is — and only once, not twice
        // under two spellings of the same header.
        assertEquals("gzip", merged["accept-encoding"])
        assertFalse(merged.containsKey("Accept-Encoding"))
        assertEquals("Hawk x", merged["Authorization"])
        assertEquals("empty", merged["Sec-Fetch-Dest"])
    }

    @Test
    fun mergingForAnotherHostLeavesTheCallerAlone() {
        val caller = mapOf("Authorization" to "Bearer t", "Accept" to "text/plain")
        assertEquals(caller, FoxHeaders.merge("https://example.com/x", caller))
    }
}
