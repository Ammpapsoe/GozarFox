package com.gozar

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Refuses to run a repackaged build. The pinned digest is the same official
 * signing certificate the release CI enforces (build.yml), so a modified APK
 * re-signed with any other key cannot use the tunnel.
 */
object TamperCheck {

    private const val PINNED = "bc9d3368f2671175f81be047a1ff72bca0cfc58e8b1b8f9f87e24694ba45f5b1"

    fun trusted(hashes: List<String>): Boolean = hashes.any { it.equals(PINNED, true) }

    fun verify(context: Context): Boolean {
        // Debug and unit-test builds carry the debug key by design.
        if (BuildConfig.DEBUG) return true
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                val info = context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signing = info.signingInfo ?: return false
                (if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory)
                    ?.toList() ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures?.toList() ?: emptyList()
            }
            trusted(signatures.map { FoxCrypto.hex(FoxCrypto.sha256(it.toByteArray())) })
        } catch (_: Exception) {
            false
        }
    }
}
