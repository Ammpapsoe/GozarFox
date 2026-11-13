package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeBriefingTest {

    private val marker = "/_fs-ch-Ab12Cd34"

    private fun powTask(base: String) =
        """{"ty":"pow","data":{"base":"$base","hash":"ff","hmac":"hh","expires":"1700"}}"""

    @Test
    fun `reads the standard three-argument briefing`() {
        val script = """
            var x = 1;
            init([${powTask("b")}], "tok-1", "$marker");
            tail();
        """.trimIndent()
        val briefing = EdgeBriefing.extract(script, marker)
        assertNotNull(briefing)
        assertEquals("tok-1", briefing!!.passToken)
        assertEquals(1, briefing.tasks.size)
        assertEquals("pow", briefing.tasks[0].getString("ty"))
    }

    @Test
    fun `commas and parens inside JSON strings never split arguments`() {
        val tricky = """{"ty":"pow","data":{"base":"a,b(c)[d]","hash":"ff","hmac":"h,h","expires":"1"}}"""
        val script = """init([$tricky], "tok,with(comma)", "$marker")"""
        val briefing = EdgeBriefing.extract(script, marker)
        assertNotNull(briefing)
        assertEquals("tok,with(comma)", briefing!!.passToken)
        assertEquals("a,b(c)[d]", briefing.tasks[0].getJSONObject("data").getString("base"))
    }

    @Test
    fun `escaped quotes inside strings survive`() {
        val script = """init([${powTask("b")}], "tok\"quoted\"", "$marker")"""
        val briefing = EdgeBriefing.extract(script, marker)
        assertNotNull(briefing)
        assertEquals("tok\"quoted\"", briefing!!.passToken)
    }

    @Test
    fun `the last valid call wins over stale decoys`() {
        val script = """
            init([${powTask("b")}], "stale", "$marker");
            init([${powTask("b")}], "fresh", "$marker");
        """.trimIndent()
        assertEquals("fresh", EdgeBriefing.extract(script, marker)?.passToken)
    }

    @Test
    fun `comments between arguments and before the closing paren are stepped over`() {
        val withBlock = """init([${powTask("b")}], /* note */ "tok-b", "$marker" /*legacy*/)"""
        assertNotNull(EdgeBriefing.extract(withBlock, marker))
        val withLine = "init([${powTask("b")}], \"tok-l\", \"$marker\" // done\n)"
        assertNotNull(EdgeBriefing.extract(withLine, marker))
    }

    @Test
    fun `marker must equal the page marker`() {
        val script = """init([${powTask("b")}], "tok", "/_fs-ch-Other99")"""
        assertNull(EdgeBriefing.extract(script, marker))
    }

    @Test
    fun `rejects empty token, empty tasks, nameless kinds, and bad shape`() {
        assertNull(EdgeBriefing.extract("""init([${powTask("b")}], "", "$marker")""", marker))
        assertNull(EdgeBriefing.extract("""init([], "tok", "$marker")""", marker))
        assertNull(
            EdgeBriefing.extract("""init([{"ty":"","data":{}}], "tok", "$marker")""", marker),
        )
        assertNull(EdgeBriefing.extract("var init = 2;", marker))
        assertNull(EdgeBriefing.extract("""init([${powTask("b")}""", marker))
        // A trailing empty argument means the delimiter rule failed.
        assertNull(EdgeBriefing.extract("""init([${powTask("b")}], "tok", "$marker",)""", marker))
    }

    @Test
    fun `a real fourth argument is accepted, an empty one is not`() {
        val withFourth = """init([${powTask("b")}], "tok", "$marker", {"extra":1})"""
        val briefing = EdgeBriefing.extract(withFourth, marker)
        assertNotNull(briefing)
        assertTrue(briefing!!.tasks.size == 1)
        val task = briefing.tasks[0]
        assertEquals("pow", task.optString("ty"))
        assertEquals("1700", task.getJSONObject("data").getString("expires"))
    }
}
