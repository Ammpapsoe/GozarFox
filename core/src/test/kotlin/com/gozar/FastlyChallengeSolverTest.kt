package com.gozar

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [FastlyChallengeSolver] against a scripted edge. The fixtures mirror
 * the live accounts.firefox.com exchange: a Client Challenge page carrying a
 * script prefix, an init(...) call inside script.js, and fst-post-back
 * responses that either bless the answers or issue another round.
 */
class FastlyChallengeSolverTest {

    private val target = "https://accounts.firefox.com/?context=fx_desktop_v3&service=sync&action=email"
    private val origin = "https://accounts.firefox.com"
    private val prefix = "/_fs-ch-1T1wmsGaOgGaSxcX"

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun challengePage() = """
        <!doctype html><html><head><title>Client Challenge</title></head><body>
        <script src="$prefix/script.js?reload=true"></script>
        </body></html>
    """.trimIndent()

    private fun realPage() = """
        <!doctype html><html><head><title>Mozilla accounts sign-in</title></head>
        <body><form id="main-content"></form></body></html>
    """.trimIndent()

    private fun powChallenge(base: String, suffix: String) = JSONObject()
        .put("ty", "pow")
        .put(
            "data",
            JSONObject()
                .put("base", base)
                .put("hash", sha256Hex(base + suffix))
                .put("hmac", "hmac-fixture")
                .put("expires", "1700000000000"),
        )

    private fun scriptWith(vararg initCalls: String) =
        initCalls.joinToString(separator = "\n", postfix = "\n")

    /** Records every request and replays canned responses in order. */
    private class ScriptedEdge(
        private val responses: List<Pair<Int, String>>,
        private val headers: List<Map<String, List<String>>> = emptyList(),
    ) : FastlyChallengeSolver.Transport {
        val requests = mutableListOf<FastlyChallengeSolver.Transport.Request>()

        override fun send(request: FastlyChallengeSolver.Transport.Request)
            : FastlyChallengeSolver.Transport.Response {
            requests += request
            val index = requests.size - 1
            assertTrue("edge ran out of scripted responses", index < responses.size)
            val (status, body) = responses[index]
            val header = headers.getOrElse(index) { emptyMap() }
            return FastlyChallengeSolver.Transport.Response(
                status,
                header,
                body.toByteArray(Charsets.UTF_8),
            )
        }
    }

    private fun solverFor(
        edge: FastlyChallengeSolver.Transport,
        trace: (String) -> Unit = {},
    ) = FastlyChallengeSolver(http = edge, trace = trace)

    // ------------------------------------------------------------------- PoW

    @Test
    fun `pow solver finds the two-character suffix for a real hash`() {
        val base = "fs-ch-base-fixture"
        val suffix = FastlyChallengeSolver.solvePow(base, sha256Hex(base + "Qz"))
        assertEquals("Qz", suffix)
    }

    @Test
    fun `pow solver rejects malformed and unmatched targets`() {
        assertNull(FastlyChallengeSolver.solvePow("base", "not-hex!!"))
        assertNull(FastlyChallengeSolver.solvePow("base", "abcd")) // too short
        // A random full hash has no two-character preimage; must give up.
        assertNull(
            FastlyChallengeSolver.solvePow(
                "base",
                "0000000000000000000000000000000000000000000000000000000000000000",
            ),
        )
    }

    // ------------------------------------------------------- script parsing

    @Test
    fun `init parser takes the last well-formed call and ignores decoys`() {
        val stale = """init([{"ty":"pow","data":{"base":"old"}}], "stale-token", "$prefix")"""
        val fresh = """init([{"ty":"pow","data":{"base":"new"}}], "fresh-token", "$prefix")"""
        val parsed = FastlyChallengeSolver.parseInitArguments(scriptWith(stale, fresh))
        assertNotNull(parsed)
        assertEquals("fresh-token", parsed!!.token)
        assertEquals(1, parsed.challenges.size)
        assertEquals("new", parsed.challenges[0].getJSONObject("data").getString("base"))
    }

    @Test
    fun `init parser survives legacy comment after the closing paren`() {
        val call = """init([{"ty":"pow","data":{"base":"b"}}], "tok", "$prefix")/*legacy*/;"""
        val parsed = FastlyChallengeSolver.parseInitArguments(call)
        assertNotNull(parsed)
        assertEquals("tok", parsed!!.token)
    }

    @Test
    fun `init parser rejects scripts without a usable call`() {
        assertNull(FastlyChallengeSolver.parseInitArguments("var x = 1;"))
        // Right shape, wrong prefix — not a challenge script we understand.
        assertNull(
            FastlyChallengeSolver.parseInitArguments(
                """init([{"ty":"pow","data":{}}], "tok", "/other")""",
            ),
        )
        // Unbalanced call must not consume the rest of the file.
        assertNull(FastlyChallengeSolver.parseInitArguments("init([{\"ty\":\"pow\""))
    }

    // -------------------------------------------------------- whole exchange

    @Test
    fun `solved pow is posted back and earned cookie is verified then returned`() {
        val setCookie = mapOf("Set-Cookie" to listOf("fs-ch-ck=earned-value; Path=/; Secure"))
        val edge = ScriptedEdge(
            listOf(
                200 to challengePage(),                       // probe
                200 to scriptWith(                            // script.js
                    """init([${powChallenge("fs-base", "K9")}], "tok-1", "$prefix")""",
                ),
                200 to """{"status":"success","ch":[],"tok":""}""", // post-back
                200 to realPage(),                            // probe-after
            ),
            headers = listOf(emptyMap(), emptyMap(), setCookie, emptyMap()),
        )

        val outcome = solverFor(edge).solve(target)

        assertTrue(outcome is FastlyChallengeSolver.Outcome.Success)
        val success = outcome as FastlyChallengeSolver.Outcome.Success
        assertEquals("earned-value", success.cookies["fs-ch-ck"])
        // The raw line keeps its attributes so CookieManager can honor scope.
        assertEquals(
            listOf("fs-ch-ck=earned-value; Path=/; Secure"),
            success.setCookieHeaders,
        )

        // The post-back must carry the exact answer envelope.
        val postBack = edge.requests[2]
        assertEquals("POST", postBack.method)
        assertEquals("$origin$prefix/fst-post-back", postBack.url)
        assertEquals("application/json", postBack.headers["Content-Type"])
        assertEquals(origin, postBack.headers["Origin"])
        val payload = JSONObject(String(postBack.body!!, Charsets.UTF_8))
        assertEquals("tok-1", payload.getString("token"))
        val answers = payload.getJSONArray("data")
        assertEquals(1, answers.length())
        val answer = answers.getJSONObject(0)
        assertEquals("pow", answer.getString("ty"))
        assertEquals("fs-base", answer.getString("base"))
        assertEquals("K9", answer.getString("answer"))
        assertEquals("hmac-fixture", answer.getString("hmac"))
        // The edge validates the echoed JSON type strictly: a string stays a
        // string. Sending a number here is what earned v1.13.4 its HTTP 400.
        assertTrue("expires must stay a JSON string", answer.get("expires") is String)
        assertEquals("1700000000000", answer.getString("expires"))

        // And the solver must ride the loopback identity, not a generic one.
        assertTrue(postBack.headers["User-Agent"]!!.contains("Gecko/145.0 Firefox/145.0"))
        assertTrue(!postBack.headers["User-Agent"]!!.contains("AppleWebKit"))
    }

    @Test
    fun `second round token and challenges drive the next post-back`() {
        val edge = ScriptedEdge(
            listOf(
                200 to challengePage(),
                200 to scriptWith("""init([${powChallenge("b1", "aB")}], "tok-1", "$prefix")"""),
                200 to """{"status":"continue","ch":[${powChallenge("b2", "cD")}]""" +
                    ""","tok":"tok-2"}""",
                200 to """{"status":"success","ch":[],"tok":""}""",
                200 to realPage(),
            ),
            headers = listOf(
                emptyMap(),
                emptyMap(),
                emptyMap(),
                mapOf("Set-Cookie" to listOf("fs-ch-ck=v2; Path=/")),
                emptyMap(),
            ),
        )

        val outcome = solverFor(edge).solve(target)

        assertTrue(outcome is FastlyChallengeSolver.Outcome.Success)
        val secondPost = JSONObject(String(edge.requests[3].body!!, Charsets.UTF_8))
        assertEquals("tok-2", secondPost.getString("token"))
        assertEquals("cD", secondPost.getJSONArray("data").getJSONObject(0).getString("answer"))
    }

    @Test
    fun `cookie that the edge does not honor fails instead of looping forever`() {
        val edge = ScriptedEdge(
            listOf(
                200 to challengePage(),
                200 to scriptWith("""init([${powChallenge("b", "aB")}], "t", "$prefix")"""),
                200 to """{"status":"success","ch":[],"tok":""}""",
                200 to challengePage(), // probe-after: still challenged
            ),
            headers = listOf(
                emptyMap(),
                emptyMap(),
                mapOf("Set-Cookie" to listOf("fs-ch-ck=v; Path=/")),
                emptyMap(),
            ),
        )

        val outcome = solverFor(edge).solve(target)

        assertTrue(outcome is FastlyChallengeSolver.Outcome.Failed)
        assertEquals("cookie-not-honored", (outcome as FastlyChallengeSolver.Outcome.Failed).reason)
    }

    @Test
    fun `rounds are capped so a hostile edge cannot spin the budget away`() {
        val continueForever = """{"status":"continue","ch":[${powChallenge("b", "aB")}],"tok":"t"}"""
        val responses = mutableListOf(
            200 to challengePage(),
            200 to scriptWith("""init([${powChallenge("b", "aB")}], "t", "$prefix")"""),
        )
        repeat(8) { responses += 200 to continueForever }
        val edge = ScriptedEdge(responses)

        val outcome = solverFor(edge).solve(target)

        assertTrue(outcome is FastlyChallengeSolver.Outcome.Failed)
        assertEquals("rounds-exhausted", (outcome as FastlyChallengeSolver.Outcome.Failed).reason)
    }

    // ------------------------------------------------------- blocking & misc

    @Test
    fun `captcha challenge blocks immediately for the human fallback`() {
        val captcha = JSONObject()
            .put("ty", "captcha")
            .put("data", JSONObject().put("image_b64", "data:image/png;base64,AAAA"))
        val edge = ScriptedEdge(
            listOf(
                200 to challengePage(),
                200 to scriptWith("""init([$captcha], "t", "$prefix")"""),
            ),
        )

        val outcome = solverFor(edge).solve(target)

        assertTrue(outcome is FastlyChallengeSolver.Outcome.Blocked)
        assertEquals("captcha", (outcome as FastlyChallengeSolver.Outcome.Blocked).reason)
    }

    @Test
    fun `pat challenge aborts with empty auth so the edge falls back to pow`() {
        val pat = JSONObject().put("ty", "pat").put("data", JSONObject())
        val edge = ScriptedEdge(
            listOf(
                200 to challengePage(),
                200 to scriptWith("""init([$pat], "tok-pat", "$prefix")"""),
                401 to "",                                   // /pat refusal
                200 to """{"status":"continue","ch":[${powChallenge("b", "zz")}],"tok":"t2"}""",
                200 to """{"status":"success","ch":[],"tok":""}""",
                200 to realPage(),
            ),
            headers = listOf(
                emptyMap(),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                mapOf("Set-Cookie" to listOf("fs-ch-ck=vp; Path=/")),
                emptyMap(),
            ),
        )

        val outcome = solverFor(edge).solve(target)

        assertTrue(outcome is FastlyChallengeSolver.Outcome.Success)
        val patRequest = edge.requests[2]
        assertEquals("POST", patRequest.method)
        assertTrue(patRequest.url.startsWith("$origin$prefix/pat?token="))
        val patAnswer = JSONObject(String(edge.requests[3].body!!, Charsets.UTF_8))
            .getJSONArray("data").getJSONObject(0)
        assertEquals("pat", patAnswer.getString("ty"))
        assertEquals("", patAnswer.getString("auth"))
    }

    @Test
    fun `page without a challenge is reported as not challenged`() {
        val edge = ScriptedEdge(listOf(200 to realPage()))
        assertEquals(FastlyChallengeSolver.Outcome.NotChallenged, solverFor(edge).solve(target))
        assertEquals(1, edge.requests.size)
    }

    @Test
    fun `script fetch failure is a clean failure`() {
        val edge = ScriptedEdge(listOf(200 to challengePage(), 400 to ""))
        val outcome = solverFor(edge).solve(target)
        assertTrue(outcome is FastlyChallengeSolver.Outcome.Failed)
        assertEquals("script-http-400", (outcome as FastlyChallengeSolver.Outcome.Failed).reason)
    }

    @Test
    fun `jar starts empty and cookies ride along on later requests`() {
        val edge = ScriptedEdge(
            listOf(
                200 to challengePage(),
                200 to scriptWith("""init([${powChallenge("b", "aB")}], "t", "$prefix")"""),
                200 to """{"status":"success","ch":[],"tok":""}""",
                200 to realPage(),
            ),
            headers = listOf(
                mapOf("Set-Cookie" to listOf("first=1; Path=/")),
                emptyMap(),
                mapOf("Set-Cookie" to listOf("fs-ch-ck=v; Path=/")),
                emptyMap(),
            ),
        )

        solverFor(edge).solve(target)

        assertNull("probe must not send cookies", edge.requests[0].headers["Cookie"])
        assertEquals("first=1", edge.requests[1].headers["Cookie"])
        assertTrue(edge.requests[3].headers["Cookie"]!!.contains("fs-ch-ck=v"))
    }

    @Test
    fun `trace never leaks token or cookie material`() {
        val lines = mutableListOf<String>()
        val edge = ScriptedEdge(
            listOf(
                200 to challengePage(),
                200 to scriptWith("""init([${powChallenge("b", "aB")}], "secret-tok", "$prefix")"""),
                200 to """{"status":"success","ch":[],"tok":""}""",
                200 to realPage(),
            ),
            headers = listOf(
                emptyMap(),
                emptyMap(),
                mapOf("Set-Cookie" to listOf("fs-ch-ck=secret-cookie; Path=/")),
                emptyMap(),
            ),
        )

        solverFor(edge) { lines += it }.solve(target)

        val joined = lines.joinToString("\n")
        assertTrue(joined.isNotEmpty())
        assertTrue(!joined.contains("secret-tok"))
        assertTrue(!joined.contains("secret-cookie"))
    }

    @Test
    fun `overall budget stops a stalled exchange`() {
        // Every clock read jumps 10 s: the deadline passes mid-exchange,
        // exactly like a network that stalls between the script and round 1.
        var now = 0L
        val edge = ScriptedEdge(
            listOf(
                200 to challengePage(),
                200 to scriptWith("""init([${powChallenge("b", "aB")}], "t", "$prefix")"""),
            ),
        )
        val solver = FastlyChallengeSolver(
            http = edge,
            overallBudgetMs = 1_000L,
            clock = { now += 10_000L; now },
        )
        val outcome = solver.solve(target)
        assertTrue(outcome is FastlyChallengeSolver.Outcome.Failed)
        assertEquals("budget", (outcome as FastlyChallengeSolver.Outcome.Failed).reason)
    }

    @Test
    fun `challenge body detection needs both markers`() {
        assertTrue(FastlyChallengeSolver.isChallengeBody("x /_fs-ch-ab Client Challenge y"))
        assertTrue(!FastlyChallengeSolver.isChallengeBody("just Client Challenge"))
        assertTrue(!FastlyChallengeSolver.isChallengeBody("just /_fs-ch-ab"))
        assertEquals("/_fs-ch-ab12", FastlyChallengeSolver.challengePrefix("a/_fs-ch-ab12/b"))
        assertNull(FastlyChallengeSolver.challengePrefix("no prefix here"))
    }

    @Test
    fun `expires echoes verbatim whatever type the edge issued`() {
        // A number on the wire stays a number — the rule is faithfulness,
        // not "always string".
        val numeric = JSONObject()
            .put("ty", "pow")
            .put(
                "data",
                JSONObject()
                    .put("base", "b")
                    .put("hash", sha256Hex("bQ7"))
                    .put("hmac", "h")
                    .put("expires", 1700000000000L),
            )
        val edge = ScriptedEdge(
            listOf(
                200 to challengePage(),
                200 to scriptWith("""init([$numeric], "t", "$prefix")"""),
                200 to """{"status":"success","ch":[],"tok":""}""",
                200 to realPage(),
            ),
            headers = listOf(
                emptyMap(),
                emptyMap(),
                mapOf("Set-Cookie" to listOf("ck=v; Path=/")),
                emptyMap(),
            ),
        )
        solverFor(edge).solve(target)
        val sent = JSONObject(String(edge.requests[2].body!!, Charsets.UTF_8))
            .getJSONArray("data").getJSONObject(0)
        assertTrue(sent.get("expires") is Number)
        assertEquals(1700000000000L, sent.getLong("expires"))
    }

    @Test
    fun `missing or null expires echoes as empty string without failing`() {
        for (data in listOf(
            JSONObject().put("base", "b").put("hash", sha256Hex("bQ7")).put("hmac", "h"),
            JSONObject().put("base", "b").put("hash", sha256Hex("bQ7")).put("hmac", "h")
                .put("expires", JSONObject.NULL),
        )) {
            val task = JSONObject().put("ty", "pow").put("data", data)
            val edge = ScriptedEdge(
                listOf(
                    200 to challengePage(),
                    200 to scriptWith("""init([$task], "t", "$prefix")"""),
                    200 to """{"status":"success","ch":[],"tok":""}""",
                    200 to realPage(),
                ),
                headers = listOf(
                    emptyMap(),
                    emptyMap(),
                    mapOf("Set-Cookie" to listOf("ck=v; Path=/")),
                    emptyMap(),
                ),
            )
            val outcome = solverFor(edge).solve(target)
            assertTrue(outcome is FastlyChallengeSolver.Outcome.Success)
            val sent = JSONObject(String(edge.requests[2].body!!, Charsets.UTF_8))
                .getJSONArray("data").getJSONObject(0)
            assertEquals("", sent.getString("expires"))
        }
    }

    @Test
    fun `refused post-back is traced with status and Fastly id but never body or token`() {
        val lines = mutableListOf<String>()
        val edge = ScriptedEdge(
            listOf(
                200 to challengePage(),
                200 to scriptWith("""init([${powChallenge("b", "aB")}], "tok-secret", "$prefix")"""),
                400 to "secret-error-body",
            ),
            headers = listOf(
                emptyMap(),
                emptyMap(),
                mapOf("Fastly-Trace-ID" to listOf("TRACE-abc-123")),
            ),
        )

        val outcome = solverFor(edge) { lines += it }.solve(target)

        assertTrue(outcome is FastlyChallengeSolver.Outcome.Failed)
        assertEquals(
            "post-back-http-400",
            (outcome as FastlyChallengeSolver.Outcome.Failed).reason,
        )
        val traced = lines.joinToString("\n")
        assertTrue(traced.contains("http=400"))
        assertTrue(traced.contains("fastly-trace=TRACE-abc-123"))
        assertTrue(!traced.contains("secret-error-body"))
        assertTrue(!traced.contains("tok-secret"))
    }
}
