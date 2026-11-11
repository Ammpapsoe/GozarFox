package com.gozar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The release-decision pipeline: a release only becomes an offered update
 * when GitHub serves both signed-manifest assets and the Ed25519 signature
 * plus every FoxUpdate rule accepts them. Everything else is a hard failure
 * (or "up to date" only when a valid signed manifest names the installed version).
 */
@RunWith(RobolectricTestRunner::class)
class UpdateManifestFlowTest {

    // RFC 8032 vector 1 — public test material, never the real release key.
    private val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val keyHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

    private val manifestBody = """
        {"version":"1.12.0","channel_min":"1.8.0","rollback":false,"issued_at":1793000000,
         "notes":"n","artifacts":[{"name":"Gozar_1.12.0_universal.apk","sha256":"${"a".repeat(64)}",
         "size":2048,"url":"https://github.com/Ammpapsoe/GozarFox/releases/download/v1.12.0/Gozar_1.12.0_universal.apk"}]}
    """.trimIndent().replace("\n", "").replace(" ", "")

    private fun signed(body: String) = base64(FoxSign.sign(seed, body.toByteArray(Charsets.UTF_8)))

    private fun release(vararg assetNames: String) = JSONObject().apply {
        val assets = org.json.JSONArray()
        for (name in assetNames) {
            assets.put(JSONObject().put("name", name).put("browser_download_url", "https://example.invalid/$name"))
        }
        put("assets", assets)
    }

    private fun verdict(release: JSONObject, manifest: String?, signature: String?, installed: String = "1.11.0") =
        UpdateChecker.verdictFrom(release, installed, keyHex) { name ->
            when (name) {
                "update.json" -> manifest
                "update.json.sig" -> signature
                else -> null
            }
        }

    @Test
    fun releasesWithoutBothSignedManifestAssetsAreFailed() {
        assertTrue(verdict(release("Gozar_1.12.0_universal.apk"), null, null) is UpdateChecker.Result.Failed)
        assertTrue(verdict(release("update.json", "gozar.apk"), manifestBody, null) is UpdateChecker.Result.Failed)
        assertTrue(verdict(release("update.json.sig", "gozar.apk"), null, signed(manifestBody)) is UpdateChecker.Result.Failed)
    }

    @Test
    fun aValidSignedManifestBecomesAnAvailableUpdateWithArtifactProof() {
        val result = verdict(release("update.json", "update.json.sig"), manifestBody, signed(manifestBody))
        assertTrue(result is UpdateChecker.Result.Available)
        val available = result as UpdateChecker.Result.Available
        assertEquals("1.12.0", available.version)
        assertEquals("a".repeat(64), available.sha256)
        assertEquals(2048L, available.size)
        assertTrue(available.url.endsWith("Gozar_1.12.0_universal.apk"))
    }

    @Test
    fun forwardRecoveryMustAdvanceVersionCodeAndMatchTheAffectedBuild() {
        val metadata = """"recovery":{"affected_version":"1.11.0","affected_code":37,"affected_commit":"${"a".repeat(40)}","restores_version":"1.10.0","restores_code":36,"restores_commit":"${"b".repeat(40)}","recovery_code":38}"""
        val body = manifestBody.replace("\"notes\":\"n\",", "\"notes\":\"n\",$metadata,")
        val release = release("update.json", "update.json.sig")
        fun check(code: Long): UpdateChecker.Result = UpdateChecker.verdictFrom(
            release, "1.11.0", keyHex, listOf("x86_64"), installedCode = code
        ) { name -> if (name == "update.json") body else signed(body) }
        val targeted = check(37) as UpdateChecker.Result.Available
        assertEquals("1.10.0", targeted.recovery?.restoresVersion)
        assertTrue(check(38) is UpdateChecker.Result.Failed)
        assertEquals(null, (check(36) as UpdateChecker.Result.Available).recovery)
    }

    @Test
    fun tamperedManifestsAreFailedNotDowngraded() {
        val tampered = manifestBody.replace("1.12.0", "9.9.9")
        assertTrue(
            verdict(release("update.json", "update.json.sig"), tampered, signed(manifestBody))
                is UpdateChecker.Result.Failed
        )
    }

    @Test
    fun aManifestDeclaringNothingNewerMeansUpToDate() {
        val same = manifestBody.replace("1.12.0", "1.11.0").replace("v1.12.0", "v1.11.0")
        val result = verdict(release("update.json", "update.json.sig"), same, signed(same))
        assertTrue("expected Current, got $result", result is UpdateChecker.Result.Current)
    }

    @Test
    fun anUnsignedOrReplayedManifestNeverReportsUpToDate() {
        val same = manifestBody.replace("1.12.0", "1.11.0")
        val older = manifestBody.replace("1.12.0", "1.10.0")
        val release = release("update.json", "update.json.sig")
        assertTrue(verdict(release, same, signed(manifestBody)) is UpdateChecker.Result.Failed)
        assertTrue(verdict(release, same, "invalid") is UpdateChecker.Result.Failed)
        assertTrue(verdict(release, older, signed(older)) is UpdateChecker.Result.Failed)
    }

    @Test
    fun signatureCoversTheExactManifestBytes() {
        val spaced = "  $manifestBody\n"
        val release = release("update.json", "update.json.sig")
        assertTrue(verdict(release, spaced, signed(spaced)) is UpdateChecker.Result.Available)
        assertTrue(verdict(release, spaced, signed(manifestBody)) is UpdateChecker.Result.Failed)
    }

    @Test
    fun androidDoesNotOfferAnOldSignedRollbackApk() {
        val rollback = manifestBody.replace("1.12.0", "1.10.0").replace("\"rollback\":false", "\"rollback\":true")
        assertTrue(verdict(release("update.json", "update.json.sig"), rollback, signed(rollback)) is UpdateChecker.Result.Failed)
    }

    @Test
    fun boundedTextReadingCountsBytesRatherThanCharacters() {
        val bytes = "سلام".toByteArray(Charsets.UTF_8)
        assertEquals("سلام", UpdateChecker.readLimited(bytes.inputStream(), bytes.size))
        assertEquals(null, UpdateChecker.readLimited(bytes.inputStream(), bytes.size - 1))
    }

    @Test
    fun aSignedManifestWithoutAnApkArtifactIsFailed() {
        val noApk = manifestBody.replace("Gozar_1.12.0_universal.apk", "gozar-1.12.0-desktop.tar.zst")
        val result = verdict(release("update.json", "update.json.sig"), noApk, signed(noApk))
        assertTrue(result is UpdateChecker.Result.Failed)
    }

    @Test
    fun theDeviceAbiSelectsItsArtifactWithUniversalFallback() {
        val multi = """{"version":"1.12.0","channel_min":"1.8.0","rollback":false,"issued_at":1793000000,"notes":"n","artifacts":[""" +
            """{"name":"Gozar_1.12.0_arm64-v8a.apk","sha256":"${"a".repeat(64)}","size":1000,"url":"https://github.com/Ammpapsoe/GozarFox/releases/download/v1.12.0/Gozar_1.12.0_arm64-v8a.apk"},""" +
            """{"name":"Gozar_1.12.0_universal.apk","sha256":"${"b".repeat(64)}","size":2000,"url":"https://github.com/Ammpapsoe/GozarFox/releases/download/v1.12.0/Gozar_1.12.0_universal.apk"}]}"""
        val release = release("update.json", "update.json.sig")
        val arm = UpdateChecker.verdictFrom(release, "1.11.0", keyHex, listOf("arm64-v8a", "armeabi-v7a")) { name ->
            if (name == "update.json") multi else if (name == "update.json.sig") signed(multi) else null
        } as UpdateChecker.Result.Available
        assertEquals(1000L, arm.size)
        assertTrue(arm.url.endsWith("arm64-v8a.apk"))
        val other = UpdateChecker.verdictFrom(release, "1.11.0", keyHex, listOf("riscv64")) { name ->
            if (name == "update.json") multi else if (name == "update.json.sig") signed(multi) else null
        } as UpdateChecker.Result.Available
        assertEquals(2000L, other.size)
        assertTrue(other.url.endsWith("universal.apk"))
    }

    @Test
    fun anIncompatibleApkIsNotSelectedAsFallback() {
        val armOnly = manifestBody.replace("_universal.apk", "_arm64-v8a.apk")
        val result = UpdateChecker.verdictFrom(
            release("update.json", "update.json.sig"), "1.11.0", keyHex, listOf("x86_64")
        ) { name -> if (name == "update.json") armOnly else signed(armOnly) }
        assertTrue(result is UpdateChecker.Result.Failed)
    }

    @Test
    fun theStoreRoundTripsTheArtifactProof() {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        Store.setUpdate(context, "1.12.0", "https://github.com/x", "ab".repeat(32), 4096)
        assertEquals("1.12.0", Store.updateVersion(context))
        assertEquals("ab".repeat(32), Store.updateSha(context))
        assertEquals(4096L, Store.updateSize(context))
        Store.clearUpdate(context)
        assertEquals("", Store.updateSha(context))
        assertEquals(0L, Store.updateSize(context))
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) {
        ((Character.digit(value[it * 2], 16) shl 4) or Character.digit(value[it * 2 + 1], 16)).toByte()
    }

    private fun base64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder()
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
            out.append(alphabet[b0 shr 2])
            out.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
            out.append(if (index + 1 < bytes.size) alphabet[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
            out.append(if (index + 2 < bytes.size) alphabet[b2 and 0x3F] else '=')
            index += 3
        }
        return out.toString()
    }
}
