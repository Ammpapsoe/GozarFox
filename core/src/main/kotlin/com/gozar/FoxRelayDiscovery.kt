package com.gozar

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

object FoxRelayDiscovery {
    const val KEY_HEX = "5a74ff31ae5dff9923384ae85eb403cd26967c0f63fe78cf03e5471788aec446"
    const val TTL_MS = 24L * 60 * 60 * 1000
    const val MAX_SKEW_MS = 5L * 60 * 1000
    const val MAX_DOCUMENT_BYTES = 16 * 1024
    private const val PREFIX = "GOZAR-RELAY-ENDPOINTS-V1\n"
    private const val DOCUMENT_URL = "https://raw.githubusercontent.com/Ammpapsoe/GozarFox/mission/autonomous-v1/packaging/relay-endpoints.json"
    private const val SIGNATURE_URL = "$DOCUMENT_URL.sig"
    private val defaults = listOf(
        "https://script.google.com/macros/s/AKfycbwMXiE-wFBEaC_ZpZOode1yvgPG1xIHsrty-sM4czaFz6ZBabAWuCd9udiPW4PLH2GR/exec",
        "https://gozar-guardian-a.gozarfox7.workers.dev",
        "https://gozar-guardian-b.gozarfox7.workers.dev"
    )
    private val refreshLock = Any()
    @Volatile private var active = defaults
    @Volatile private var fetchedAt = 0L

    fun relays(): List<String> = active

    private fun prioritize(values: List<String>): List<String> {
        val primary = defaults.first()
        return (listOf(primary) + values.filterNot { it == primary }).distinct().take(3)
    }

    fun validUrl(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > 255 || raw != raw.trim()) return false
        return try {
            val url = URI(raw)
            val host = url.host?.lowercase() ?: return false
            val clean = url.scheme == "https" && url.port == -1 && url.userInfo == null &&
                url.rawQuery == null && url.rawFragment == null
            clean && when {
                host == "script.google.com" -> url.rawPath?.matches(Regex("/macros/s/[A-Za-z0-9_-]{20,}/exec")) == true
                host.endsWith(".gozarfox7.workers.dev") && host.length > ".gozarfox7.workers.dev".length &&
                    (url.rawPath.isNullOrEmpty() || url.rawPath == "/") ->
                    host.substringBefore(".gozarfox7.workers.dev").let { prefix ->
                        prefix.isNotEmpty() && !prefix.contains('.') && prefix.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
                            !prefix.startsWith('-') && !prefix.endsWith('-')
                    }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isGoogle(raw: String): Boolean = try {
        URI(raw).host?.equals("script.google.com", true) == true
    } catch (_: Exception) {
        false
    }

    private fun normalize(raw: String): String = raw.removeSuffix("/")

    fun verify(document: String, signatureText: String, publicHex: String = KEY_HEX): Boolean {
        val bytes = document.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_DOCUMENT_BYTES) return false
        val signature = decodeHex(signatureText.trim()) ?: return false
        if (signature.size != 64) return false
        val public = decodeHex(publicHex) ?: return false
        return public.size == 32 && FoxSign.verify(public, PREFIX.toByteArray(Charsets.UTF_8) + bytes, signature)
    }

    fun parse(document: String): List<String>? {
        if (document.isEmpty() || document.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) return null
        return try {
            val root = JSONObject(document)
            if (root.length() != 2 || root.opt("version") as? Int != 1) return null
            val array = root.optJSONArray("relays") ?: return null
            if (array.length() !in 1..3) return null
            val output = LinkedHashSet<String>()
            for (index in 0 until array.length()) {
                val value = array.opt(index) as? String ?: return null
                if (!validUrl(value) || !output.add(normalize(value))) return null
            }
            output.toList()
        } catch (_: Exception) {
            null
        }
    }

    fun acceptPublished(document: String, signatureText: String): Boolean {
        if (!verify(document, signatureText)) return false
        val parsed = parse(document) ?: return false
        active = prioritize(parsed)
        fetchedAt = System.currentTimeMillis()
        return true
    }

    fun encodeCache(relays: List<String>, now: Long): String? {
        if (now <= 0 || relays.isEmpty() || relays.size > 3 || relays.toSet().size != relays.size || relays.any { !validUrl(it) }) return null
        val array = JSONArray()
        relays.forEach { array.put(normalize(it)) }
        return JSONObject().put("version", 1).put("fetched_at", now).put("relays", array).toString()
    }

    fun decodeCache(raw: String, now: Long): Pair<List<String>, Long>? {
        if (raw.isEmpty() || raw.length > MAX_DOCUMENT_BYTES || now <= 0) return null
        return try {
            val root = JSONObject(raw)
            if (root.length() != 3 || root.opt("version") as? Int != 1) return null
            val timestamp = root.optLong("fetched_at", 0)
            if (timestamp <= 0 || timestamp > now + MAX_SKEW_MS || now - timestamp >= TTL_MS) return null
            val array = root.optJSONArray("relays") ?: return null
            if (array.length() !in 1..3) return null
            val output = LinkedHashSet<String>()
            for (index in 0 until array.length()) {
                val value = array.opt(index) as? String ?: return null
                if (!validUrl(value) || !output.add(normalize(value))) return null
            }
            output.toList() to timestamp
        } catch (_: Exception) {
            null
        }
    }

    fun restoreCache(raw: String, now: Long = System.currentTimeMillis()): Boolean {
        val cached = decodeCache(raw, now) ?: return false
        active = prioritize(cached.first)
        fetchedAt = cached.second
        return true
    }

    fun refresh(now: Long = System.currentTimeMillis(), force: Boolean = false): Boolean = synchronized(refreshLock) {
        if (!force && fetchedAt > 0 && now >= fetchedAt && now - fetchedAt < TTL_MS) return@synchronized false
        val document = FoxNet.call(DOCUMENT_URL, "GET", timeout = 15000)
        if (document.code != 200) return@synchronized false
        val signature = FoxNet.call(SIGNATURE_URL, "GET", timeout = 15000)
        if (signature.code != 200) return@synchronized false
        if (!verify(document.body, signature.body)) return@synchronized false
        val parsed = parse(document.body) ?: return@synchronized false
        active = prioritize(parsed)
        fetchedAt = now
        true
    }

    internal fun installForTest(values: List<String>): List<String> {
        val previous = active
        active = values
        fetchedAt = 0
        return previous
    }

    private fun decodeHex(text: String): ByteArray? {
        if (text.length % 2 != 0 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(text.length / 2) { index -> text.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
