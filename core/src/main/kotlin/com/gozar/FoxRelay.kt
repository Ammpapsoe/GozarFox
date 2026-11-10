package com.gozar

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object FoxRelay {
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    internal data class Request(val endpoint: String, val path: String, val method: String, val access: String?, val body: String?)
    @Volatile private var testHandler: ((Request) -> FoxReply)? = null

    fun retry(status: Int): Boolean = status == 408 || status == 425 || status == 429 || status == 451 || status in 500..599

    fun allowed(path: String, method: String): Boolean = when (path) {
        "/v1/guardian/pass" -> method == "GET" || method == "HEAD"
        "/v1/guardian/activate" -> method == "POST"
        "/v1/mozilla/servers" -> method == "GET"
        else -> false
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
        if (!FoxRelayDiscovery.validUrl(endpoint) || !allowed(path, method)) throw IOException("relay request is not permitted")
        if (body != null && body.toByteArray(Charsets.UTF_8).size > 4096) throw IOException("relay request body is too large")
        testHandler?.let { return it(Request(endpoint, path, method, access, body)) }
        val target = URL(endpoint.removeSuffix("/") + path)
        val connection = FoxNet.open(target) as HttpsURLConnection
        connection.sslSocketFactory = FoxPinnedTls.socketFactory()
        connection.requestMethod = method
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-store")
        if (!access.isNullOrEmpty()) connection.setRequestProperty("Authorization", "Bearer $access")
        if (body != null) connection.setRequestProperty("Content-Type", "application/json")
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { String(readBounded(it), Charsets.UTF_8) } ?: ""
            val headers = HashMap<String, String>()
            for ((name, values) in connection.headerFields) {
                if (name != null && values.isNotEmpty() && name.equals("Set-Cookie", true).not()) {
                    headers[name] = values.joinToString(",")
                }
            }
            return FoxReply(code, text, headers)
        } finally {
            connection.disconnect()
        }
    }

    internal fun installForTest(handler: ((Request) -> FoxReply)?): (() -> Unit) {
        val previous = testHandler
        testHandler = handler
        return { testHandler = previous }
    }

    private fun readBounded(input: InputStream): ByteArray = input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            if (output.size() > MAX_RESPONSE_BYTES) throw IOException("relay response is too large")
        }
        output.toByteArray()
    }
}
