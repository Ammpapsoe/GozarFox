package com.gozar

import java.security.MessageDigest

/**
 * The edge's proof of work, solved from its published rules.
 *
 * This is not a difficulty-target grind: the edge hands out a random [base]
 * and the SHA-256 digest of `base` plus an unknown two-character suffix, and
 * asks for that suffix. The character set is the 62 alphanumeric characters,
 * so the whole search space is 3844 digests — milliseconds of work, which is
 * why it still runs only where the caller put it (a worker thread) and why
 * splitting it across cores would buy nothing measurable.
 *
 * Written from the protocol itself; no other implementation was copied.
 */
object ProofOfWork {

    private const val CHARACTERS =
        "abcdefghijklmnopqrstuvwxyz" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
            "0123456789"

    private const val DIGEST_BYTES = 32

    /**
     * Returns the two-character suffix whose digest over [base] equals
     * [targetHex], or null when the target is malformed or unreachable.
     * [keepGoing] is polled inside the loop so a caller can cancel mid-search
     * without waiting for the 3844th digest.
     */
    fun findSuffix(
        base: String,
        targetHex: String,
        keepGoing: () -> Boolean = { true },
    ): String? {
        val target = bytesOf(targetHex) ?: return null
        if (target.size != DIGEST_BYTES) return null
        val head = base.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val candidate = ByteArray(head.size + 2)
        System.arraycopy(head, 0, candidate, 0, head.size)
        for (first in CHARACTERS.indices) {
            candidate[head.size] = CHARACTERS[first].code.toByte()
            for (second in CHARACTERS.indices) {
                if (!keepGoing()) return null
                candidate[head.size + 1] = CHARACTERS[second].code.toByte()
                digest.reset()
                if (digest.digest(candidate).contentEquals(target)) {
                    return CHARACTERS.substring(first, first + 1) +
                        CHARACTERS.substring(second, second + 1)
                }
            }
        }
        return null
    }

    /** Strict hex decoding: odd length or a non-hex digit means null. */
    internal fun bytesOf(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            val high = Character.digit(hex[index * 2], 16)
            val low = Character.digit(hex[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }
}
