package com.gozar

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signing and verification per RFC 8032, in plain Kotlin arithmetic so
 * it behaves identically on the desktop JVM and on every Android API level
 * (the platform "Ed25519" provider only exists from API 33). Verification is
 * the security-critical path for update manifests; signing is used by the
 * release tooling and by the tests.
 *
 * The twisted Edwards addition law is unified — the identity (0, 1) and point
 * doubling need no special cases — so the implementation stays small enough
 * to audit against the RFC vectors in FoxSignTest.
 */
object FoxSign {

    private val P = BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16)
    private val L = BigInteger("1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed", 16)
    private val D = BigInteger("52036cee2b6ffe738cc740797779e89800700a4d4141d8ab75eb4dca135978a3", 16)
    // sqrt(-1) mod P, used when recovering x needs the other square root.
    private val SQRT_M1 = BigInteger("2b8324804fc1df0b2b4d00993dfbd7a72f431806ad2fe478c4ee1b274a0ea0b0", 16)
    private val BASE_X = BigInteger("216936d3cd6e53fec0a4e231fdd6dc5c692cc7609525a7b2c9562d608f25d51a", 16)
    private val BASE_Y = BigInteger("6666666666666666666666666666666666666666666666666666666666666658", 16)
    private val BASE = Point(BASE_X, BASE_Y)

    private class Point(val x: BigInteger, val y: BigInteger)

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    private fun littleEndianBytes(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        var rest = value
        for (index in 0 until 32) {
            out[index] = rest.and(BigInteger.valueOf(255)).toByte()
            rest = rest.shiftRight(8)
        }
        return out
    }

    private fun add(p: Point, q: Point): Point {
        // Twisted Edwards curve with a = -1:
        //   x3 = (x1 y2 + y1 x2) / (1 + d x1 x2 y1 y2)
        //   y3 = (y1 y2 + x1 x2) / (1 - d x1 x2 y1 y2)
        val x1y2 = p.x.multiply(q.y)
        val y1x2 = p.y.multiply(q.x)
        val y1y2 = p.y.multiply(q.y)
        val x1x2 = p.x.multiply(q.x)
        val t = D.multiply(x1y2).multiply(y1x2).mod(P)
        val x3 = x1y2.add(y1x2).mod(P).multiply(BigInteger.ONE.add(t).modInverse(P)).mod(P)
        val y3 = y1y2.add(x1x2).mod(P).multiply(BigInteger.ONE.subtract(t).mod(P).modInverse(P)).mod(P)
        return Point(x3, y3)
    }

    private fun multiply(scalar: BigInteger, point: Point): Point {
        var result = Point(BigInteger.ZERO, BigInteger.ONE)
        var current = point
        require(scalar.signum() >= 0)
        // Do not reduce by L here: subgroup validation must really compute
        // [L]P, including when P has a non-prime-order component.
        var rest = scalar
        while (rest.signum() > 0) {
            if (rest.testBit(0)) result = add(result, current)
            current = add(current, current)
            rest = rest.shiftRight(1)
        }
        return result
    }

    /**
     * Decodes a compressed point (little-endian y with the sign of x in the
     * top bit). Returns null when the encoding is invalid: y out of range, no
     * square root, or the rejected x == 0 with the sign bit set.
     */
    private fun decode(encoded: ByteArray): Point? {
        if (encoded.size != 32) return null
        val raw = encoded.copyOf()
        val sign = (raw[31].toInt() shr 7) and 1
        raw[31] = (raw[31].toInt() and 0x7F).toByte()
        val y = littleEndianOfBytes(raw)
        if (y >= P) return null
        val y2 = y.multiply(y).mod(P)
        // x^2 = (y^2 - 1) / (d y^2 + 1)   on  -x^2 + y^2 = 1 + d x^2 y^2
        val numerator = y2.subtract(BigInteger.ONE).mod(P)
        val denominator = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val x2 = numerator.multiply(denominator.modInverse(P)).mod(P)
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).mod(P) != x2) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).mod(P) != x2) return null
        if (x.signum() == 0 && sign == 1) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return Point(x, y)
    }

    private fun littleEndianOfBytes(raw: ByteArray): BigInteger {
        var result = BigInteger.ZERO
        for (index in raw.indices.reversed()) {
            result = result.shiftLeft(8).or(BigInteger.valueOf((raw[index].toInt() and 0xFF).toLong()))
        }
        return result
    }

    private fun encode(point: Point): ByteArray {
        val out = littleEndianBytes(point.y)
        if (point.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** RFC 8032 secret clamping: the scalar derived from a 32-byte seed. */
    private fun clamp(seed: ByteArray): BigInteger {
        if (seed.size != 32) throw IllegalArgumentException("an Ed25519 seed is 32 bytes")
        val hash = sha512(seed).copyOfRange(0, 32)
        hash[0] = (hash[0].toInt() and 248).toByte()
        hash[31] = (hash[31].toInt() and 127).toByte()
        hash[31] = (hash[31].toInt() or 64).toByte()
        return littleEndianOfBytes(hash)
    }

    fun publicFromSeed(seed: ByteArray): ByteArray = encode(multiply(clamp(seed), BASE))

    /** Deterministic RFC 8032 signature: 32-byte R followed by 32-byte S. */
    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val hash = sha512(seed)
        val scalar = clamp(seed)
        val prefix = hash.copyOfRange(32, 64)
        val publicKey = encode(multiply(scalar, BASE))
        val r = littleEndianOfBytes(sha512(prefix, message)).mod(L)
        val encodedR = encode(multiply(r, BASE))
        val k = littleEndianOfBytes(sha512(encodedR, publicKey, message)).mod(L)
        val s = r.add(k.multiply(scalar)).mod(L)
        return encodedR + littleEndianBytes(s)
    }

    fun verify(public: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (public.size != 32 || signature.size != 64) return false
        return try {
            val pointA = decode(public) ?: return false
            val encodedR = signature.copyOfRange(0, 32)
            val pointR = decode(encodedR) ?: return false
            val s = littleEndianOfBytes(signature.copyOfRange(32, 64))
            if (s >= L) return false
            fun identity(point: Point) = point.x == BigInteger.ZERO && point.y == BigInteger.ONE
            // Identity, low-order and mixed-order points are never acceptable
            // release keys or nonce points. Check the actual prime subgroup.
            if (identity(pointA) || identity(pointR) ||
                !identity(multiply(L, pointA)) || !identity(multiply(L, pointR))) return false
            val k = littleEndianOfBytes(sha512(encodedR, public, message)).mod(L)
            val left = multiply(s, BASE)
            val right = add(pointR, multiply(k, pointA))
            left.x == right.x && left.y == right.y
        } catch (_: ArithmeticException) {
            false
        }
    }
}
