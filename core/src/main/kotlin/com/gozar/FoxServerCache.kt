package com.gozar

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded, validated snapshot of Mozilla's public server list. It contains no
 * account credentials. The app seals the encoded text with the keystore before
 * it touches storage. Reuse never rewrites fetched_at, so a snapshot cannot be
 * kept alive by reconnects. Only a fresh successful fetch creates a new one.
 */
object FoxServerCache {
    const val LIFETIME_MS = 24L * 60 * 60 * 1000
    const val MAX_SKEW_MS = 5L * 60 * 1000
    const val MAX_ENTRIES = 512
    const val MAX_BYTES = 1 shl 20

    class Snapshot(val locations: List<FoxLocation>, val fetchedAt: Long, val expiresAt: Long)

    /** Returns null for an empty or invalid list. Invalid records are dropped, never repaired. */
    fun encode(locations: List<FoxLocation>, now: Long): String? {
        if (now <= 0L || locations.isEmpty() || locations.size > MAX_ENTRIES) return null
        val valid = locations.filter { valid(it) }
        if (valid.isEmpty()) return null
        val array = JSONArray()
        for (place in valid) {
            array.put(
                JSONObject()
                    .put("code", place.code)
                    .put("country", place.country)
                    .put("city", place.city)
                    .put("host", place.host)
                    .put("port", place.port)
                    .put("recommended", place.recommended)
            )
        }
        val text = JSONObject()
            .put("version", 1)
            .put("fetched_at", now)
            .put("locations", array)
            .toString()
        return if (text.length > MAX_BYTES) null else text
    }

    /** Null for malformed, future-dated, expired or invalid snapshots. */
    fun decode(raw: String, now: Long): Snapshot? {
        if (raw.isEmpty() || raw.length > MAX_BYTES) return null
        return try {
            val root = JSONObject(raw)
            if (root.optInt("version", -1) != 1) return null
            val fetchedAt = root.optLong("fetched_at", 0L)
            if (fetchedAt <= 0L || fetchedAt > now + MAX_SKEW_MS) return null
            val expiresAt = fetchedAt + LIFETIME_MS
            if (expiresAt <= now) return null
            val array = root.optJSONArray("locations") ?: return null
            if (array.length() == 0 || array.length() > MAX_ENTRIES) return null
            val out = ArrayList<FoxLocation>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: return null
                val place = FoxLocation(
                    code = item.optString("code", ""),
                    country = item.optString("country", ""),
                    city = item.optString("city", ""),
                    host = item.optString("host", ""),
                    port = item.optInt("port", 0),
                    recommended = item.optBoolean("recommended", false)
                )
                if (!valid(place)) return null
                out.add(place)
            }
            Snapshot(out, fetchedAt, expiresAt)
        } catch (_: Exception) {
            null
        }
    }

    internal fun valid(place: FoxLocation): Boolean {
        if (place.code.isEmpty() || place.code.length > 16 || place.country.length > 200 || place.city.length > 200) return false
        if (place.port !in 1..65535 || !validHost(place.host)) return false
        if (!place.code.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) return false
        return place.country.none { it.isISOControl() } && place.city.none { it.isISOControl() }
    }

    internal fun validHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        // IP literals are not accepted from a cached list.
        if (host.all { it in '0'..'9' || it == '.' } || host.contains(':')) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        return labels.all { label ->
            label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }
}
