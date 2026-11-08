package com.gozar

import java.net.URL
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class FoxAuthProtocolTest {
    @Test fun hawkUsesStandardBase64AndCanonicalHost() {
        val token = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
        val payload = "{\"code\":\"123456\"}"
        val url = URL("https://oauth.accounts.firefox.com/v1/session/verify_code?source=test")
        val id = FoxCrypto.hawkId(token)
        val key = FoxCrypto.hawkKey(token)
        val header = FoxCrypto.hawk("POST", url, id, key, payload)
        val parts = header.removePrefix("Hawk ").split(", ").associate {
            val index = it.indexOf('=')
            it.substring(0, index) to it.substring(index + 1).trim('"')
        }
        val expectedHash = Base64.getEncoder().encodeToString(
            FoxCrypto.sha256("hawk.1.payload\napplication/json\n$payload\n".toByteArray())
        )
        assertEquals(expectedHash, parts["hash"])
        val normalized = listOf("hawk.1.header", parts["ts"], parts["nonce"], "POST",
            "/v1/session/verify_code?source=test", "oauth.accounts.firefox.com", "443",
            expectedHash, "", "").joinToString("\n")
        val expectedMac = Base64.getEncoder().encodeToString(FoxCrypto.hmac(key, normalized.toByteArray()))
        assertEquals(expectedMac, parts["mac"])
    }

    @Test fun normalEmailSessionMayExchangeButTwoFactorWaitsForCode() {
        fun read(method: String): FoxSession = FoxApi.readSignIn(FoxReply(200,
            "{\"uid\":\"fake\",\"sessionToken\":\"fake\",\"verified\":false,\"verificationMethod\":\"$method\"}",
            emptyMap()))
        assertFalse(read("email").needsCode)
        assertTrue(read("email-2fa").needsCode)
        assertTrue(read("totp-2fa").needsCode)
    }
}
