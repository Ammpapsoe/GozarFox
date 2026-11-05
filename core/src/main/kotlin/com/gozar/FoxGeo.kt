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

    fun refresh(location: FoxLocation, pass: String): Boolean {
        return try {
            val pipe = FoxTunnel.open(location, "raw.githubusercontent.com", 443, pass, 15000)
            try {
                val request = ("GET /bardia049b-stack/Gozar/main/packaging/ir-domains.txt HTTP/1.1\r\n" +
                    "Host: raw.githubusercontent.com\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                pipe.write(request, 0, request.size)
                val buffer = ByteArray(8192)
                val all = ByteArrayOutputStream()
                while (true) {
                    val count = pipe.read(buffer, 0, buffer.size)
                    if (count <= 0) break
                    all.write(buffer, 0, count)
                    if (all.size() > 1_100_000) return false
                }
                val bytes = all.toByteArray()
                val text = String(bytes, Charsets.ISO_8859_1)
                val split = text.indexOf("\r\n\r\n")
                if (split < 0) return false
                val head = text.substring(0, split)
                if (!head.startsWith("HTTP/") || !head.contains(" 200 ")) return false
                update(String(bytes, split + 4, bytes.size - split - 4, Charsets.UTF_8))
            } finally {
                pipe.close()
            }
        } catch (e: Exception) {
            false
        }
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
