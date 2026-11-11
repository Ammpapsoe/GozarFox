package com.gozar

import java.net.URI
import org.json.JSONObject

object FoxBridge {
    const val KEY_HEX = "5560cebe849c94040a1a7af57232a64981f5884bd6ef058e1170d0a92d74319d"
    const val MAX_AUTOMATIC = 10
    const val MAX_FEED_BYTES = 1_048_576

    class Candidate internal constructor(val mode: FoxTorPlan.Mode, val line: String)

    fun manual(raw: String): Candidate? {
        val candidate = parse(raw) ?: return null
        return candidate.takeIf { it.mode == FoxTorPlan.Mode.OBFS4 }
    }

    fun parse(raw: String): Candidate? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 2_048 || text.any { it.code < 0x20 || it.code == 0x7f }) return null
        val parts = text.split(Regex("\\s+"))
        return when (parts.firstOrNull()) {
            "obfs4" -> obfs4(parts)
            "webtunnel" -> webtunnel(parts)
            else -> null
        }
    }

    fun verify(
        manifestJson: String,
        signatureBase64: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        keyHex: String = KEY_HEX
    ): List<Candidate>? {
        val raw = manifestJson.toByteArray(Charsets.UTF_8)
        if (raw.isEmpty() || raw.size > MAX_FEED_BYTES) return null
        val signature = try {
            base64(signatureBase64)
        } catch (_: Exception) {
            return null
        }
        val key = hex(keyHex) ?: return null
        if (!FoxSign.verify(key, raw, signature)) return null
        val json = try {
            JSONObject(manifestJson)
        } catch (_: Exception) {
            return null
        }
        if (json.length() != 5 || json.optInt("schema", -1) != 1) return null
        val issued = number(json.opt("issued_at")) ?: return null
        val expires = number(json.opt("expires_at")) ?: return null
        if (issued <= 0 || issued > nowSeconds + 300 || expires <= issued || expires > issued + 72 * 3600 || expires <= nowSeconds) return null
        val sources = json.optJSONArray("sources") ?: return null
        if (sources.length() !in 1..8) return null
        val sourceNames = HashSet<String>()
        for (index in 0 until sources.length()) {
            val source = sources.optString(index, "")
            if (source !in setOf("tor-moat-builtin", "community-delta-kronecker", "community-onionhop") || !sourceNames.add(source)) return null
        }
        val array = json.optJSONArray("bridges") ?: return null
        if (array.length() !in 1..200) return null
        val lines = HashSet<String>()
        val output = ArrayList<Candidate>(array.length())
        for (index in 0 until array.length()) {
            val value = array.opt(index) as? String ?: return null
            val candidate = parse(value) ?: return null
            if (!lines.add(candidate.line)) return null
            output += candidate
        }
        return output
    }

    fun rotation(candidates: List<Candidate>, limit: Int = MAX_AUTOMATIC): List<Candidate> {
        if (limit <= 0) return emptyList()
        val obfs4 = candidates.filter { it.mode == FoxTorPlan.Mode.OBFS4 }.toMutableList()
        val webtunnel = candidates.filter { it.mode == FoxTorPlan.Mode.WEBTUNNEL }.toMutableList()
        obfs4.shuffle()
        webtunnel.shuffle()
        return (obfs4 + webtunnel).take(limit)
    }

    private fun obfs4(parts: List<String>): Candidate? {
        if (parts.size != 5) return null
        val endpoint = endpoint(parts[1]) ?: return null
        val fingerprint = fingerprint(parts[2]) ?: return null
        val certificate = parts[3].removePrefix("cert=")
        if (parts[3] == certificate || !Regex("[A-Za-z0-9+/]{70}(?:==)?").matches(certificate)) return null
        val mode = parts[4].removePrefix("iat-mode=")
        if (parts[4] == mode || mode !in setOf("0", "1", "2")) return null
        val line = "obfs4 $endpoint $fingerprint cert=$certificate iat-mode=$mode"
        return Candidate(FoxTorPlan.Mode.OBFS4, line)
    }

    private fun webtunnel(parts: List<String>): Candidate? {
        if (parts.size !in 4..5) return null
        val endpoint = endpoint(parts[1]) ?: return null
        val fingerprint = fingerprint(parts[2]) ?: return null
        val address = parts[3].removePrefix("url=")
        if (parts[3] == address || !validWebTunnelUrl(address)) return null
        var version = ""
        if (parts.size == 5) {
            version = parts[4].removePrefix("ver=")
            if (parts[4] == version || !Regex("[A-Za-z0-9.+_-]{1,16}").matches(version)) return null
        }
        val line = "webtunnel $endpoint $fingerprint url=$address" + if (version.isEmpty()) "" else " ver=$version"
        return Candidate(FoxTorPlan.Mode.WEBTUNNEL, line)
    }

    private fun fingerprint(value: String): String? =
        value.uppercase().takeIf { Regex("[0-9A-F]{40}").matches(it) }

    private fun endpoint(value: String): String? {
        val split = value.lastIndexOf(':')
        if (split <= 0 || value.substring(0, split).contains('[')) return null
        val address = value.substring(0, split)
        val port = value.substring(split + 1).toIntOrNull() ?: return null
        if (port !in 1..65535 || !publicIpv4(address)) return null
        return "$address:$port"
    }

    private fun publicIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } }) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        val c = octets[2]
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false
        if (a == 100 && b in 64..127) return false
        if (a == 169 && b == 254) return false
        if (a == 172 && b in 16..31) return false
        if (a == 192 && b == 168) return false
        if (a == 192 && b == 0 && (c == 0 || c == 2)) return false
        if (a == 198 && (b == 18 || b == 19)) return false
        if (a == 198 && b == 51 && c == 100) return false
        if (a == 203 && b == 0 && c == 113) return false
        return true
    }

    private fun validWebTunnelUrl(value: String): Boolean {
        if (value.length > 1_024 || value.any { it.code < 0x21 || it.code > 0x7e || it == '"' || it == '\\' }) return false
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme != "https" || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
        if (uri.port !in -1..65535 || uri.port == 0) return false
        val host = uri.host?.lowercase() ?: return false
        if (host.length > 253 || host == "localhost") return false
        val numeric = publicIpv4(host)
        if (!numeric) {
            if (host.contains(':') || host.all { it.isDigit() || it == '.' }) return false
            val labels = host.removeSuffix(".").split('.')
            if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') || !Regex("[a-z0-9-]+").matches(it) }) return false
        }
        val path = uri.rawPath ?: return false
        if (path.isEmpty() || path.length > 768 || path.contains("%2f", true) || path.contains("%5c", true)) return false
        return path.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-._~/!$&'()*+,;=:@" || it == '%' }
    }

    private fun number(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun hex(value: String): ByteArray? {
        if (value.length != 64 || value.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun base64(input: String): ByteArray {
        val body = input.trim()
        if (body.length != 88 || !body.endsWith("=")) throw IllegalArgumentException()
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val padding = body.count { it == '=' }
        if (padding > 2 || !body.endsWith("=".repeat(padding))) throw IllegalArgumentException()
        val out = ByteArray(body.length / 4 * 3 - padding)
        var at = 0
        var buffer = 0
        var bits = 0
        for (character in body) {
            if (character == '=') continue
            val value = alphabet.indexOf(character)
            if (value < 0) throw IllegalArgumentException()
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[at++] = ((buffer shr bits) and 255).toByte()
            }
        }
        if (at != out.size || (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0)) throw IllegalArgumentException()
        return out
    }
}
