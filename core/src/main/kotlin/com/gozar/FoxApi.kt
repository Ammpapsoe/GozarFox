package com.gozar

import org.json.JSONArray
import org.json.JSONObject

class FoxError(message: String, val errno: Int = 0, val status: Int = 0) : Exception(message)

class FoxSession(val uid: String, val token: String, val needsCode: Boolean, val byLink: Boolean = false)

class FoxTokens(val access: String, val refresh: String, val expiresIn: Int)

class FoxPass(val token: String, val limit: Long, val remaining: Long, val reset: String)

class FoxLocation(
    val code: String,
    val country: String,
    val city: String,
    val host: String,
    val port: Int,
    val recommended: Boolean
)

object FoxApi {

    private fun failure(reply: FoxReply): FoxError {
        var message = "HTTP ${reply.code}"
        var errno = 0
        try {
            val json = JSONObject(reply.body)
            errno = json.optInt("errno", 0)
            val text = json.optString("message", "")
            val code = json.optString("code", "")
            message = when {
                text.isNotEmpty() -> text
                code.isNotEmpty() -> code
                else -> message
            }
        } catch (_: Exception) {
        }
        if (reply.code == 406) message = "Mozilla's security check was not accepted; try the official account page"
        if (reply.code == 451) message = "Mozilla is unavailable from this network (HTTP 451). Use a trusted connection to sign in again."
        return FoxError(message, errno, reply.code)
    }

    private fun json(reply: FoxReply): JSONObject {
        if (reply.code !in 200..299) throw failure(reply)
        return try {
            JSONObject(reply.body)
        } catch (_: Exception) {
            throw FoxError("Unexpected response from Mozilla")
        }
    }

    private fun authHeader(token: String, method: String, url: java.net.URL, body: String): String =
        FoxCrypto.hawk(method, url, FoxCrypto.hawkId(token), FoxCrypto.hawkKey(token), body)

    fun register(email: String, password: String): FoxSession {
        val body = JSONObject()
            .put("email", email)
            .put("authPW", FoxCrypto.hex(FoxCrypto.authPw(email, password)))
            .put("service", "sync")
            .put("verificationMethod", "email-otp")
            .toString()
        val reply = FoxNet.call(FoxNet.AUTH + "/account/create", "POST", body)
        val data = json(reply)
        val verified = data.optBoolean("verified", data.optBoolean("emailVerified", false))
        return FoxSession(data.optString("uid"), data.optString("sessionToken"), !verified, false)
    }

    fun signIn(email: String, password: String): FoxSession {
        val authPw = FoxCrypto.hex(FoxCrypto.authPw(email, password))
        val first = attemptSignIn(email, password, authPw, "", "")
        if (first.code == 400) {
            val json = try {
                JSONObject(first.body)
            } catch (_: Exception) {
                null
            }
            if (json != null && json.optInt("errno", 0) == 107) {
                val twoFactor = attemptSignIn(email, password, authPw, "email-2fa", "")
                return readSignIn(twoFactor)
            }
        }
        return readSignIn(first)
    }

    private fun attemptSignIn(
        email: String,
        password: String,
        authPw: String,
        method: String,
        original: String
    ): FoxReply {
        val body = JSONObject()
            .put("email", email)
            .put("authPW", authPw)
            .apply {
                if (method.isNotEmpty()) put("verificationMethod", method)
                if (original.isNotEmpty()) put("originalLoginEmail", original)
            }
            .toString()
        return FoxNet.call(FoxNet.AUTH + "/account/login", "POST", body)
    }

    internal fun readSignIn(reply: FoxReply): FoxSession {
        val data = json(reply)
        val token = data.optString("sessionToken")
        val verified = data.optBoolean("verified", false)
        val method = data.optString("verificationMethod", "")
        // A normal email-method session may be eligible for OAuth even when
        // its sessionVerified flag is false. Let Mozilla decide in the token
        // exchange; do not treat email-2fa or totp-2fa as already verified.
        val tryExchange = verified || method == "email"
        return FoxSession(data.optString("uid"), token, !tryExchange, false)
    }

    fun verifyEmail(sessionToken: String, uid: String, code: String) {
        var last: Exception? = null
        val signed = JSONObject().put("code", code).toString()
        val url = java.net.URL(FoxNet.AUTH + "/recovery_email/verify_code")
        try {
            val header = authHeader(sessionToken, "POST", url, signed)
            val reply = FoxNet.call(url.toString(), "POST", signed, mapOf("Authorization" to header))
            if (reply.code in 200..299) return
            last = failure(reply)
        } catch (e: Exception) {
            last = e
        }
        val plain = JSONObject().put("uid", uid).put("code", code).toString()
        try {
            val reply = FoxNet.call(FoxNet.AUTH + "/recovery_email/verify_code", "POST", plain)
            if (reply.code in 200..299) return
            last = failure(reply)
        } catch (e: Exception) {
            last = e
        }
        try {
            verifySession(sessionToken, code)
            return
        } catch (e: Exception) {
            last = e
        }
        throw last ?: FoxError("The code was not accepted")
    }

    fun emailVerified(sessionToken: String): Boolean {
        val url = java.net.URL(FoxNet.AUTH + "/recovery_email/status")
        return try {
            val header = authHeader(sessionToken, "GET", url, "")
            val reply = FoxNet.call(url.toString(), "GET", null, mapOf("Authorization" to header))
            reply.code in 200..299 && reply.body.contains("\"verified\"") &&
                !reply.body.contains("\"verified\":false")
        } catch (e: Exception) {
            false
        }
    }

    fun resendSignupCode(sessionToken: String) {
        val url = java.net.URL(FoxNet.AUTH + "/recovery_email/resend_code")
        val header = authHeader(sessionToken, "POST", url, "")
        FoxNet.call(url.toString(), "POST", "", mapOf("Authorization" to header))
    }

    fun verifySession(sessionToken: String, code: String) {
        val url = java.net.URL(FoxNet.AUTH + "/session/verify_code")
        val body = JSONObject().put("code", code).toString()
        val header = authHeader(sessionToken, "POST", url, body)
        json(FoxNet.call(url.toString(), "POST", body, mapOf("Authorization" to header)))
    }

    fun tokens(sessionToken: String): FoxTokens {
        val url = java.net.URL(FoxNet.AUTH + "/oauth/token")
        val body = JSONObject()
            .put("client_id", FoxNet.CLIENT_ID)
            .put("grant_type", "fxa-credentials")
            .put("scope", FoxNet.SCOPE)
            .put("access_type", "offline")
            .toString()
        val header = authHeader(sessionToken, "POST", url, body)
        val reply = FoxNet.call(url.toString(), "POST", body, mapOf("Authorization" to header))
        val data = json(reply)
        return FoxTokens(
            data.optString("access_token"),
            data.optString("refresh_token"),
            data.optInt("expires_in", 0)
        )
    }

    fun refresh(refreshToken: String): FoxTokens {
        val body = JSONObject()
            .put("client_id", FoxNet.CLIENT_ID)
            .put("grant_type", "refresh_token")
            .put("refresh_token", refreshToken)
            .put("scope", FoxNet.SCOPE)
            .toString()
        val reply = FoxNet.call(FoxNet.TOKEN_REFRESH, "POST", body)
        val data = json(reply)
        return FoxTokens(
            data.optString("access_token"),
            data.optString("refresh_token").ifEmpty { refreshToken },
            data.optInt("expires_in", 0)
        )
    }

    private fun quota(reply: FoxReply): FoxPass? {
        if (reply.header("X-Quota-Unlimited")?.trim()?.equals("true", true) == true) {
            return FoxPass("", -1, -1, "")
        }
        val limit = reply.header("X-Quota-Limit")?.trim()?.toLongOrNull() ?: return null
        val remaining = reply.header("X-Quota-Remaining")?.trim()?.toLongOrNull() ?: return null
        val reset = reply.header("X-Quota-Reset")?.trim() ?: ""
        if (limit < 0 || remaining < 0) return null
        return FoxPass("", limit, remaining, reset)
    }

    fun pass(access: String): FoxPass = pass(access, true)

    // Renewal must never activate an entitlement implicitly.
    fun existingPass(access: String): FoxPass = pass(access, false)

    private fun pass(access: String, allowActivate: Boolean): FoxPass {
        var reply = FoxNet.callWithRetry(
            FoxNet.GUARDIAN + "/api/v1/fpn/token",
            "GET",
            null,
            mapOf("Authorization" to "Bearer $access")
        )
        if (reply.code == 403 && allowActivate) {
            activate(access)
            reply = FoxNet.callWithRetry(
                FoxNet.GUARDIAN + "/api/v1/fpn/token",
                "GET",
                null,
                mapOf("Authorization" to "Bearer $access")
            )
        }
        if (reply.code == 451 && !FoxNet.controlScoped()) return relayPass(access, allowActivate)
        return readPass(reply)
    }

    private fun relayPass(access: String, allowActivate: Boolean): FoxPass {
        var lastReply: FoxReply? = null
        var lastFailure: Exception? = null
        for (endpoint in FoxRelayDiscovery.relays()) {
            val reply = try {
                var candidate = FoxRelay.once(endpoint, "/v1/guardian/pass", "GET", access)
                if (candidate.code == 403 && allowActivate) {
                    val activation = FoxRelay.once(endpoint, "/v1/guardian/activate", "POST", access, "{}")
                    if (FoxRelay.retry(activation.code)) {
                        lastReply = activation
                        continue
                    }
                    candidate = FoxRelay.once(endpoint, "/v1/guardian/pass", "GET", access)
                }
                candidate
            } catch (error: Exception) {
                lastFailure = error
                null
            } ?: continue
            if (FoxRelay.retry(reply.code)) {
                lastReply = reply
                continue
            }
            return readPass(reply)
        }
        lastReply?.let { throw failure(it) }
        throw lastFailure ?: FoxError("no verified relay is available", status = 451)
    }

    private fun readPass(reply: FoxReply): FoxPass {
        if (reply.code != 200) throw failure(reply)
        val token = try {
            JSONObject(reply.body).optString("token")
        } catch (_: Exception) {
            ""
        }
        if (token.isEmpty()) throw FoxError("Mozilla did not return a proxy pass", status = reply.code)
        val usage = quota(reply) ?: FoxPass(token, 0, 0, "")
        return FoxPass(token, usage.limit, usage.remaining, usage.reset)
    }

    private fun activate(access: String) {
        try {
            FoxNet.call(
                FoxNet.GUARDIAN + "/api/v1/fpn/activate",
                "POST",
                "{}",
                mapOf("Authorization" to "Bearer $access")
            )
        } catch (_: Exception) {
        }
    }

    fun quota(access: String): FoxPass? {
        var reply = FoxNet.call(
            FoxNet.GUARDIAN + "/api/v1/fpn/token",
            "HEAD",
            null,
            mapOf("Authorization" to "Bearer $access")
        )
        if (reply.code == 451 && !FoxNet.controlScoped()) reply = FoxRelay.call("/v1/guardian/pass", "HEAD", access)
        if (reply.code != 200) return null
        return quota(reply)
    }

    fun servers(): List<FoxLocation> {
        var reply = FoxNet.call(FoxNet.SERVERS, "GET")
        if (reply.code == 451 && !FoxNet.controlScoped()) reply = FoxRelay.call("/v1/mozilla/servers", "GET")
        if (reply.code != 200) throw failure(reply)
        val data = JSONObject(reply.body).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<FoxLocation>()
        for (i in 0 until data.length()) {
            val country = data.optJSONObject(i) ?: continue
            val code = country.optString("code")
            if (code.isEmpty()) continue
            val name = country.optString("name", code)
            val cities = country.optJSONArray("cities") ?: continue
            for (c in 0 until cities.length()) {
                val city = cities.optJSONObject(c) ?: continue
                val target = pick(city) ?: continue
                out.add(
                    FoxLocation(
                        code = code,
                        country = name,
                        city = city.optString("name", ""),
                        host = target.first,
                        port = target.second,
                        recommended = code == "REC"
                    )
                )
            }
        }
        return out
    }

    private fun pick(city: JSONObject): Pair<String, Int>? {
        val servers = city.optJSONArray("servers") ?: return null
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            if (server.optBoolean("quarantined", false)) continue
            val hostname = server.optString("hostname")
            val port = server.optInt("port", 0)
            val protocols = server.optJSONArray("protocols")
            if (protocols != null) {
                for (p in 0 until protocols.length()) {
                    val proto = protocols.optJSONObject(p) ?: continue
                    val scheme = proto.optString("scheme", "")
                    if (scheme.isNotEmpty() && !scheme.equals("https", true)) continue
                    val host = proto.optString("host").ifEmpty { hostname }
                    val usePort = proto.optInt("port", 0).let { if (it > 0) it else port }
                    if (host.isEmpty()) continue
                    return host to if (usePort > 0) usePort else 443
                }
            }
            if (hostname.isNotEmpty()) return hostname to if (port > 0) port else 443
        }
        return null
    }
}
