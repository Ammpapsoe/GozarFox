package com.gozar

import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [EdgeGuard] against a scripted edge: canned replies in order,
 * every request recorded. The fixtures mirror the live accounts.firefox.com
 * exchange (interstitial page, init briefing inside script.js, fst-post-back
 * receipts that bless or re-issue).
 */
class EdgeGuardTest {

    private val target = "https://accounts.firefox.com/?context=fx_desktop_v3&service=sync&action=email"
    private val origin = "https://accounts.firefox.com"
    private val marker = "/_fs-ch-1T1wmsGaOgGaSxcX"

    private fun digestHex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun interstitial() = """
        <!doctype html><html><head><title>Client Challenge</title></head><body>
        <script src="$marker/script.js?reload=true"></script>
        </body></html>
    """.trimIndent()

    private fun realPage() = """
        <!doctype html><html><head><title>Mozilla accounts sign-in</title></head>
        <body><form id="main-content"></form></body></html>
    """.trimIndent()

    private fun powTask(base: String, suffix: String, expires: Any = "1700000000000") =
        """{"ty":"pow","data":{"base":"$base","hash":"${digestHex(base + suffix)}",""" +
            """"hmac":"hmac-fixture","expires":${if (expires is String) "\"$expires\"" else expires}}}"""

    private fun script(vararg initCalls: String) =
        initCalls.joinToString(separator = "\n", postfix = "\n")

    /** Records requests, replays canned replies. */
    private class FakeEdge(
        private val replies: List<Pair<Int, String>>,
        private val headers: List<Map<String, List<String>>> = emptyList(),
    ) : EdgeGuard.Wire {
        val requests = mutableListOf<EdgeGuard.Wire.Request>()

        override fun exchange(request: EdgeGuard.Wire.Request): EdgeGuard.Wire.Reply {
            requests += request
            val index = requests.size - 1
            assertTrue("edge ran out of scripted replies", index < replies.size)
            val (status, body) = replies[index]
            return EdgeGuard.Wire.Reply(
                status,
                headers.getOrElse(index) { emptyMap() },
                body.toByteArray(Charsets.UTF_8),
            )
        }
    }

    private fun verifierFor(
        edge: EdgeGuard.Wire,
        trace: (String) -> Unit = {},
        notify: (EdgeGuard.Stage) -> Unit = {},
        clock: () -> Long = System::currentTimeMillis,
        budgetMs: Long = 60_000L,
    ) = EdgeGuard(
        wire = edge,
        clock = clock,
        budgetMs = budgetMs,
        trace = trace,
        notify = notify,
    )

    // ------------------------------------------------------- full exchange

    @Test
    fun `cleared run posts the right envelope and returns the pass verbatim`() {
        val passLine = "fs-ch-ck=earned-value; Path=/; Secure"
        val edge = FakeEdge(
            listOf(
                200 to interstitial(),
                200 to script("""init([${powTask("fs-base", "K9")}], "tok-1", "$marker")"""),
                200 to """{"status":"success","ch":[],"tok":""}""",
                200 to realPage(),
            ),
            headers = listOf(
                emptyMap(),
                emptyMap(),
                mapOf("Set-Cookie" to listOf(passLine)),
                emptyMap(),
            ),
        )

        val verdict = verifierFor(edge).resolve(target)

        assertTrue(verdict is EdgeGuard.Verdict.Cleared)
        val cleared = verdict as EdgeGuard.Verdict.Cleared
        assertEquals("earned-value", cleared.cookies["fs-ch-ck"])
        assertEquals(listOf(passLine), cleared.setCookieLines)

        val delivery = edge.requests[2]
        assertEquals("POST", delivery.method)
        assertEquals("$origin$marker/fst-post-back", delivery.url)
        assertEquals("application/json", delivery.headers["Content-Type"])
        assertEquals(origin, delivery.headers["Origin"])
        val envelope = JSONObject(String(delivery.body!!, Charsets.UTF_8))
        assertEquals("tok-1", envelope.getString("token"))
        val answer = envelope.getJSONArray("data").getJSONObject(0)
        assertEquals("pow", answer.getString("ty"))
        assertEquals("fs-base", answer.getString("base"))
        assertEquals("K9", answer.getString("answer"))
        assertEquals("hmac-fixture", answer.getString("hmac"))
        // The wire issues expires as a string and validates the echoed type.
        assertTrue("expires must stay a string", answer.get("expires") is String)
        assertEquals("1700000000000", answer.getString("expires"))
        // The walk rides the WebView's identity.
        val agent = delivery.headers["User-Agent"]!!
        assertTrue(agent.contains("Gecko/145.0 Firefox/145.0"))
        assertTrue(!agent.contains("AppleWebKit"))
    }

    @Test
    fun `expires echoes verbatim whatever type was issued`() {
        val edge = FakeEdge(
            listOf(
                200 to interstitial(),
                200 to script("""init([${powTask("b", "Q7", 1700000000000L)}], "t", "$marker")"""),
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
        verifierFor(edge).resolve(target)
        val sent = JSONObject(String(edge.requests[2].body!!, Charsets.UTF_8))
            .getJSONArray("data").getJSONObject(0)
        assertTrue(sent.get("expires") is Number)
        assertEquals(1700000000000L, sent.getLong("expires"))
    }

    @Test
    fun `missing or null expires echoes empty without failing`() {
        for (data in listOf(
            """{"base":"b","hash":"${digestHex("bQ7")}","hmac":"h"}""",
            """{"base":"b","hash":"${digestHex("bQ7")}","hmac":"h","expires":null}""",
        )) {
            val edge = FakeEdge(
                listOf(
                    200 to interstitial(),
                    200 to script("""init([{"ty":"pow","data":$data}], "t", "$marker")"""),
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
            val verdict = verifierFor(edge).resolve(target)
            assertTrue(verdict is EdgeGuard.Verdict.Cleared)
            val sent = JSONObject(String(edge.requests[2].body!!, Charsets.UTF_8))
                .getJSONArray("data").getJSONObject(0)
            assertEquals("", sent.getString("expires"))
        }
    }

    @Test
    fun `a second round rides the new token and tasks`() {
        val edge = FakeEdge(
            listOf(
                200 to interstitial(),
                200 to script("""init([${powTask("b1", "aB")}], "tok-1", "$marker")"""),
                200 to """{"status":"continue","ch":[${powTask("b2", "cD")}],"tok":"tok-2"}""",
                200 to """{"status":"success","ch":[],"tok":""}""",
                200 to realPage(),
            ),
            headers = listOf(
                emptyMap(),
                emptyMap(),
                emptyMap(),
                mapOf("Set-Cookie" to listOf("ck=v2; Path=/")),
                emptyMap(),
            ),
        )
        val verdict = verifierFor(edge).resolve(target)
        assertTrue(verdict is EdgeGuard.Verdict.Cleared)
        val second = JSONObject(String(edge.requests[3].body!!, Charsets.UTF_8))
        assertEquals("tok-2", second.getString("token"))
        assertEquals("cD", second.getJSONArray("data").getJSONObject(0).getString("answer"))
    }

    // ----------------------------------------------------------- failures

    @Test
    fun `a pass the edge does not honor is admitted, not retried forever`() {
        val edge = FakeEdge(
            listOf(
                200 to interstitial(),
                200 to script("""init([${powTask("b", "aB")}], "t", "$marker")"""),
                200 to """{"status":"success","ch":[],"tok":""}""",
                200 to interstitial(),
            ),
            headers = listOf(
                emptyMap(),
                emptyMap(),
                mapOf("Set-Cookie" to listOf("ck=v; Path=/")),
                emptyMap(),
            ),
        )
        val verdict = verifierFor(edge).resolve(target)
        assertTrue(verdict is EdgeGuard.Verdict.GaveUp)
        assertEquals("pass-not-honored", (verdict as EdgeGuard.Verdict.GaveUp).why)
    }

    @Test
    fun `rounds stop at the cap`() {
        val keepGoing = """{"status":"continue","ch":[${powTask("b", "aB")}],"tok":"t"}"""
        val replies = mutableListOf(
            200 to interstitial(),
            200 to script("""init([${powTask("b", "aB")}], "t", "$marker")"""),
        )
        repeat(8) { replies += 200 to keepGoing }
        val verdict = verifierFor(FakeEdge(replies)).resolve(target)
        assertTrue(verdict is EdgeGuard.Verdict.GaveUp)
        assertEquals("rounds-used-up", (verdict as EdgeGuard.Verdict.GaveUp).why)
    }

    @Test
    fun `refused delivery is traced with status and edge id but never body or token`() {
        val lines = mutableListOf<String>()
        val edge = FakeEdge(
            listOf(
                200 to interstitial(),
                200 to script("""init([${powTask("b", "aB")}], "tok-secret", "$marker")"""),
                400 to "secret-error-body",
            ),
            headers = listOf(
                emptyMap(),
                emptyMap(),
                mapOf("Fastly-Trace-ID" to listOf("TRACE-abc-123")),
            ),
        )
        val verdict = verifierFor(edge, trace = { lines += it }).resolve(target)
        assertTrue(verdict is EdgeGuard.Verdict.GaveUp)
        assertEquals("delivery-refused-400", (verdict as EdgeGuard.Verdict.GaveUp).why)
        val traced = lines.joinToString("\n")
        assertTrue(traced.contains("http=400"))
        assertTrue(traced.contains("fastly-trace=TRACE-abc-123"))
        assertTrue(!traced.contains("secret-error-body"))
        assertTrue(!traced.contains("tok-secret"))
    }

    @Test
    fun `image puzzle goes to the human verdict immediately`() {
        val captcha = """{"ty":"captcha","data":{"image_b64":"data:image/png;base64,AAAA"}}"""
        val edge = FakeEdge(
            listOf(
                200 to interstitial(),
                200 to script("""init([$captcha], "t", "$marker")"""),
            ),
        )
        val verdict = verifierFor(edge).resolve(target)
        assertTrue(verdict is EdgeGuard.Verdict.NeedsHuman)
        assertEquals("captcha", (verdict as EdgeGuard.Verdict.NeedsHuman).why)
    }

    @Test
    fun `access-token task aborts empty so the edge falls back to work proofs`() {
        val edge = FakeEdge(
            listOf(
                200 to interstitial(),
                200 to script("""init([{"ty":"pat","data":{}}], "tok-pat", "$marker")"""),
                401 to "",
                200 to """{"status":"continue","ch":[${powTask("b", "zz")}],"tok":"t2"}""",
                200 to """{"status":"success","ch":[],"tok":""}""",
                200 to realPage(),
            ),
            headers = listOf(
                emptyMap(),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                mapOf("Set-Cookie" to listOf("ck=vp; Path=/")),
                emptyMap(),
            ),
        )
        val verdict = verifierFor(edge).resolve(target)
        assertTrue(verdict is EdgeGuard.Verdict.Cleared)
        val patRequest = edge.requests[2]
        assertEquals("POST", patRequest.method)
        assertTrue(patRequest.url.startsWith("$origin$marker/pat?token="))
        val patAnswer = JSONObject(String(edge.requests[3].body!!, Charsets.UTF_8))
            .getJSONArray("data").getJSONObject(0)
        assertEquals("pat", patAnswer.getString("ty"))
        assertEquals("", patAnswer.getString("auth"))
    }

    @Test
    fun `no interstitial means no challenge`() {
        val edge = FakeEdge(listOf(200 to realPage()))
        assertEquals(EdgeGuard.Verdict.NoChallenge, verifierFor(edge).resolve(target))
        assertEquals(1, edge.requests.size)
    }

    @Test
    fun `refused script is a clean verdict`() {
        val edge = FakeEdge(listOf(200 to interstitial(), 400 to ""))
        val verdict = verifierFor(edge).resolve(target)
        assertTrue(verdict is EdgeGuard.Verdict.GaveUp)
        assertEquals("script-refused-400", (verdict as EdgeGuard.Verdict.GaveUp).why)
    }

    @Test
    fun `unreadable briefing is a clean verdict`() {
        val edge = FakeEdge(listOf(200 to interstitial(), 200 to "var nothing = 1;"))
        val verdict = verifierFor(edge).resolve(target)
        assertEquals("briefing-unreadable", (verdict as EdgeGuard.Verdict.GaveUp).why)
    }

    // ------------------------------------------------- hygiene and bounds

    @Test
    fun `cookies start absent and ride along on later steps`() {
        val edge = FakeEdge(
            listOf(
                200 to interstitial(),
                200 to script("""init([${powTask("b", "aB")}], "t", "$marker")"""),
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
        verifierFor(edge).resolve(target)
        assertNull(edge.requests[0].headers["Cookie"])
        assertEquals("first=1", edge.requests[1].headers["Cookie"])
        assertTrue(edge.requests[3].headers["Cookie"]!!.contains("fs-ch-ck=v"))
    }

    @Test
    fun `traces never carry token or cookie material`() {
        val lines = mutableListOf<String>()
        val edge = FakeEdge(
            listOf(
                200 to interstitial(),
                200 to script("""init([${powTask("b", "aB")}], "secret-tok", "$marker")"""),
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
        verifierFor(edge, trace = { lines += it }).resolve(target)
        val traced = lines.joinToString("\n")
        assertTrue(traced.isNotEmpty())
        assertTrue(!traced.contains("secret-tok"))
        assertTrue(!traced.contains("secret-cookie"))
    }

    @Test
    fun `the budget stops a stalled walk`() {
        var now = 0L
        val edge = FakeEdge(
            listOf(
                200 to interstitial(),
                200 to script("""init([${powTask("b", "aB")}], "t", "$marker")"""),
            ),
        )
        val verdict = verifierFor(
            edge,
            clock = { now += 10_000L; now },
            budgetMs = 1_000L,
        ).resolve(target)
        assertTrue(verdict is EdgeGuard.Verdict.GaveUp)
        assertEquals("out-of-time", (verdict as EdgeGuard.Verdict.GaveUp).why)
    }

    @Test
    fun `cancel stops the walk at the next safe point`() {
        val edge = FakeEdge(
            listOf(
                200 to interstitial(),
                200 to script("""init([${powTask("b", "aB")}], "t", "$marker")"""),
            ),
        )
        val verifier = verifierFor(edge)
        verifier.cancel()
        val verdict = verifier.resolve(target)
        assertTrue(verdict is EdgeGuard.Verdict.GaveUp)
        assertEquals("called-off", (verdict as EdgeGuard.Verdict.GaveUp).why)
        // Cancelled before the script was ever fetched.
        assertEquals(1, edge.requests.size)
    }

    @Test
    fun `stage narration climbs to one hundred`() {
        val stages = mutableListOf<EdgeGuard.Stage>()
        val edge = FakeEdge(
            listOf(
                200 to interstitial(),
                200 to script("""init([${powTask("b", "aB")}], "t", "$marker")"""),
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
        verifierFor(edge, notify = { stages += it }).resolve(target)
        assertEquals(EdgeGuard.Stage.PROBING, stages.first())
        assertEquals(EdgeGuard.Stage.CLEARED, stages.last())
        val percents = stages.map { it.percent }
        assertEquals(percents.sorted(), percents)
        assertTrue(percents.contains(60))
    }

    @Test
    fun `interstitial detection needs both markers`() {
        assertTrue(EdgeGuard.isInterstitial("x /_fs-ch-ab Client Challenge y"))
        assertTrue(!EdgeGuard.isInterstitial("just Client Challenge"))
        assertTrue(!EdgeGuard.isInterstitial("just /_fs-ch-ab"))
        assertEquals("/_fs-ch-ab12", EdgeGuard.markerOf("a/_fs-ch-ab12/b"))
        assertNull(EdgeGuard.markerOf("no marker here"))
    }
}
