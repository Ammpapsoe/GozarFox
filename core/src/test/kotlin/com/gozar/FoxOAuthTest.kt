package com.gozar

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxOAuthTest {
    @Test fun requestUsesPkceOfflineAccessAndExactCallback() {
        val request = FoxOAuth.request(false, "person@example.invalid")
        val parameters = parameters(request.url)
        assertEquals("e6eb0d1e856335fc", parameters["client_id"])
        assertEquals("code", parameters["response_type"])
        assertEquals("offline", parameters["access_type"])
        assertEquals("signin", parameters["action"])
        assertEquals(FoxOAuth.CALLBACK, parameters["redirect_uri"])
        assertEquals("person@example.invalid", parameters["login_hint"])
        assertEquals("S256", parameters["code_challenge_method"])
        assertTrue(request.verifier.length >= 43)
        assertTrue(request.state.length >= 43)
        assertFalse(request.url.contains("account/login"))
        assertFalse(request.url.contains("account/create"))
        assertFalse(FoxRelay.allowed("/v1/mozilla/account/login", "POST"))
        assertFalse(FoxRelay.allowed("/v1/mozilla/account/create", "POST"))
        assertTrue(FoxRelay.allowed("/v1/mozilla/profile", "GET"))
        val digest = MessageDigest.getInstance("SHA-256").digest(request.verifier.toByteArray(Charsets.US_ASCII))
        val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        assertEquals(expected, parameters["code_challenge"])
        val signup = FoxOAuth.request(true)
        assertEquals("signup", parameters(signup.url)["action"])
        assertNotEquals(request.state, signup.state)
        assertNotEquals(request.verifier, signup.verifier)
    }

    @Test fun callbackRequiresExactDestinationStateAndSingleCode() {
        val code = "abcdef1234567890"
        val state = "state-12345678901234567890123456789012"
        val valid = "https://vpn.mozilla.org/oauth/success?code=$code&state=$state"
        assertEquals(code, FoxOAuth.callbackCode(valid, state))
        val rejected = listOf(
            "https://vpn.mozilla.org/oauth/success?code=$code&state=wrong",
            "https://vpn.mozilla.org/oauth/success?code=$code",
            "https://vpn.mozilla.org/oauth/success?code=$code&state=$state&code=other",
            "https://vpn.mozilla.org.evil.invalid/oauth/success?code=$code&state=$state",
            "https://user@vpn.mozilla.org/oauth/success?code=$code&state=$state",
            "https://vpn.mozilla.org:444/oauth/success?code=$code&state=$state",
            "http://vpn.mozilla.org/oauth/success?code=$code&state=$state",
            "https://vpn.mozilla.org/oauth/success?state=$state",
            "https://vpn.mozilla.org/oauth/success?code=short&state=$state"
        )
        for (raw in rejected) assertNull(raw, FoxOAuth.callbackCode(raw, state))
    }

    private fun parameters(raw: String): Map<String, String> {
        val query = URI(raw).rawQuery
        return query.split('&').associate { part ->
            val name = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
    }
}
