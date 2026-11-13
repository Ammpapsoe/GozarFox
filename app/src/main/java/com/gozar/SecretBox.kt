package com.gozar

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecretBox {

    private const val ALIAS = "gozar.session.v1"
    private const val HARDWARE = "h1:"
    private const val SOFTWARE = "h0:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val SOFTWARE_KEY = "gozar-session.key"

    private val random = SecureRandom()
    private val lock = Any()

    fun seal(context: Context, value: String): String {
        if (value.isEmpty() || isSealed(value)) return value
        val plain = value.toByteArray(Charsets.UTF_8)
        try {
            keystoreKey(true)?.let { key ->
                runCatching { return HARDWARE + encode(key, plain) }
            }
            val fallback = softwareKey(context) ?: return ""
            return SOFTWARE + encode(fallback, plain)
        } finally {
            // The UTF-8 copy dies here; the sealed base64 is what survives.
            plain.fill(0)
        }
    }

    fun open(context: Context, value: String): String {
        if (value.isEmpty()) return value
        val key = when {
            value.startsWith(HARDWARE) -> keystoreKey(false) ?: return ""
            value.startsWith(SOFTWARE) -> softwareKey(context) ?: return ""
            else -> return value
        }
        return runCatching {
            val plain = decode(key, value.substring(3))
            try {
                String(plain, Charsets.UTF_8)
            } finally {
                plain.fill(0)
            }
        }.getOrDefault("")
    }

    fun isSealed(value: String): Boolean = value.startsWith(HARDWARE) || value.startsWith(SOFTWARE)

    private fun encode(key: SecretKey, plain: ByteArray): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD("com.gozar".toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(plain)
        val packed = ByteArray(iv.size + body.size)
        iv.copyInto(packed)
        body.copyInto(packed, iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decode(key: SecretKey, payload: String): ByteArray {
        val packed = Base64.decode(payload, Base64.NO_WRAP)
        require(packed.size > IV_BYTES + TAG_BITS / 8) { "short ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES))
        cipher.updateAAD("com.gozar".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES)
    }

    private fun keystoreKey(create: Boolean): SecretKey? = runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: if (!create) null else generate()
    }.getOrNull()

    private fun generate(): SecretKey {
        for (size in intArrayOf(256, 128)) {
            runCatching {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(size)
                        .setUserAuthenticationRequired(false)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                return generator.generateKey()
            }
        }
        throw IllegalStateException("the keystore rejected the session key")
    }

    private fun softwareKey(context: Context): SecretKey? = synchronized(lock) {
        runCatching {
            val file = File(context.applicationContext.noBackupFilesDir, SOFTWARE_KEY)
            val bytes = if (file.length() == 32L) file.readBytes() else ByteArray(32).also { fresh ->
                random.nextBytes(fresh)
                file.parentFile?.mkdirs()
                file.writeBytes(fresh)
            }
            SecretKeySpec(bytes, "AES")
        }.getOrNull()
    }
}
