package com.gozar

import java.net.URI
import org.json.JSONObject

object FoxTrace {
    fun endpoint(url: String): String = try {
        val uri = URI(url)
        if (uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty()) "invalid-endpoint" else {
            "${uri.scheme}://${uri.host}${uri.rawPath.orEmpty()}"
        }
    } catch (_: Exception) {
        "invalid-endpoint"
    }

    fun request(url: String, method: String, body: String?, authorization: Boolean) {
        val fields = jsonFields(body)
        FoxDiagnostics.event("HTTP ${endpoint(url)} method=$method request=send fields=[$fields] authorization=$authorization")
    }

    fun response(url: String, method: String, route: String, status: Int, body: String) {
        val metadata = try {
            val json = JSONObject(body)
            val fields = json.keys().asSequence().toList().sorted().joinToString(",")
            val errno = if (json.has("errno")) json.optInt("errno", -1).toString() else "none"
            val code = if (json.has("code")) json.optInt("code", -1).toString() else "none"
            val error = safeText(json.optString("error"))
            val message = safeText(json.optString("message"))
            " fields=[$fields] errno=$errno bodyCode=$code error=[$error] message=[$message]"
        } catch (_: Exception) {
            " fields=[] errno=none bodyCode=none"
        }
        FoxDiagnostics.event("HTTP ${endpoint(url)} method=$method route=$route status=$status$metadata")
    }

    private fun safeText(value: String): String {
        if (value.isEmpty()) return "none"
        if (value.length > 200) return "redacted"
        return value.replace(Regex("https?://\\S+"), "[url]")
            .replace(Regex("\\S+@\\S+"), "[email]")
            .replace(Regex("[A-Za-z0-9_+\\-/=]{16,}"), "[redacted]")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(160)
    }

    private fun jsonFields(body: String?): String {
        if (body == null) return ""
        return try {
            JSONObject(body).keys().asSequence().toList().sorted().joinToString(",")
        } catch (_: Exception) {
            ""
        }
    }
}
