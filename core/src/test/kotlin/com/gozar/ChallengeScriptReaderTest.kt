package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeScriptReaderTest {

    private val marker = "/_fs-ch-Ab12Cd34"

    private fun powTask(base: String) =
        """{"ty":"pow","data":{"base":"$base","hash":"ff","hmac":"hh","expires":"1700"}}"""

    @Test
    fun `reads the standard three-argument briefing`() {
        val script = """
            var x = 1;
            init([$powTask], "tok-1", "$marker");
            tail();
        """.trimIndent()
        val briefing = ChallengeScriptReader.read(script, marker)
        assertNotNull(briefing)
        assertEquals("tok-1", briefing!!.passToken)
        assertEquals(1, briefing.tasks.size)
        assertEquals("pow", briefing.tasks[0].getString("ty"))
    }

    @Test
    fun `commas and parens inside JSON strings never split arguments`() {
        val tricky = """{"ty":"pow","data":{"base":"a,b(c)[d]","hash":"ff","hmac":"h,h","expires":"1"}}"""
        val script = """init([$tricky], "tok,with(comma)", "$marker")"""
        val briefing = ChallengeScriptReader.read(script, marker)
        assertNotNull(briefing)
        assertEquals("tok,with(comma)", briefing!!.passToken)
        assertEquals("a,b(c)[d]", briefing.tasks[0].getJSONObject("data").getString("base"))
    }

    @Test
    fun `escaped quotes inside strings survive`() {
        val script = """init([$powTask], "tok\"quoted\"", "$marker")"""
        val briefing = ChallengeScriptReader.read(script, marker)
        assertNotNull(briefing)
        assertEquals("tok\"quoted\"", briefing!!.passToken)
    }

    @Test
    fun `the last valid call wins over stale decoys`() {
        val script = """
            init([$powTask], "stale", "$marker");
            init([$powTask], "fresh", "$marker");
        """.trimIndent()
        assertEquals("fresh", ChallengeScriptReader.read(script, marker)?.passToken)
    }

    @Test
    fun `comments between arguments and before the closing paren are stepped over`() {
        val withBlock = """init([$powTask], /* note */ "tok-b", "$marker" /*legacy*/)"""
        assertNotNull(ChallengeScriptReader.read(withBlock, marker))
        val withLine = "init([$powTask], \"tok-l\", \"$marker\" // done\n)"
        assertNotNull(ChallengeScriptReader.read(withLine, marker))
    }

    @Test
    fun `marker must equal the page marker`() {
        val script = """init([$powTask], "tok", "/_fs-ch-Other99")"""
        assertNull(ChallengeScriptReader.read(script, marker))
    }

    @Test
    fun `rejects empty token, empty tasks, nameless kinds, and bad shape`() {
        assertNull(ChallengeScriptReader.read("""init([$powTask], "", "$marker")""", marker))
        assertNull(ChallengeScriptReader.read("""init([], "tok", "$marker")""", marker))
        assertNull(
            ChallengeScriptReader.read("""init([{"ty":"","data":{}}], "tok", "$marker")""", marker),
        )
        assertNull(ChallengeScriptReader.read("var init = 2;", marker))
        assertNull(ChallengeScriptReader.read("""init([$powTask""", marker))
        // A trailing empty argument means the delimiter rule failed.
        assertNull(ChallengeScriptReader.read("""init([$powTask], "tok", "$marker",)""", marker))
    }

    @Test
    fun `a real fourth argument is accepted, an empty one is not`() {
        val withFourth = """init([$powTask], "tok", "$marker", {"extra":1})"""
        val briefing = ChallengeScriptReader.read(withFourth, marker)
        assertNotNull(briefing)
        assertTrue(briefing!!.tasks.size == 1)
        val task = briefing.tasks[0]
        assertEquals("pow", task.optString("ty"))
        assertEquals("1700", task.getJSONObject("data").getString("expires"))
    }
}
