package com.gozar

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Best-effort local signing-certificate check, using the official digest that
 * release CI also verifies. This is not a defense against an attacker who can
 * modify the check itself; do not treat it as proof of device integrity.
 */
object TamperCheck {

    private const val PINNED = "800d24fa5cc38d2a63c216051d4710b950b0b3c4f457096ccfc11794a2b3bfd3"

    fun trusted(hashes: List<String>): Boolean = hashes.any { it.equals(PINNED, true) }
    internal fun trustedCurrentSigners(hashes: List<String>): Boolean = hashes.size == 1 && trusted(hashes)

    fun verify(context: Context): Boolean {
        // The build variant is compiled into this code. A repackager setting
        // the manifest's debuggable flag alone must not bypass a release check.
        if (BuildConfig.DEBUG) return true
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val info = context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signing = info.signingInfo ?: return false
                // Never accept an additional current signer merely because one
                // of the current signatures happens to be the pinned one.
                if (signing.hasMultipleSigners()) return false
                val history = signing.signingCertificateHistory?.toList() ?: return false
                trusted(history.map { FoxCrypto.hex(FoxCrypto.sha256(it.toByteArray())) })
            } else {
                @Suppress("DEPRECATION")
                val signatures = context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures?.toList() ?: emptyList()
                trustedCurrentSigners(signatures.map { FoxCrypto.hex(FoxCrypto.sha256(it.toByteArray())) })
            }
        } catch (_: Exception) {
            false
        }
    }
}
