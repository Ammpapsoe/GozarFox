package com.gozar

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Solver for the Fastly client challenge that fronts accounts.firefox.com.
 *
 * The edge serves a "Client Challenge" interstitial instead of the sign-in
 * page until the browser proves itself. This is an independent Kotlin
 * implementation of the exchange: probe the page, read the challenge prefix,
 * fetch the challenge script, parse its init() arguments without evaluating
 * any JavaScript, answer proof-of-work (and the auxiliary challenge kinds),
 * post the answers back for as many rounds as the edge issues, verify the
 * earned cookie against the real page, and hand the cookies to the caller
 * for injection into the WebView.
 *
 * مفهوم از challenge.go رقیب، پیاده‌سازی مستقل — every routine here is written
 * from the observed protocol, not transcribed from any other codebase.
 *
 * Nothing here may block the UI thread; the caller runs solve() in the
 * background. Tokens, cookies, and answers are never passed to [trace].
 */
class FastlyChallengeSolver(
    private val http: Transport = JdkTransport(),
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val requestTimeoutMs: Int = 10_000,
    private val maxRounds: Int = 8,
    private val overallBudgetMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val trace: (String) -> Unit = {},
) {

    /** Minimal HTTP surface so the solver stays unit-testable off-device. */
    interface Transport {
        fun send(request: Request): Response

        data class Request(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val body: ByteArray? = null,
        )

        data class Response(
            val status: Int,
            val headers: Map<String, List<String>>,
            val body: ByteArray,
        )
    }

    sealed class Outcome {
        /**
         * The edge accepted the answers and issued cookies for the site.
         * [cookies] maps name to value; [setCookieHeaders] keeps the full
         * Set-Cookie lines, attributes included, so the caller can hand them
         * to CookieManager verbatim and preserve the edge's own scoping.
         */
        data class Success(
            val cookies: Map<String, String>,
            val setCookieHeaders: List<String>,
        ) : Outcome()

        /** No challenge was served — the page loaded directly. */
        object NotChallenged : Outcome()

        /** The challenge needs a human (CAPTCHA) or is a kind we refuse. */
        data class Blocked(val reason: String) : Outcome()

        /** The exchange broke somewhere; [reason] names the stage only. */
        data class Failed(val reason: String) : Outcome()
    }

    fun solve(targetUrl: String): Outcome {
        val deadline = clock() + overallBudgetMs
        // A short random tag so one attempt's stage lines can be followed in
        // logcat without ever naming a token, a cookie, or a body.
        val attemptId = "edge-" + java.util.UUID.randomUUID().toString().take(8)
        // The jar must be empty: an already-solved client is served the real
        // page, and stale cookies would poison a fresh exchange.
        val jar = mutableMapOf<String, String>()
        val jarRaw = LinkedHashMap<String, String>()

        val probe = fetchPage(targetUrl, jar, jarRaw) ?: return Outcome.Failed("probe")
        val probeBody = utf8(probe.body)
        if (!isChallengeBody(probeBody)) return Outcome.NotChallenged
        val prefix = challengePrefix(probeBody) ?: return Outcome.Failed("prefix")
        trace("challenge prefix found")

        val scriptUrl = originOf(targetUrl) + prefix + "/script.js?reload=true"
        val script = get(scriptUrl, browserHeaders(), jar, jarRaw)
            ?: return Outcome.Failed("script-fetch")
        if (script.status != 200) {
            trace(
                "$attemptId script refused http=${script.status} " +
                    "fastly-trace=${headerOf(script, TRACE_HEADER)}"
            )
            return Outcome.Failed("script-http-" + script.status)
        }
        val scriptText = utf8(script.body)
        val init = parseInitArguments(scriptText) ?: return Outcome.Failed("init-parse")
        trace("init parsed")

        var challenges = init.challenges
        var token = init.token
        var round = 0
        while (true) {
            if (clock() > deadline) return Outcome.Failed("budget")
            if (++round > maxRounds) return Outcome.Failed("rounds-exhausted")

            val answers = mutableListOf<JSONObject>()
            for (challenge in challenges) {
                val answer = answerChallenge(
                    challenge,
                    originOf(targetUrl),
                    prefix,
                    token,
                    jar,
                    jarRaw,
                )
                    ?: return Outcome.Blocked(challengeKind(challenge))
                answers += answer
            }

            val payload = JSONObject()
                .put("token", token)
                .put("data", JSONArray(answers))
            val post = post(
                originOf(targetUrl) + prefix + "/fst-post-back",
                payload.toString().toByteArray(Charsets.UTF_8),
                jar,
                jarRaw,
            ) ?: return Outcome.Failed("post-back")
            absorbCookies(post, jar, jarRaw)
            if (post.status != 200) {
                trace(
                    "$attemptId post-back refused http=${post.status} " +
                    "fastly-trace=${headerOf(post, TRACE_HEADER)} round=$round"
                )
                return Outcome.Failed("post-back-http-" + post.status)
            }

            val reply = runCatching { JSONObject(utf8(post.body)) }.getOrNull()
                ?: return Outcome.Failed("post-back-parse")
            when (reply.optString("status")) {
                "success" -> {
                    // The cookie is bound to this exit and identity; a probe
                    // is the only proof that it is actually honored.
                    val check = fetchPage(targetUrl, jar, jarRaw)
                        ?: return Outcome.Failed("probe-after")
                    if (isChallengeBody(utf8(check.body))) {
                        return Outcome.Failed("cookie-not-honored")
                    }
                    if (jar.isEmpty()) return Outcome.Failed("no-cookies")
                    trace("solved in $round round(s)")
                    return Outcome.Success(jar.toMap(), jarRaw.values.toList())
                }
                "blocked" -> return Outcome.Blocked("server-blocked")
            }

            challenges = reply.optJSONArray("ch")?.let { list ->
                (0 until list.length()).map { list.optJSONObject(it) }.filterNotNull()
            } ?: return Outcome.Failed("next-challenges")
            token = reply.optString("tok").ifEmpty { token }
            trace("round $round answered, edge issued another")
        }
    }

    // ---------------------------------------------------------------- answers

    private fun answerChallenge(
        challenge: JSONObject,
        origin: String,
        prefix: String,
        token: String,
        jar: MutableMap<String, String>,
        jarRaw: MutableMap<String, String>,
    ): JSONObject? {
        val kind = challenge.optString("ty")
        val data = challenge.optJSONObject("data") ?: return null
        return when (kind) {
            "pow" -> {
                val base = data.optString("base")
                val suffix = solvePow(base, data.optString("hash")) ?: return null
                JSONObject()
                    .put("ty", "pow")
                    .put("base", base)
                    .put("answer", suffix)
                    // Echoed back verbatim so the edge can re-verify: these
                    // are the challenge's own fields, not anything we invent.
                    // The edge issues `expires` as a JSON string and rejects
                    // type-changed echoes with HTTP 400 — never coerce it.
                    .put("hmac", data.optString("hmac"))
                    .put("expires", echoBack(data, "expires"))
            }
            "pat" -> {
                // A Private Access Token is a browser-platform feature this
                // client cannot mint. Answering with an empty auth is the
                // documented abort: the edge then falls back to PoW.
                JSONObject()
                    .put("ty", "pat")
                    .put("auth", fetchPat(origin + prefix, token, jar, jarRaw))
            }
            "clientmetrics" -> clientMetricsAnswer()
            else -> null // captcha and anything unknown need a human
        }
    }

    private fun fetchPat(
        prefixUrl: String,
        token: String,
        jar: MutableMap<String, String>,
        jarRaw: MutableMap<String, String>,
    ): String {
        val headers = browserHeaders() + mapOf(
            "Accept" to "text/plain",
            "Content-Type" to "application/json",
            "Origin" to originOf(prefixUrl),
        )
        val request = Transport.Request(
            "POST",
            prefixUrl + "/pat?token=" + URLEncoder.encode(token, "UTF-8"),
            withJar(headers, jar),
            ByteArray(0),
        )
        val response = runCatching { http.send(request) }.getOrNull() ?: return ""
        absorbCookies(response, jar, jarRaw)
        if (response.status != 200) {
            trace("pat endpoint answered http=${response.status}")
            return ""
        }
        return runCatching { JSONObject(utf8(response.body)).optString("auth") }.getOrDefault("")
    }

    private fun clientMetricsAnswer(): JSONObject = JSONObject()
        .put("ty", "clientmetrics")
        .put("webdriver", false)
        .put("bot_detection_result", JSONObject().put("bot_detected", false))
        .put("browser_metrics", JSONObject().put("client_data", "{}"))
        .put("detector_results", JSONObject())
        .put("v", 2)

    companion object {
        /** The response header Fastly's own script reads when it gets a 400. */
        private const val TRACE_HEADER = "Fastly-Trace-ID"

        /** The canonical Firefox-for-Android identity — no Chromium bones. */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Android 13; Mobile; rv:145.0) Gecko/145.0 Firefox/145.0"

        private val POW_ALPHABET =
            ('a'..'z') + ('A'..'Z') + ('0'..'9')

        /**
         * The proof of work is a whole-hash match, not a difficulty target:
         * SHA-256(base + suffix) must equal the issued hash exactly. The
         * suffix is two alphabet characters, so the search space is 62*62 =
         * 3844 hashes — milliseconds even on a low-end phone.
         */
        internal fun solvePow(base: String, targetHex: String): String? {
            val target = decodeHex(targetHex) ?: return null
            if (target.size != 32) return null
            val prefixBytes = base.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(prefixBytes.size + 2)
            System.arraycopy(prefixBytes, 0, buffer, 0, prefixBytes.size)
            for (first in POW_ALPHABET) {
                buffer[prefixBytes.size] = first.code.toByte()
                for (second in POW_ALPHABET) {
                    buffer[prefixBytes.size + 1] = second.code.toByte()
                    digest.reset()
                    if (digest.digest(buffer).contentEquals(target)) {
                        return "" + first + second
                    }
                }
            }
            return null
        }

        internal fun decodeHex(hex: String): ByteArray? {
            if (hex.isEmpty() || hex.length % 2 != 0) return null
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val high = Character.digit(hex[i * 2], 16)
                val low = Character.digit(hex[i * 2 + 1], 16)
                if (high < 0 || low < 0) return null
                out[i] = ((high shl 4) or low).toByte()
            }
            return out
        }

        /** True when the served body is the Fastly interstitial. */
        internal fun isChallengeBody(body: String): Boolean =
            body.contains("/_fs-ch-") && body.contains("Client Challenge")

        /** First "/_fs-ch-<token>" occurrence, or null. */
        internal fun challengePrefix(body: String): String? {
            val start = body.indexOf("/_fs-ch-")
            if (start < 0) return null
            var end = start + "/_fs-ch-".length
            while (end < body.length && body[end].isLetterOrDigit()) end++
            if (end == start + "/_fs-ch-".length) return null
            return body.substring(start, end)
        }

        internal data class InitArgs(
            val challenges: List<JSONObject>,
            val token: String,
            val scriptPrefix: String,
        )

        /**
         * Extracts the init(...) arguments from the challenge script without
         * executing it. Arguments are split at top-level commas — JSON never
         * contains a bare comma or parenthesis outside a string, and strings
         * in these payloads carry no escapes that could hide one. The last
         * well-formed call wins: a page may embed a decoy or a stale copy.
         */
        internal fun parseInitArguments(script: String): InitArgs? {
            var searchFrom = 0
            var best: InitArgs? = null
            while (true) {
                val at = script.indexOf("init(", searchFrom)
                if (at < 0) break
                searchFrom = at + 5
                val args = splitTopLevel(script, at + 5) ?: continue
                if (args.size < 3) continue
                val challenges = runCatching { JSONArray(args[0]) }.getOrNull() ?: continue
                val token = runCatching { JSONArray("[" + args[1] + "]") }
                    .getOrNull()?.optString(0)?.takeIf { it.isNotEmpty() } ?: continue
                val prefix = runCatching { JSONArray("[" + args[2] + "]") }
                    .getOrNull()?.optString(0) ?: continue
                if (!prefix.startsWith("/_fs-ch-")) continue
                val parsed = (0 until challenges.length())
                    .map { challenges.optJSONObject(it) }
                    .filterNotNull()
                if (parsed.isEmpty()) continue
                best = InitArgs(parsed, token, prefix)
            }
            return best
        }

        private fun splitTopLevel(script: String, from: Int): List<String>? {
            val args = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var i = from
            while (i < script.length) {
                val c = script[i]
                when {
                    c == '{' || c == '[' -> { depth++; current.append(c) }
                    c == '}' || c == ']' -> {
                        if (depth == 0) return null // unbalanced: not our call
                        depth--; current.append(c)
                    }
                    c == '(' -> { depth++; current.append(c) }
                    c == ')' -> {
                        if (depth == 0) {
                            args += current.toString()
                            return args
                        }
                        depth--; current.append(c)
                    }
                    c == ',' && depth == 0 -> {
                        args += current.toString()
                        current.setLength(0)
                    }
                    else -> current.append(c)
                }
                i++
            }
            return null
        }

        private fun originOf(url: String): String {
            val scheme = url.indexOf("://")
            if (scheme < 0) return url
            val hostEnd = url.indexOf('/', scheme + 3)
            return if (hostEnd < 0) url else url.substring(0, hostEnd)
        }

        private fun utf8(bytes: ByteArray) = String(bytes, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------- HTTP

    private fun browserHeaders(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
    )

    private fun fetchPage(
        url: String,
        jar: MutableMap<String, String>,
        jarRaw: MutableMap<String, String>,
    ): Transport.Response? = get(url, browserHeaders(), jar, jarRaw)

    private fun get(
        url: String,
        headers: Map<String, String>,
        jar: MutableMap<String, String>,
        jarRaw: MutableMap<String, String>,
    ): Transport.Response? {
        val response = runCatching { http.send(Transport.Request("GET", url, withJar(headers, jar))) }
            .getOrNull() ?: return null
        absorbCookies(response, jar, jarRaw)
        return response
    }

    private fun post(
        url: String,
        body: ByteArray,
        jar: MutableMap<String, String>,
        jarRaw: MutableMap<String, String>,
    ): Transport.Response? {
        val headers = browserHeaders() + mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Origin" to originOf(url),
        )
        val response = runCatching {
            http.send(Transport.Request("POST", url, withJar(headers, jar), body))
        }.getOrNull() ?: return null
        absorbCookies(response, jar, jarRaw)
        return response
    }

    private fun withJar(
        headers: Map<String, String>,
        jar: Map<String, String>,
    ): Map<String, String> {
        if (jar.isEmpty()) return headers
        val cookie = jar.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        return headers + ("Cookie" to cookie)
    }

    private fun absorbCookies(
        response: Transport.Response,
        jar: MutableMap<String, String>,
        jarRaw: MutableMap<String, String>,
    ) {
        val sets = response.headers.entries
            .filter { (name, _) -> name.equals("set-cookie", ignoreCase = true) }
            .flatMap { (_, values) -> values }
        for (raw in sets) {
            val pair = raw.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            jar[name] = pair.substring(eq + 1).trim()
            jarRaw[name] = raw.trim()
        }
    }

    private fun challengeKind(challenge: JSONObject): String =
        challenge.optString("ty").ifEmpty { "unknown" }

    /** The field exactly as the edge issued it; missing or null echoes "". */
    private fun echoBack(data: JSONObject, key: String): Any =
        if (data.has(key) && !data.isNull(key)) data.get(key) else ""

    /** Case-insensitive header read; "-" when absent. Never the body. */
    private fun headerOf(response: Transport.Response, name: String): String =
        response.headers.entries
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()?.take(64) ?: "-"

    /**
     * [HttpURLConnection] pinned to the loopback proxy so the solver's exit
     * IP — and therefore the cookie binding — matches the WebView's traffic.
     * Redirects stay disabled: a 302 would swallow the Set-Cookie headers
     * this exchange lives on, and the probe only needs the first response.
     */
    class JdkTransport(
        private val proxyHost: String? = null,
        private val proxyPort: Int = 0,
    ) : Transport {

        override fun send(request: Transport.Request): Transport.Response {
            val url = URL(request.url)
            val proxy = if (proxyHost != null && proxyPort > 0) {
                Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
            } else {
                Proxy.NO_PROXY
            }
            val connection = url.openConnection(proxy) as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = request.method
                for ((name, value) in request.headers) {
                    connection.setRequestProperty(name, value)
                }
                val body = request.body
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..399) connection.inputStream else connection.errorStream
                val raw = stream?.use { readFully(it) } ?: ByteArray(0)
                val decoded = if ("gzip".equals(connection.contentEncoding, true)) {
                    GZIPInputStream(raw.inputStream()).use { readFully(it) }
                } else {
                    raw
                }
                val headers = mutableMapOf<String, List<String>>()
                for ((name, values) in connection.headerFields) {
                    if (name != null && values != null) headers[name] = values
                }
                return Transport.Response(status, headers, decoded)
            } finally {
                connection.disconnect()
            }
        }

        private fun readFully(stream: java.io.InputStream): ByteArray {
            val sink = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
            }
            return sink.toByteArray()
        }
    }
}
