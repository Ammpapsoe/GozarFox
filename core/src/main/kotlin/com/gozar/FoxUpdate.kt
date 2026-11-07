package com.gozar

import org.json.JSONArray
import org.json.JSONObject

/**
 * Signed update manifests. A manifest is a small JSON document; its detached
 * Ed25519 signature is checked against the pinned release key before anything
 * else about it is trusted. The rules are fail-closed: anything unexpected is
 * a rejection with a reason, never an accept.
 *
 * Manifest schema:
 * {
 *   "version":     "1.11.1",                    // semantic, must match VERSION
 *   "channel_min": "1.8.0",                     // oldest version a rollback may target
 *   "rollback":    false,                       // true only for a rollback build
 *   "issued_at":   1793000000,                  // epoch seconds, sanity-checked
 *   "notes":       "...",                       // user-visible changelog text
 *   "artifacts": [ { "name": "...", "sha256": "<64 hex>", "size": 123, "url": "https://github.com/..." } ]
 * }
 */
object FoxUpdate {

    /** Pinned Ed25519 release public key (hex). The private half never ships. */
    const val KEY_HEX = "75ec9a23ecb173ddb9683e1c574515b3d4676453836804f327e86492896184e8"

    private const val MAX_MANIFEST_BYTES = 65_536
    private const val MAX_CLOCK_SKEW_SECONDS = 24L * 60 * 60
    private const val OWNER = "bardia049b-stack"
    private val REPOSITORIES = listOf("Gozar", "Gozar1")
    private val VERSION = Regex("^\\d{1,4}(\\.\\d{1,4}){1,3}(-[0-9A-Za-z.]{1,16})?$")
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    class Artifact(val name: String, val sha256: String, val size: Long, val url: String)

    class Manifest(
        val version: String,
        val channelMin: String,
        val rollback: Boolean,
        val issuedAt: Long,
        val notes: String,
        val artifacts: List<Artifact>
    ) {
        fun artifactFor(suffix: String): Artifact? = artifacts.firstOrNull { it.name.endsWith(suffix) }
    }

    sealed class Verdict {
        class Accept(val manifest: Manifest) : Verdict()
        // A current result is only possible after signature and schema checks.
        class Current(val manifest: Manifest) : Verdict()
        class Reject(val reason: String) : Verdict()
    }

    fun verify(
        manifestJson: String,
        signatureBase64: String,
        installed: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        keyHex: String = KEY_HEX
    ): Verdict {
        val raw = manifestJson.toByteArray(Charsets.UTF_8)
        if (raw.isEmpty() || raw.size > MAX_MANIFEST_BYTES) return Verdict.Reject("manifest outside the allowed size")
        val signature = try {
            base64Decode(signatureBase64)
        } catch (_: Exception) {
            return Verdict.Reject("signature is not valid base64")
        }
        val key = try {
            hex(keyHex)
        } catch (_: Exception) {
            return Verdict.Reject("release key is malformed")
        }
        if (!FoxSign.verify(key, raw, signature)) return Verdict.Reject("signature does not match the release key")

        val json = try {
            JSONObject(manifestJson)
        } catch (_: Exception) {
            return Verdict.Reject("manifest is not valid JSON")
        }
        val version = json.opt("version") as? String ?: return Verdict.Reject("version is malformed")
        val channelMin = json.opt("channel_min") as? String ?: return Verdict.Reject("channel_min is malformed")
        if (!validVersion(version)) return Verdict.Reject("version is malformed")
        if (!validVersion(channelMin)) return Verdict.Reject("channel_min is malformed")
        val rollback = json.opt("rollback") as? Boolean ?: return Verdict.Reject("rollback is malformed")
        val issuedAt = when (val value = json.opt("issued_at")) {
            is Int -> value.toLong()
            is Long -> value
            else -> return Verdict.Reject("issued_at is malformed")
        }
        if (issuedAt <= 0) return Verdict.Reject("issued_at is malformed")
        if (issuedAt > nowSeconds + MAX_CLOCK_SKEW_SECONDS) return Verdict.Reject("manifest is dated in the future")
        if (installed.isNotEmpty() && !validVersion(installed)) return Verdict.Reject("installed version is malformed")

        val artifacts = try {
            parseArtifacts(json.getJSONArray("artifacts"), version)
        } catch (error: Exception) {
            return Verdict.Reject(error.message ?: "artifacts are malformed")
        }
        if (artifacts.isEmpty()) return Verdict.Reject("manifest lists no artifacts")
        val manifest = Manifest(version, channelMin, rollback, issuedAt, json.optString("notes"), artifacts)

        if (rollback) {
            if (installed.isEmpty()) return Verdict.Reject("a rollback needs a known installed version")
            if (!isNewer(installed, version)) return Verdict.Reject("a rollback must target an older version")
            if (isNewer(channelMin, version)) return Verdict.Reject("the rollback target is below the channel floor")
        } else if (installed.isNotEmpty()) {
            if (version == installed) return Verdict.Current(manifest)
            if (!isNewer(version, installed)) return Verdict.Reject("the manifest is older than the installed build")
        }
        return Verdict.Accept(manifest)
    }

    private fun parseArtifacts(array: JSONArray, version: String): List<Artifact> {
        if (array.length() > 64) throw IllegalArgumentException("too many artifacts")
        val out = ArrayList<Artifact>()
        val names = HashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val name = item.opt("name") as? String ?: throw IllegalArgumentException("artifact name is malformed")
            val sha256 = item.opt("sha256") as? String ?: throw IllegalArgumentException("artifact sha256 is malformed")
            val size = when (val value = item.opt("size")) {
                is Int -> value.toLong()
                is Long -> value
                else -> throw IllegalArgumentException("artifact size is malformed")
            }
            val url = item.opt("url") as? String ?: throw IllegalArgumentException("artifact url is malformed")
            if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}").matches(name) || !names.add(name)) {
                throw IllegalArgumentException("artifact name is malformed or duplicated")
            }
            if (!SHA256_HEX.matches(sha256)) throw IllegalArgumentException("artifact sha256 is malformed")
            if (size <= 0 || size > 512L * 1024 * 1024) throw IllegalArgumentException("artifact size is malformed")
            if (!trusted(url)) throw IllegalArgumentException("artifact url is not a trusted release asset")
            val path = java.net.URI(url).rawPath
            if (!path.endsWith("/v$version/$name")) throw IllegalArgumentException("artifact does not match its release")
            out.add(Artifact(name, sha256, size, url))
        }
        return out
    }

    /** Only exact, unescaped https release-download paths in our repositories. */
    fun trusted(url: String): Boolean {
        val parsed = try { java.net.URI(url) } catch (_: Exception) { return false }
        if (!parsed.scheme.equals("https", true) || parsed.userInfo != null || parsed.port != -1) return false
        if (!parsed.host.equals("github.com", true) || parsed.rawQuery != null || parsed.rawFragment != null) return false
        val path = parsed.rawPath ?: return false
        if (path.contains('%') || parsed.normalize().rawPath != path) return false
        val parts = path.split('/')
        return parts.size == 7 && parts[0].isEmpty() && parts[1] == OWNER && parts[2] in REPOSITORIES &&
            parts[3] == "releases" && parts[4] == "download" &&
            Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}").matches(parts[5]) &&
            Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}").matches(parts[6])
    }

    /** Constant-shape check of downloaded bytes against the signed manifest entry. */
    fun verifyArtifact(bytes: ByteArray, artifact: Artifact): Boolean {
        if (bytes.size.toLong() != artifact.size) return false
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val actual = digest.joinToString("") { "%02x".format(it) }
        var difference = actual.length xor artifact.sha256.length
        for (index in actual.indices) {
            difference = difference or (actual[index].code xor artifact.sha256[index].code)
        }
        return difference == 0
    }

    fun versionParts(value: String): List<Int> =
        value.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }.take(4)

    /** Numeric core first, then SemVer-style prerelease precedence. */
    fun isNewer(candidate: String, baseline: String): Boolean {
        if (!validVersion(candidate) || !validVersion(baseline)) return false
        val left = versionParts(candidate)
        val right = versionParts(baseline)
        val count = maxOf(left.size, right.size, 3)
        for (index in 0 until count) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        val aPre = candidate.substringAfter('-', "")
        val bPre = baseline.substringAfter('-', "")
        if (aPre.isEmpty() || bPre.isEmpty()) return aPre.isEmpty() && bPre.isNotEmpty()
        val aParts = aPre.split('.')
        val bParts = bPre.split('.')
        for (index in 0 until minOf(aParts.size, bParts.size)) {
            val a = aParts[index]
            val b = bParts[index]
            if (a == b) continue
            val aNumber = a.toLongOrNull()
            val bNumber = b.toLongOrNull()
            if (aNumber != null && bNumber != null) {
                if (aNumber != bNumber) return aNumber > bNumber
            } else if (aNumber != null || bNumber != null) {
                return aNumber == null
            } else {
                return a > b
            }
        }
        return aParts.size > bParts.size
    }

    private fun validVersion(value: String): Boolean = VERSION.matches(value) &&
        (!value.contains('-') || value.substringAfter('-').split('.').all { it.isNotEmpty() })

    private fun hex(value: String): ByteArray {
        require(value.matches(Regex("[0-9a-fA-F]{64}")))
        return ByteArray(value.length / 2) {
            ((Character.digit(value[it * 2], 16) shl 4) or Character.digit(value[it * 2 + 1], 16)).toByte()
        }
    }

    /** Strict RFC 4648 base64 — java.util.Base64 needs API 26 and this module supports 24. */
    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun base64Decode(input: String): ByteArray {
        val body = input.trim()
        if (body.isEmpty() || body.length % 4 != 0) throw IllegalArgumentException("bad base64 length")
        val padding = body.count { it == '=' }
        if (padding > 2 || (padding > 0 && !body.endsWith("=".repeat(padding)))) {
            throw IllegalArgumentException("bad base64 padding")
        }
        val out = ByteArray(body.length / 4 * 3 - padding)
        var at = 0
        var buffer = 0
        var bits = 0
        for (character in body) {
            if (character == '=') continue
            val value = BASE64_ALPHABET.indexOf(character)
            if (value < 0) throw IllegalArgumentException("bad base64 character")
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[at++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        if (at != out.size || (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0)) {
            throw IllegalArgumentException("bad base64 body")
        }
        return out
    }
}
