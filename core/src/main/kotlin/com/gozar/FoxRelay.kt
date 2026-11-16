package com.gozar

import java.io.IOException
import java.net.URI
import java.net.URL
import org.json.JSONObject

object FoxRelay {
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    internal data class Request(val endpoint: String, val path: String, val method: String, val access: String?, val body: String?)
    @Volatile private var testHandler: ((Request) -> FoxReply)? = null

    fun retry(status: Int): Boolean = status == 408 || status == 425 || status == 429 || status == 451 || status in 500..599

    fun allowed(path: String, method: String): Boolean = when (path) {
        "/v1/mozilla/servers" -> method == "GET"
        "/v1/mozilla/token", "/v1/mozilla/oauth/token", "/v1/mozilla/recovery-email/verify-code",
        "/v1/mozilla/recovery-email/resend-code", "/v1/mozilla/session/verify-code" -> method == "POST"
        "/v1/mozilla/recovery-email/status", "/v1/mozilla/profile" -> method == "GET"
        else -> false
    }

    fun relayPath(url: String, method: String): String? = try {
        val target = URL(url)
        if (target.protocol != "https" || target.port !in setOf(-1, 443) || target.userInfo != null || target.query != null || target.ref != null) {
            null
        } else {
            val path = when {
                target.host.equals("vpn.mozilla.org", true) -> null
                target.host.equals("oauth.accounts.firefox.com", true) -> when (target.path) {
                    "/v1/token" -> "/v1/mozilla/token"
                    "/v1/oauth/token" -> "/v1/mozilla/oauth/token"
                    "/v1/recovery_email/status" -> "/v1/mozilla/recovery-email/status"
                    "/v1/recovery_email/verify_code" -> "/v1/mozilla/recovery-email/verify-code"
                    "/v1/recovery_email/resend_code" -> "/v1/mozilla/recovery-email/resend-code"
                    "/v1/session/verify_code" -> "/v1/mozilla/session/verify-code"
                    else -> null
                }
                target.host.equals("profile.accounts.firefox.com", true) && target.path == "/v1/profile" -> "/v1/mozilla/profile"
                target.host.equals("firefox.settings.services.mozilla.com", true) &&
                    target.path == "/v1/buckets/main/collections/vpn-serverlist/records" -> "/v1/mozilla/servers"
                else -> null
            }
            path?.takeIf { allowed(it, method) }
        }
    } catch (_: Exception) {
        null
    }

    fun forward(url: String, method: String, body: String?, headers: Map<String, String>, timeout: Int): FoxReply {
        val path = relayPath(url, method) ?: throw IOException("relay request is not permitted")
        val authorization = headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value
        val access = authorization?.takeIf { it.startsWith("Bearer ") }?.substring(7)
        var lastReply: FoxReply? = null
        var lastFailure: Exception? = null
        for (endpoint in FoxRelayDiscovery.relays()) {
            if (!FoxRelayDiscovery.isGoogle(endpoint) && path != "/v1/mozilla/servers") continue
            try {
                val reply = request(endpoint, path, method, access, body, authorization, timeout)
                reply.route = relayRoute(endpoint)
                FoxTrace.response(url, method, reply.route, reply.code, reply.body)
                if (!retry(reply.code)) return reply
                lastReply = reply
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        lastReply?.let { return it }
        throw lastFailure ?: IOException("no verified relay is configured")
    }

    fun call(path: String, method: String, access: String? = null, body: String? = null): FoxReply {
        var lastReply: FoxReply? = null
        var lastFailure: Exception? = null
        for (endpoint in FoxRelayDiscovery.relays()) {
            try {
                val reply = once(endpoint, path, method, access, body)
                if (!retry(reply.code)) return reply
                lastReply = reply
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        lastReply?.let { return it }
        throw lastFailure ?: IOException("no verified relay is configured")
    }

    fun once(endpoint: String, path: String, method: String, access: String? = null, body: String? = null): FoxReply {
        val reply = request(endpoint, path, method, access, body, access?.let { "Bearer $it" }, 20000)
        reply.route = relayRoute(endpoint)
        FoxTrace.response(upstream(path), method, reply.route, reply.code, reply.body)
        return reply
    }

    private fun relayRoute(endpoint: String): String = if (FoxRelayDiscovery.isGoogle(endpoint)) "apps-script" else "verified-relay"

    private fun upstream(path: String): String = when (path) {
        "/v1/mozilla/servers" -> FoxNet.SERVERS
        "/v1/mozilla/token" -> FoxNet.AUTH + "/token"
        "/v1/mozilla/oauth/token" -> FoxNet.AUTH + "/oauth/token"
        "/v1/mozilla/profile" -> "https://profile.accounts.firefox.com/v1/profile"
        "/v1/mozilla/recovery-email/status" -> FoxNet.AUTH + "/recovery_email/status"
        "/v1/mozilla/recovery-email/verify-code" -> FoxNet.AUTH + "/recovery_email/verify_code"
        "/v1/mozilla/recovery-email/resend-code" -> FoxNet.AUTH + "/recovery_email/resend_code"
        "/v1/mozilla/session/verify-code" -> FoxNet.AUTH + "/session/verify_code"
        else -> "invalid-endpoint"
    }

    private fun request(
        endpoint: String,
        path: String,
        method: String,
        access: String?,
        body: String?,
        authorization: String?,
        timeout: Int
    ): FoxReply {
        if (!FoxRelayDiscovery.validUrl(endpoint) || !allowed(path, method)) throw IOException("relay request is not permitted")
        if (body != null && body.toByteArray(Charsets.UTF_8).size > 4096) throw IOException("relay request body is too large")
        testHandler?.let { return it(Request(endpoint, path, method, access, body)) }
        val google = FoxRelayDiscovery.isGoogle(endpoint)
        val target = URL(if (google) endpoint else endpoint.removeSuffix("/") + path)
        val payload = if (google) {
            JSONObject()
                .put("v", 1)
                .put("path", path)
                .put("method", method)
                .put("authorization", authorization ?: JSONObject.NULL)
                .put("body", body ?: JSONObject.NULL)
                .toString()
        } else {
            body
        }
        // OkHttp: same trust model as before (system trust + SPKI pins for
        // the verified relays, plain Google trust for Apps Script), no
        // redirect following (Google redirects are validated by hand below).
        val builder = FoxHttp.builder(FoxNet.controlPortOf(), timeout)
        val client = if (google) builder.build() else FoxHttp.secureWith(builder, FoxPinnedTls.socketFactory())
        val methodName = if (google) "POST" else method
        val requestBuilder = okhttp3.Request.Builder()
            .url(target.toString())
            .method(methodName, payload?.let { FoxHttp.jsonBody(it) })
            .header("Accept", "application/json")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-store")
        if (!google && !authorization.isNullOrEmpty()) requestBuilder.header("Authorization", authorization)
        if (payload != null) requestBuilder.header("Content-Type", "application/json")
        val response = client.newCall(requestBuilder.build()).execute()
        return if (google) readGoogle(client, response, timeout, 0) else readDirect(response)
    }

    private fun readGoogle(
        client: okhttp3.OkHttpClient,
        response: okhttp3.Response,
        timeout: Int,
        redirects: Int
    ): FoxReply = response.use {
        val code = it.code
        if (code in setOf(301, 302, 303, 307, 308)) {
            if (redirects != 0) throw IOException("too many relay redirects")
            val location = it.header("Location") ?: throw IOException("missing relay redirect")
            val uri = URI(location)
            if (uri.scheme != "https" || uri.host != "script.googleusercontent.com" || uri.port != -1 || uri.userInfo != null || uri.fragment != null) {
                throw IOException("relay redirect is not permitted")
            }
            val redirectedRequest = okhttp3.Request.Builder()
                .url(uri.toString())
                .method("GET", null)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-store")
                .build()
            val redirected = client.newCall(redirectedRequest).execute()
            return readGoogle(client, redirected, timeout, 1)
        }
        val bodyBytes = it.body?.let { body -> FoxHttp.readBounded(body.byteStream(), MAX_RESPONSE_BYTES) } ?: ByteArray(0)
        val text = String(bodyBytes, Charsets.UTF_8)
        if (code != 200) throw IOException("Apps Script relay HTTP $code")
        try {
            val root = JSONObject(text)
            val status = root.optInt("status", 0)
            val body = root.optString("body")
            val source = root.optJSONObject("headers") ?: JSONObject()
            if (status !in 100..599 || root.length() != 3 || body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
                throw IOException("invalid Apps Script response")
            }
            val headers = HashMap<String, String>()
            for (name in listOf("Content-Type", "X-Quota-Unlimited", "X-Quota-Limit", "X-Quota-Remaining", "X-Quota-Reset")) {
                val value = source.optString(name)
                if (value.isNotEmpty() && value.length <= 1024) headers[name] = value
            }
            FoxReply(status, body, headers)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("invalid Apps Script response", error)
        }
    }

    private fun readDirect(response: okhttp3.Response): FoxReply = response.use {
        val code = it.code
        val bodyBytes = it.body?.let { body -> FoxHttp.readBounded(body.byteStream(), MAX_RESPONSE_BYTES) } ?: ByteArray(0)
        val text = String(bodyBytes, Charsets.UTF_8)
        val headers = HashMap<String, String>()
        for ((name, values) in it.headers.toMultimap()) {
            if (values.isEmpty() || name.equals("Set-Cookie", true)) continue
            headers[name] = values.joinToString(",")
        }
        FoxReply(code, text, headers)
    }

    internal fun installForTest(handler: ((Request) -> FoxReply)?): (() -> Unit) {
        val previous = testHandler
        testHandler = handler
        return { testHandler = previous }
    }

}
