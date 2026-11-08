package com.gozar

/** API-24-safe base64 for Hawk authentication and device identities. */
internal object FoxBase64 {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val n = minOf(3, bytes.size - i)
            val value = ((bytes[i].toInt() and 255) shl 16) or
                (if (n > 1) (bytes[i + 1].toInt() and 255) shl 8 else 0) or
                (if (n > 2) bytes[i + 2].toInt() and 255 else 0)
            out.append(alphabet[(value ushr 18) and 63]); out.append(alphabet[(value ushr 12) and 63])
            out.append(if (n > 1) alphabet[(value ushr 6) and 63] else '=')
            out.append(if (n > 2) alphabet[value and 63] else '=')
            i += n
        }
        return out.toString()
    }
    fun decode(text: String): ByteArray {
        val input = text.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
            .filterNot { it.isWhitespace() }
        require(input.isNotEmpty() && input.length % 4 == 0)
        val padding = input.count { it == '=' }
        require(padding <= 2 && (padding == 0 || input.endsWith("=".repeat(padding))))
        val out = ByteArray(input.length / 4 * 3 - padding)
        var bits = 0; var buffer = 0; var index = 0
        for (ch in input) {
            if (ch == '=') continue
            val value = alphabet.indexOf(ch); require(value >= 0)
            buffer = (buffer shl 6) or value; bits += 6
            if (bits >= 8) { bits -= 8; out[index++] = ((buffer ushr bits) and 255).toByte() }
        }
        require(index == out.size && (bits == 0 || buffer and ((1 shl bits) - 1) == 0))
        return out
    }
}
