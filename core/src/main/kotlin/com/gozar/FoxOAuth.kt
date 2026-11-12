package com.gozar

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

object FoxOAuth {
    const val CALLBACK = "https://vpn.mozilla.org/oauth/success"
    private const val AUTHORIZATION = "https://accounts.firefox.com/authorization"
    private val random = SecureRandom()

    data class Request(val url: String, val verifier: String, val state: String)
    data class BrowserRequest(val url: String, val verifier: String, val state: String)

    fun customTabRequest(loginHint: String = ""): BrowserRequest {
        val verifier = token()
        val state = token()
        val challenge = challenge(verifier)
        val parameters = linkedMapOf(
            "client_id" to FoxNet.CLIENT_ID,
            "scope" to FoxNet.SCOPE,
            "response_type" to "code",
            "redirect_uri" to CALLBACK,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "access_type" to "offline",
            "action" to "signin",
            "iap" to "true"
        )
        if (loginHint.isNotBlank()) parameters["login_hint"] = loginHint.trim()
        val query = parameters.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        return BrowserRequest("$AUTHORIZATION?$query", verifier, state)
    }

    fun request(create: Boolean, loginHint: String = ""): Request {
        val verifier = token()
        val state = token()
        val challenge = challenge(verifier)
        val parameters = linkedMapOf(
            "client_id" to FoxNet.CLIENT_ID,
            "scope" to FoxNet.SCOPE,
            "response_type" to "code",
            "redirect_uri" to CALLBACK,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "access_type" to "offline",
            "action" to if (create) "signup" else "signin"
        )
        if (loginHint.isNotBlank()) parameters["login_hint"] = loginHint.trim()
        val query = parameters.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        return Request("$AUTHORIZATION?$query", verifier, state)
    }

    fun callbackCode(raw: String, expectedState: String): String? {
        if (expectedState.length < 32) return null
        return try {
            val uri = URI(raw)
            if (uri.scheme != "https" || uri.host != "vpn.mozilla.org" || uri.path != "/oauth/success" ||
                uri.port != -1 || uri.userInfo != null || uri.fragment != null
            ) return null
            val values = HashMap<String, String>()
            for (part in uri.rawQuery.orEmpty().split('&')) {
                if (part.isEmpty()) continue
                val name = decode(part.substringBefore('='))
                if (values.put(name, decode(part.substringAfter('=', ""))) != null) return null
            }
            val state = values["state"] ?: return null
            if (!MessageDigest.isEqual(state.toByteArray(Charsets.US_ASCII), expectedState.toByteArray(Charsets.US_ASCII))) return null
            val code = values["code"] ?: return null
            code.takeIf { it.length in 8..512 && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' } }
        } catch (_: Exception) {
            null
        }
    }

    private fun challenge(verifier: String): String =
        encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun token(): String = encode(ByteArray(32).also { random.nextBytes(it) })

    private fun encode(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index < bytes.size) {
            val a = bytes[index].toInt() and 255
            val b = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 255 else 0
            val c = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 255 else 0
            val value = (a shl 16) or (b shl 8) or c
            output.append(alphabet[(value ushr 18) and 63])
            output.append(alphabet[(value ushr 12) and 63])
            if (index + 1 < bytes.size) output.append(alphabet[(value ushr 6) and 63])
            if (index + 2 < bytes.size) output.append(alphabet[value and 63])
            index += 3
        }
        return output.toString().trimEnd('=').replace('+', '-').replace('/', '_')
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}
