package com.gozar

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object FoxPinnedTls {
    val WORKER_SPKI_SHA256_PINS = setOf(
        "31e940075ef2f2bc22ec290916699ab9dc0ae463e81d68023415e7e8e28f621b"
    )

    fun socketFactory(): SSLSocketFactory = socketFactory(WORKER_SPKI_SHA256_PINS, defaultTrustManagers())

    internal fun socketFactory(pinHex: String, managers: Array<TrustManager>): SSLSocketFactory = socketFactory(setOf(pinHex), managers)

    internal fun socketFactory(pins: Set<String>, managers: Array<TrustManager>): SSLSocketFactory {
        val expected = pins.map { decodeHex(it) ?: throw IllegalArgumentException("invalid relay pin") }
        require(expected.isNotEmpty() && expected.all { it.size == 32 }) { "invalid relay pin" }
        val pinned = managers.map { manager ->
            val trust = manager as? X509TrustManager ?: throw IllegalArgumentException("X509 trust required")
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = trust.checkClientTrusted(chain, authType)
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    trust.checkServerTrusted(chain, authType)
                    if (expected.none { matches(chain, it) }) throw java.security.cert.CertificateException("relay public key pin mismatch")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = trust.acceptedIssuers
            }
        }.toTypedArray()
        return SSLContext.getInstance("TLS").apply { init(null, pinned, null) }.socketFactory
    }

    internal fun matches(chain: Array<X509Certificate>, expected: ByteArray): Boolean {
        if (chain.isEmpty() || expected.size != 32) return false
        val encoded = chain[0].publicKey?.encoded ?: return false
        val actual = MessageDigest.getInstance("SHA-256").digest(encoded)
        return MessageDigest.isEqual(actual, expected)
    }

    internal fun decodeHex(text: String): ByteArray? {
        if (text.length % 2 != 0 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(text.length / 2) { index -> text.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun defaultTrustManagers(): Array<TrustManager> {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers
    }
}
