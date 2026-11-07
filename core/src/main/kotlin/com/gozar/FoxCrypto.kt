package com.gozar

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object FoxCrypto {

    private const val PREFIX = "identity.mozilla.com/picl/v1/"
    private const val MAC = "HmacSHA256"

    /**
     * Zeroes key material as soon as it is spent, so derived secrets do not
     * linger in the heap waiting for a memory dump. JVM strings cannot be
     * zeroed — that is why secrets move through this codebase as ByteArray.
     */
    fun burn(vararg buffers: ByteArray?) {
        for (buffer in buffers) buffer?.fill(0)
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(MAC)
        mac.init(SecretKeySpec(key, MAC))
        return mac.doFinal(data)
    }

    fun pbkdf2(password: ByteArray, salt: ByteArray, rounds: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        var block = 1
        var done = 0
        while (done < length) {
            val index = byteArrayOf(
                (block ushr 24).toByte(),
                (block ushr 16).toByte(),
                (block ushr 8).toByte(),
                block.toByte()
            )
            var u = hmac(password, salt + index)
            val t = u.copyOf()
            var round = 1
            while (round < rounds) {
                u = hmac(password, u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
                round++
            }
            val take = minOf(length - done, t.size)
            System.arraycopy(t, 0, out, done, take)
            done += take
            block++
        }
        return out
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt ?: ByteArray(32), ikm)
        val out = ByteArray(length)
        var prev = ByteArray(0)
        var done = 0
        var counter = 1
        while (done < length) {
            prev = hmac(prk, prev + info + byteArrayOf(counter.toByte()))
            val take = minOf(length - done, prev.size)
            System.arraycopy(prev, 0, out, done, take)
            done += take
            counter++
        }
        return out
    }

    fun authPw(email: String, password: String): ByteArray {
        val salt = (PREFIX + "quickStretch:" + email).toByteArray(Charsets.UTF_8)
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val stretched = pbkdf2(passwordBytes, salt, 1000, 32)
        burn(passwordBytes)
        val auth = hkdf(stretched, byteArrayOf(0), (PREFIX + "authPW").toByteArray(Charsets.UTF_8), 32)
        burn(stretched)
        return auth
    }

    fun hawkId(tokenHex: String): String {
        val raw = unhex(tokenHex)
        val keys = hkdf(raw, null, (PREFIX + "sessionToken").toByteArray(Charsets.UTF_8), 96)
        burn(raw)
        val id = hex(keys.copyOfRange(0, 32))
        burn(keys)
        return id
    }

    fun hawkKey(tokenHex: String): ByteArray {
        val raw = unhex(tokenHex)
        val keys = hkdf(raw, null, (PREFIX + "sessionToken").toByteArray(Charsets.UTF_8), 96)
        burn(raw)
        val key = keys.copyOfRange(32, 64)
        burn(keys)
        return key
    }

    fun hawk(method: String, url: java.net.URL, id: String, key: ByteArray, payload: String): String {
        val nonceBytes = ByteArray(6)
        SecureRandom().nextBytes(nonceBytes)
        val nonce = hex(nonceBytes)
        val ts = (System.currentTimeMillis() / 1000).toString()
        val port = if (url.port > 0) url.port.toString() else if (url.protocol == "https") "443" else "80"
        val path = if (url.query.isNullOrEmpty()) url.path else url.path + "?" + url.query
        var payloadHash = ""
        if (payload.isNotEmpty()) {
            payloadHash = hex(sha256("hawk.1.payload\napplication/json\n$payload\n".toByteArray(Charsets.UTF_8)))
        }
        val normalized = listOf(
            "hawk.1.header",
            ts,
            nonce,
            method.uppercase(),
            path,
            url.host,
            port,
            payloadHash,
            "",
            ""
        ).joinToString("\n")
        val mac = hex(hmac(key, normalized.toByteArray(Charsets.UTF_8)))
        val header = "Hawk id=\"$id\", ts=\"$ts\", nonce=\"$nonce\", mac=\"$mac\""
        return if (payloadHash.isNotEmpty()) "$header, hash=\"$payloadHash\"" else header
    }

    fun sha256(data: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(data)

    fun hex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    fun unhex(text: String): ByteArray {
        val clean = text.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((hexValue(clean[i * 2]) shl 4) or hexValue(clean[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }
}
