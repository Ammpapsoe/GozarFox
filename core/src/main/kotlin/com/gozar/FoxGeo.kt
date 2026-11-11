package com.gozar

import java.io.ByteArrayOutputStream

object FoxGeo {

    @Volatile
    private var domains: Set<String> = loadDomains()

    private val prefixes: List<LongRange> = loadPrefixes()

    fun direct(host: String): Boolean {
        val name = host.trim().lowercase().removeSuffix(".").trim('[', ']')
        if (name.isEmpty() || name.length > 253) return false
        if (name == "ir" || name.endsWith(".ir")) return true
        ipv4(name)?.let { address ->
            return prefixes.any { address in it }
        }
        var suffix = name
        while (true) {
            if (domains.contains(suffix)) return true
            val index = suffix.indexOf('.')
            if (index < 0) return false
            suffix = suffix.substring(index + 1)
        }
    }

    fun update(text: String): Boolean {
        val parsed = parse(text) ?: return false
        domains = parsed
        return true
    }

    /**
     * Fetches the domain list and its detached Ed25519 signature through the
     * current tunnel. The list is replaced only when the signature verifies
     * with the pinned list key over the exact downloaded bytes.
     */
    fun refresh(location: FoxLocation, pass: String, expectedTransport: Long? = null): Boolean {
        return try {
            val list = fetchResource(location, pass, expectedTransport, LIST_PATH, 1_100_000) ?: return false
            val signature = fetchResource(location, pass, expectedTransport, LIST_PATH + ".sig", 512) ?: return false
            if (!acceptSigned(list, String(signature, Charsets.ISO_8859_1))) return false
            if (expectedTransport != null && !FoxTunnel.transportIsCurrent(expectedTransport)) return false
            update(String(list, Charsets.UTF_8))
        } catch (e: Exception) {
            false
        }
    }

    private fun fetchResource(
        location: FoxLocation,
        pass: String,
        expectedTransport: Long?,
        path: String,
        limit: Int
    ): ByteArray? {
        val pipe = FoxTunnel.open(location, "raw.githubusercontent.com", 443, pass, 15000, expectedTransport)
        try {
            val request = ("GET $path HTTP/1.1\r\n" +
                "Host: raw.githubusercontent.com\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            pipe.write(request, 0, request.size)
            val buffer = ByteArray(8192)
            val all = ByteArrayOutputStream()
            while (true) {
                val count = pipe.read(buffer, 0, buffer.size)
                if (count <= 0) break
                all.write(buffer, 0, count)
                if (all.size() > limit + 4096) return null
            }
            val bytes = all.toByteArray()
            val text = String(bytes, Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            if (split < 0) return null
            val head = text.substring(0, split)
            if (!head.startsWith("HTTP/") || !head.contains(" 200 ")) return null
            val body = bytes.copyOfRange(split + 4, bytes.size)
            return if (body.size > limit) null else body
        } finally {
            pipe.close()
        }
    }

    /** Ed25519 key that signs packaging/ir-domains.txt; separate from the update key. */
    const val LIST_KEY_HEX = "5a74ff31ae5dff9923384ae85eb403cd26967c0f63fe78cf03e5471788aec446"
    private const val LIST_PATH = "/Ammpapsoe/GozarFox/main/packaging/ir-domains.txt"
    private const val LIST_PREFIX = "GOZAR-IR-DOMAINS-V1\n"

    /** Verifies a detached signature (128 hex characters) over the exact list bytes. */
    internal fun acceptSigned(list: ByteArray, signatureText: String, public: ByteArray = hexBytes(LIST_KEY_HEX)!!): Boolean {
        val hex = signatureText.trim()
        if (hex.length != 128) return false
        val signature = hexBytes(hex) ?: return false
        val message = LIST_PREFIX.toByteArray(Charsets.UTF_8) + list
        return FoxSign.verify(public, message, signature)
    }

    private fun hexBytes(text: String): ByteArray? {
        if (text.length % 2 != 0 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            out[i] = text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun parse(text: String): Set<String>? {
        val parsed = HashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val domain = line.lowercase().removeSuffix(".")
            if (!valid(domain)) return null
            parsed.add(domain)
            if (parsed.size > 20000) return null
        }
        return if (parsed.isEmpty()) null else parsed
    }

    private fun valid(domain: String): Boolean {
        if (domain.length > 253 || domain.contains("://")) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63 || label.startsWith("-") || label.endsWith("-")) return false
            if (!label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return false
        }
        return true
    }

    private fun ipv4(name: String): Long? {
        val parts = name.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val number = part.toIntOrNull() ?: return null
            if (number < 0 || number > 255) return null
            value = (value shl 8) or number.toLong()
        }
        return value
    }

    private fun loadDomains(): Set<String> {
        val text = resource("/ir-domains.txt") ?: return emptySet()
        return parse(text) ?: emptySet()
    }

    private fun loadPrefixes(): List<LongRange> {
        val text = resource("/ir-ipv4.cidr") ?: return emptyList()
        val ranges = ArrayList<LongRange>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val slash = line.indexOf('/')
            if (slash <= 0) continue
            val address = ipv4(line.substring(0, slash)) ?: continue
            val bits = line.substring(slash + 1).toIntOrNull() ?: continue
            if (bits < 0 || bits > 32) continue
            val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
            val start = address and mask
            val end = start or (mask.inv() and 0xFFFFFFFFL)
            ranges.add(start..end)
        }
        return ranges
    }

    private fun resource(name: String): String? {
        return try {
            FoxGeo::class.java.getResourceAsStream(name)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
