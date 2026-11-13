package com.gozar

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Walks the WebView's HTTP identity through the edge's "Client Challenge"
 * interstitial on accounts.firefox.com and comes back with the pass cookies
 * the real sign-in page needs.
 *
 * The exchange, in one breath: ask for the page; when the interstitial
 * answers, take its marker; fetch the challenge script; read its briefing
 * (see [EdgeBriefing]); answer each task (see [ProofSolver] for the
 * work proof); deliver the answers; repeat for as many rounds as the edge
 * issues; then prove the earned pass actually opens the page before handing
 * the cookies over.
 *
 * Design rules this class lives by:
 * - Every secret (pass token, cookies, answers) stays in memory only, dies
 *   with the attempt, and never reaches [trace] — the attempt tag and edge
 *   trace ids are the most identifying anything logged ever gets.
 * - Every stage has a bound: [stepTimeoutMs] per HTTP step, [budgetMs] for
 *   the whole walk, [roundCap] for edge-issued rounds, and [cancel] for the
 *   person's own change of mind.
 * - Failure is always a verdict, never a hang: the caller gets [Verdict] and
 *   decides what the screen says next.
 *
 * A protocol-level implementation written for this codebase; naming and
 * structure are our own.
 */
class EdgeGuard(
    private val wire: Wire = JdkWire(),
    private val agent: String = ANDROID_FIREFOX_AGENT,
    private val stepTimeoutMs: Int = 10_000,
    private val roundCap: Int = 8,
    private val budgetMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notify: (Stage) -> Unit = {},
    private val trace: (String) -> Unit = {},
) {

    /** The HTTP surface the walk needs; fake it in tests, pin it to the
     *  loopback tunnel on device. */
    interface Wire {
        fun exchange(request: Request): Reply

        data class Request(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val body: ByteArray? = null,
        )

        data class Reply(
            val status: Int,
            val headers: Map<String, List<String>>,
            val body: ByteArray,
        )
    }

    /** Where the walk is, for the screen; [percent] is stage-honest, not a
     *  guess about the network. */
    enum class Stage(val percent: Int) {
        PROBING(10),
        FETCHING_SCRIPT(30),
        READING_BRIEFING(45),
        SOLVING(60),
        DELIVERING(80),
        CONFIRMING(95),
        CLEARED(100),
    }

    sealed class Verdict {
        /** The pass works: the page opened and these cookies proved it. */
        data class Cleared(
            val cookies: Map<String, String>,
            val setCookieLines: List<String>,
        ) : Verdict()

        /** No interstitial was served; the page is already the real one. */
        object NoChallenge : Verdict()

        /** The edge asked for a person (image puzzle) or refused the crowd. */
        data class NeedsHuman(val why: String) : Verdict()

        /** The walk ended for a mechanical reason; [why] names the stage. */
        data class GaveUp(val why: String) : Verdict()
    }

    @Volatile
    private var calledOff = false

    /** Ask a running [run] to stop at its next safe point. */
    fun cancel() {
        calledOff = true
    }

    fun resolve(targetUrl: String): Verdict {
        val attempt = "edge-" + UUID.randomUUID().toString().take(8)
        val deadline = clock() + budgetMs
        val pouch = CookiePouch()
        try {
            notify(Stage.PROBING)
            val probe = sendGet(targetUrl, pageHeaders(), pouch)
                ?: return callOffOr("probe-unreachable")
            val probeText = probe.text()
            if (!isInterstitial(probeText)) return Verdict.NoChallenge
            val marker = markerOf(probeText) ?: return Verdict.GaveUp("no-marker")
            trace("$attempt interstitial seen")

            if (stopped()) return Verdict.GaveUp(CALLED_OFF)
            notify(Stage.FETCHING_SCRIPT)
            val origin = originOf(targetUrl)
            val scriptReply = sendGet(
                origin + marker + "/script.js?reload=true",
                scriptHeaders(),
                pouch,
            ) ?: return callOffOr("script-unreachable")
            if (scriptReply.status != 200) {
                trace(
                    "$attempt script refused http=${scriptReply.status} " +
                        "fastly-trace=${headerOf(scriptReply)}"
                )
                return Verdict.GaveUp("script-refused-${scriptReply.status}")
            }

            if (stopped()) return Verdict.GaveUp(CALLED_OFF)
            notify(Stage.READING_BRIEFING)
            val briefing = EdgeBriefing.extract(scriptReply.text(), marker)
                ?: return Verdict.GaveUp("briefing-unreadable")
            trace("$attempt briefing read tasks=${briefing.tasks.size}")

            var tasks = briefing.tasks
            var passToken = briefing.passToken
            var round = 0
            while (true) {
                if (stopped()) return Verdict.GaveUp(CALLED_OFF)
                if (clock() > deadline) return Verdict.GaveUp("out-of-time")
                if (++round > roundCap) return Verdict.GaveUp("rounds-used-up")

                notify(Stage.SOLVING)
                val answers = mutableListOf<JSONObject>()
                for (task in tasks) {
                    when (val prepared = prepareAnswer(task, origin, marker, passToken, pouch)) {
                        is Prepared.Ready -> answers += prepared.answer
                        is Prepared.Human -> return Verdict.NeedsHuman(prepared.kind)
                        is Prepared.Stuck -> return Verdict.GaveUp(prepared.why)
                    }
                }

                notify(Stage.DELIVERING)
                val envelope = JSONObject()
                    .put("token", passToken)
                    .put("data", JSONArray(answers))
                val delivery = sendPost(
                    origin + marker + DELIVERY_PATH,
                    envelope.toString().toByteArray(Charsets.UTF_8),
                    pouch,
                ) ?: return callOffOr("delivery-unreachable")
                if (delivery.status != 200) {
                    trace(
                        "$attempt delivery refused http=${delivery.status} " +
                            "fastly-trace=${headerOf(delivery)} round=$round"
                    )
                    return Verdict.GaveUp("delivery-refused-${delivery.status}")
                }
                val receipt = runCatching { JSONObject(delivery.text()) }.getOrNull()
                    ?: return Verdict.GaveUp("receipt-unreadable")
                when (receipt.optString("status")) {
                    "success" -> {
                        if (stopped()) return Verdict.GaveUp(CALLED_OFF)
                        notify(Stage.CONFIRMING)
                        // The pass is bound to this exit and this identity;
                        // only reopening the page proves it is honored.
                        val confirmation = sendGet(targetUrl, pageHeaders(), pouch)
                            ?: return callOffOr("confirm-unreachable")
                        if (isInterstitial(confirmation.text())) {
                            return Verdict.GaveUp("pass-not-honored")
                        }
                        if (pouch.isEmpty()) return Verdict.GaveUp("pass-empty")
                        trace("$attempt cleared in $round round(s)")
                        notify(Stage.CLEARED)
                        return Verdict.Cleared(pouch.snapshot(), pouch.rawLines())
                    }
                    "blocked" -> return Verdict.NeedsHuman("edge-blocked")
                }
                val nextTasks = receipt.optJSONArray("ch")
                    ?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } }
                    ?: return Verdict.GaveUp("next-tasks-missing")
                tasks = nextTasks
                passToken = receipt.optString("tok").ifEmpty { passToken }
                trace("$attempt round $round answered; edge issued another")
            }
        } finally {
            // Whatever the verdict, the pouch's secrets die here; only the
            // copies inside Cleared outlive the attempt.
            pouch.clear()
        }
    }

    // ------------------------------------------------------------ answers

    private sealed class Prepared {
        data class Ready(val answer: JSONObject) : Prepared()
        data class Human(val kind: String) : Prepared()
        data class Stuck(val why: String) : Prepared()
    }

    private fun prepareAnswer(
        task: JSONObject,
        origin: String,
        marker: String,
        passToken: String,
        pouch: CookiePouch,
    ): Prepared {
        val kind = task.optString("ty")
        val data = task.optJSONObject("data") ?: return Prepared.Stuck("task-without-data")
        return when (kind) {
            WORK_TASK -> {
                val base = data.optString("base")
                val suffix = ProofSolver.findPowSuffix(
                    base,
                    data.optString("hash"),
                    keepGoing = { !stopped() },
                ) ?: return Prepared.Stuck("work-unsolved")
                Prepared.Ready(
                    JSONObject()
                        .put("ty", WORK_TASK)
                        .put("base", base)
                        .put("answer", suffix)
                        // Both fields go back byte-for-byte in the type the
                        // edge issued: it validates strictly, and a coerced
                        // number where a string was served is a refusal.
                        .put("hmac", echoBack(data, "hmac"))
                        .put("expires", echoBack(data, "expires")),
                )
            }
            ACCESS_TOKEN_TASK -> Prepared.Ready(
                // The private access token is a browser-platform feature no
                // HTTP client can mint; an empty auth is the documented
                // abort that makes the edge fall back to work proofs.
                JSONObject()
                    .put("ty", ACCESS_TOKEN_TASK)
                    .put("auth", requestPatAuth(origin + marker, passToken, pouch)),
            )
            METRICS_TASK -> Prepared.Ready(metricsReply())
            else -> Prepared.Human(kind.ifEmpty { "unknown" })
        }
    }

    private fun requestPatAuth(
        markerUrl: String,
        passToken: String,
        pouch: CookiePouch,
    ): String {
        val headers = browserHeaders() + mapOf(
            "Accept" to "text/plain",
            "Content-Type" to "application/json",
            "Origin" to originOf(markerUrl),
        )
        val request = Wire.Request(
            "POST",
            markerUrl + "/pat?token=" + URLEncoder.encode(passToken, "UTF-8"),
            pouch.decorate(headers),
            ByteArray(0),
        )
        val reply = runCatching { wire.exchange(request) }.getOrNull() ?: return ""
        pouch.absorb(reply)
        if (reply.status != 200) {
            trace("pat endpoint answered http=${reply.status}")
            return ""
        }
        return runCatching { JSONObject(reply.text()).optString("auth") }.getOrDefault("")
    }

    private fun metricsReply(): JSONObject = JSONObject()
        .put("ty", METRICS_TASK)
        .put("webdriver", false)
        .put("bot_detection_result", JSONObject().put("bot_detected", false))
        .put("browser_metrics", JSONObject().put("client_data", "{}"))
        .put("detector_results", JSONObject())
        .put("v", 2)

    /** The field exactly as the edge issued it; missing or null echoes "". */
    private fun echoBack(data: JSONObject, key: String): Any =
        if (data.has(key) && !data.isNull(key)) data.get(key) else ""

    // --------------------------------------------------------------- HTTP

    private fun browserHeaders(): Map<String, String> = mapOf(
        "User-Agent" to agent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "identity",
    )

    private fun pageHeaders(): Map<String, String> = browserHeaders()

    private fun scriptHeaders(): Map<String, String> =
        browserHeaders() + ("Accept" to "*/*")

    private fun sendGet(
        url: String,
        headers: Map<String, String>,
        pouch: CookiePouch,
    ): Wire.Reply? {
        val reply = runCatching {
            wire.exchange(Wire.Request("GET", url, pouch.decorate(headers)))
        }.getOrNull() ?: return null
        pouch.absorb(reply)
        return reply
    }

    private fun sendPost(
        url: String,
        body: ByteArray,
        pouch: CookiePouch,
    ): Wire.Reply? {
        val headers = browserHeaders() + mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Origin" to originOf(url),
        )
        val reply = runCatching {
            wire.exchange(Wire.Request("POST", url, pouch.decorate(headers), body))
        }.getOrNull() ?: return null
        pouch.absorb(reply)
        return reply
    }

    private fun stopped(): Boolean = calledOff || Thread.currentThread().isInterrupted

    private fun callOffOr(why: String): Verdict =
        if (stopped()) Verdict.GaveUp(CALLED_OFF) else Verdict.GaveUp(why)

    /**
     * Attempt-scoped cookie memory. Values feed the Cookie header of later
     * steps; raw Set-Cookie lines (attributes intact) go to the WebView's
     * CookieManager so the edge's own scoping survives the hand-over.
     * Nothing here is ever written to disk or to a log.
     */
    private class CookiePouch {
        private val values = LinkedHashMap<String, String>()
        private val lines = LinkedHashMap<String, String>()

        fun absorb(reply: Wire.Reply) {
            val served = reply.headers.entries
                .filter { (name, _) -> name.equals("set-cookie", ignoreCase = true) }
                .flatMap { (_, values) -> values }
            for (raw in served) {
                val pair = raw.substringBefore(';').trim()
                val equals = pair.indexOf('=')
                if (equals <= 0) continue
                val name = pair.substring(0, equals).trim()
                values[name] = pair.substring(equals + 1).trim()
                lines[name] = raw.trim()
            }
        }

        fun decorate(headers: Map<String, String>): Map<String, String> {
            if (values.isEmpty()) return headers
            val cookie = values.entries.joinToString("; ") { (name, value) -> "$name=$value" }
            return headers + ("Cookie" to cookie)
        }

        fun isEmpty(): Boolean = values.isEmpty()
        fun snapshot(): Map<String, String> = values.toMap()
        fun rawLines(): List<String> = lines.values.toList()
        fun clear() {
            values.clear()
            lines.clear()
        }
    }

    companion object {
        /** The identity the WebView itself presents; the pass is bound to it. */
        const val ANDROID_FIREFOX_AGENT =
            "Mozilla/5.0 (Android 14; Mobile; rv:145.0) Gecko/145.0 Firefox/145.0"

        private const val DELIVERY_PATH = "/fst-post-back"
        private const val WORK_TASK = "pow"
        private const val ACCESS_TOKEN_TASK = "pat"
        private const val METRICS_TASK = "clientmetrics"
        private const val CALLED_OFF = "called-off"
        private const val TRACE_HEADER = "Fastly-Trace-ID"

        /** True when the served text is the interstitial, not the page. */
        internal fun isInterstitial(body: String): Boolean =
            body.contains("/_fs-ch-") && body.contains("Client Challenge")

        /** First `/_fs-ch-<id>` marker in the text, or null. */
        internal fun markerOf(body: String): String? {
            val start = body.indexOf("/_fs-ch-")
            if (start < 0) return null
            var end = start + "/_fs-ch-".length
            while (end < body.length && body[end].isLetterOrDigit()) end++
            if (end == start + "/_fs-ch-".length) return null
            return body.substring(start, end)
        }

        internal fun originOf(url: String): String {
            val scheme = url.indexOf("://")
            if (scheme < 0) return url
            val hostEnd = url.indexOf('/', scheme + 3)
            return if (hostEnd < 0) url else url.substring(0, hostEnd)
        }

        /** Case-insensitive header read for diagnostics; "-" when absent. */
        internal fun headerOf(reply: Wire.Reply, name: String = TRACE_HEADER): String =
            reply.headers.entries
                .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
                ?.value?.firstOrNull()?.take(64) ?: "-"
    }

    private fun Wire.Reply.text(): String = String(body, Charsets.UTF_8)

    /**
     * Plain [HttpURLConnection] over the loopback tunnel, so this walk shares
     * the WebView's exit and the pass it earns is bound to the same identity
     * the page will present. Redirects stay off: a 3xx would swallow the
     * Set-Cookie lines the exchange lives on.
     */
    class JdkWire(
        private val tunnelHost: String? = null,
        private val tunnelPort: Int = 0,
        private val stepTimeoutMs: Int = 10_000,
    ) : Wire {

        override fun exchange(request: Wire.Request): Wire.Reply {
            val proxy = if (tunnelHost != null && tunnelPort > 0) {
                Proxy(Proxy.Type.HTTP, InetSocketAddress(tunnelHost, tunnelPort))
            } else {
                Proxy.NO_PROXY
            }
            val connection = URL(request.url).openConnection(proxy) as HttpURLConnection
            try {
                connection.connectTimeout = stepTimeoutMs
                connection.readTimeout = stepTimeoutMs
                connection.instanceFollowRedirects = false
                connection.requestMethod = request.method
                for ((name, value) in request.headers) {
                    connection.setRequestProperty(name, value)
                }
                val outgoing = request.body
                if (outgoing != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(outgoing) }
                }
                val status = connection.responseCode
                val stream =
                    if (status in 200..399) connection.inputStream else connection.errorStream
                val raw = stream?.use { drain(it) } ?: ByteArray(0)
                val payload = if ("gzip".equals(connection.contentEncoding, true)) {
                    GZIPInputStream(raw.inputStream()).use { drain(it) }
                } else {
                    raw
                }
                val headers = mutableMapOf<String, List<String>>()
                for ((name, values) in connection.headerFields) {
                    if (name != null && values != null) headers[name] = values
                }
                return Wire.Reply(status, headers, payload)
            } finally {
                connection.disconnect()
            }
        }

        private fun drain(stream: InputStream): ByteArray {
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
