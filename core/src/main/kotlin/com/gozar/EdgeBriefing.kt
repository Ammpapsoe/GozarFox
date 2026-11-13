package com.gozar

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the edge challenge script's `init(...)` briefing without ever
 * executing a line of its JavaScript.
 *
 * The scan is string-aware: a comma, bracket, or parenthesis inside a JSON
 * string never splits an argument, and block or line comments sitting
 * between arguments are stepped over. A briefing is accepted only when its marker
 * equals the one the page carried, its token is non-empty, every task names
 * a kind, and a real delimiter closes the call. When several calls parse,
 * the last one wins — the script may embed stale or decoy copies.
 *
 * Written from the wire format; no other implementation was copied.
 */
object EdgeBriefing {

    /** What the script briefs the browser to do. */
    data class Briefing(
        val tasks: List<JSONObject>,
        val passToken: String,
        val marker: String,
    )

    fun extract(script: String, expectedMarker: String): Briefing? {
        var cursor = 0
        var best: Briefing? = null
        while (true) {
            val at = script.indexOf("init(", cursor)
            if (at < 0) return best
            cursor = at + "init(".length
            val call = sliceArguments(script, cursor) ?: continue
            if (call.arguments.size < 3) continue
            if (call.arguments.size > 3 && call.arguments[3].isBlank()) continue
            val tasks = runCatching { JSONArray(call.arguments[0]) }.getOrNull() ?: continue
            val passToken = scalarString(call.arguments[1]) ?: continue
            val marker = scalarString(call.arguments[2]) ?: continue
            if (passToken.isEmpty()) continue
            if (marker != expectedMarker) continue
            val parsed = (0 until tasks.length())
                .mapNotNull { tasks.optJSONObject(it) }
            if (parsed.isEmpty()) continue
            if (parsed.any { it.optString("ty").isEmpty() }) continue
            best = Briefing(parsed, passToken, marker)
        }
    }

    private data class Call(val arguments: List<String>)

    /**
     * Walks from just after `init(` to the matching `)`, splitting on
     * top-level commas only. Returns null when the text ends first or the
     * brackets do not balance — such a call is not the briefing.
     */
    private fun sliceArguments(script: String, from: Int): Call? {
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var insideString = false
        var escaped = false
        var index = from
        while (index < script.length) {
            val char = script[index]
            if (insideString) {
                current.append(char)
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> insideString = false
                }
                index++
                continue
            }
            when {
                char == '"' -> {
                    insideString = true
                    current.append(char)
                }
                char == '/' && index + 1 < script.length && script[index + 1] == '*' -> {
                    val end = script.indexOf("*/", index + 2)
                    if (end < 0) return null
                    index = end + 2
                    continue // the comment is not part of any argument
                }
                char == '/' && index + 1 < script.length && script[index + 1] == '/' -> {
                    val end = script.indexOf('\n', index + 2)
                    if (end < 0) return null
                    index = end + 1
                    continue
                }
                char == '{' || char == '[' || char == '(' -> {
                    depth++
                    current.append(char)
                }
                char == '}' || char == ']' || char == ')' -> {
                    if (depth == 0) {
                        if (char != ')') return null
                        arguments += current.toString()
                        return Call(arguments)
                    }
                    depth--
                    current.append(char)
                }
                char == ',' && depth == 0 -> {
                    arguments += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        return null
    }

    /** Parses one argument as a JSON string scalar; anything else is null. */
    private fun scalarString(raw: String): String? {
        val text = raw.trim()
        if (text.length < 2 || !text.startsWith("\"")) return null
        return runCatching { JSONArray("[$text]").getString(0) }.getOrNull()
    }
}
