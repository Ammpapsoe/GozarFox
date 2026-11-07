package com.gozar

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Update-signing tests: the RFC 8032 Ed25519 vectors pin the crypto, the
 * manifest matrix pins the fail-closed verification rules, and the journal
 * tests pin the rollback decision.
 */
class FoxUpdateTest {

    @get:Rule
    val folder = TemporaryFolder()

    // RFC 8032, section 7.1 — public test material.
    private val seed1 = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val public1 = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val seed2 = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
    private val public2 = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
    private val public3 = hex("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025")
    private val seed3 = hex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7")

    // ------------------------------------------------------------------
    // Ed25519 against RFC 8032
    // ------------------------------------------------------------------

    @Test
    fun publicKeysDeriveFromSeedsPerRfc8032() {
        assertTrue(public1.contentEquals(FoxSign.publicFromSeed(seed1)))
        assertTrue(public2.contentEquals(FoxSign.publicFromSeed(seed2)))
        assertTrue(public3.contentEquals(FoxSign.publicFromSeed(seed3)))
    }

    @Test
    fun signaturesMatchRfc8032Vectors() {
        val expected1 = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e0652249015" +
                "55fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )
        val expected2 = hex(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69d" +
                "a085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
        )
        val expected3 = hex(
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3a" +
                "c18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"
        )
        assertTrue(expected1.contentEquals(FoxSign.sign(seed1, ByteArray(0))))
        assertTrue(expected2.contentEquals(FoxSign.sign(seed2, byteArrayOf(0x72))))
        assertTrue(expected3.contentEquals(FoxSign.sign(seed3, hex("af82"))))
    }

    @Test
    fun verifierAcceptsRfc8032Vectors() {
        assertTrue(FoxSign.verify(public1, ByteArray(0), FoxSign.sign(seed1, ByteArray(0))))
        assertTrue(FoxSign.verify(public2, byteArrayOf(0x72), FoxSign.sign(seed2, byteArrayOf(0x72))))
        assertTrue(FoxSign.verify(public3, hex("af82"), FoxSign.sign(seed3, hex("af82"))))
    }

    @Test
    fun verifierRejectsEveryTamperingShape() {
        val message = "the manifest body".toByteArray(Charsets.UTF_8)
        val signature = FoxSign.sign(seed1, message)
        assertTrue(FoxSign.verify(public1, message, signature))
        // Flipped bit in the message.
        val tamperedMessage = message.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertFalse(FoxSign.verify(public1, tamperedMessage, signature))
        // Flipped bit inside R.
        val tamperedR = signature.copyOf().also { it[10] = (it[10].toInt() xor 8).toByte() }
        assertFalse(FoxSign.verify(public1, message, tamperedR))
        // Flipped bit inside S.
        val tamperedS = signature.copyOf().also { it[40] = (it[40].toInt() xor 8).toByte() }
        assertFalse(FoxSign.verify(public1, message, tamperedS))
        // The wrong public key.
        assertFalse(FoxSign.verify(public2, message, signature))
        // Truncated forms.
        assertFalse(FoxSign.verify(public1, message, signature.copyOfRange(0, 63)))
        assertFalse(FoxSign.verify(public1.copyOfRange(0, 31), message, signature))
        // S at the group order must be rejected.
        val order = java.math.BigInteger("1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed", 16)
        val orderBytes = ByteArray(32)
        var rest = order
        for (index in 0 until 32) {
            orderBytes[index] = rest.and(java.math.BigInteger.valueOf(255)).toByte()
            rest = rest.shiftRight(8)
        }
        val forgedS = signature.copyOfRange(0, 32) + orderBytes
        assertFalse(FoxSign.verify(public1, message, forgedS))
    }

    @Test
    fun verifierRejectsIdentityAndSmallOrderForgeries() {
        val identity = ByteArray(32).also { it[0] = 1 }
        val zero = ByteArray(32)
        val forged = identity + ByteArray(32)
        assertFalse(FoxSign.verify(identity, "anything".toByteArray(), forged))
        assertFalse(FoxSign.verify(zero, "anything".toByteArray(), forged))
        assertFalse(FoxSign.verify(public1, "anything".toByteArray(), forged))
        assertFalse(FoxSign.verify(ByteArray(32) { 0xff.toByte() }, ByteArray(0), forged))
    }

    @Test
    fun signingIsDeterministicAndSurvivesLargeMessages() {
        val message = ByteArray(5000) { (it % 251).toByte() }
        val first = FoxSign.sign(seed2, message)
        assertTrue(first.contentEquals(FoxSign.sign(seed2, message)))
        assertTrue(FoxSign.verify(public2, message, first))
    }

    // ------------------------------------------------------------------
    // Manifest verification
    // ------------------------------------------------------------------

    private val keyHex = public1.joinToString("") { "%02x".format(it) }
    private val otherKeyHex = public2.joinToString("") { "%02x".format(it) }

    private fun manifest(
        version: String = "1.12.0",
        channelMin: String = "1.8.0",
        rollback: Boolean = false,
        issuedAt: Long = 1_793_000_000,
        artifacts: String = """[{"name":"gozar-$version.apk","sha256":"${"a".repeat(64)}","size":1048576,"url":"https://github.com/bardia049b-stack/Gozar/releases/download/v$version/gozar-$version.apk"}]"""
    ): String = """{"version":"$version","channel_min":"$channelMin","rollback":$rollback,"issued_at":$issuedAt,"notes":"n","artifacts":$artifacts}"""

    private fun sign(body: String): String = base64(FoxSign.sign(seed1, body.toByteArray(Charsets.UTF_8)))

    private fun expect(body: String, signature: String, installed: String = "1.11.0", key: String = keyHex, now: Long = 1_793_100_000): FoxUpdate.Verdict =
        FoxUpdate.verify(body, signature, installed, now, key)

    @Test
    fun aCorrectlySignedNewerManifestIsAccepted() {
        val body = manifest()
        val verdict = expect(body, sign(body))
        assertTrue("expected accept, got ${(verdict as? FoxUpdate.Verdict.Reject)?.reason}", verdict is FoxUpdate.Verdict.Accept)
        val accepted = (verdict as FoxUpdate.Verdict.Accept).manifest
        assertEquals("1.12.0", accepted.version)
        assertEquals(1, accepted.artifacts.size)
        assertEquals("gozar-1.12.0.apk", accepted.artifacts[0].name)
        assertNotNull(accepted.artifactFor(".apk"))
    }

    @Test
    fun signedForwardRecoveryCarriesConsistentVersionCodesAndProvenance() {
        val recovery = """"recovery":{"affected_version":"1.11.0","affected_code":37,"affected_commit":"${"a".repeat(40)}","restores_version":"1.10.0","restores_code":36,"restores_commit":"${"b".repeat(40)}","recovery_code":38}"""
        val body = manifest().replace("\"notes\":\"n\",", "\"notes\":\"n\",$recovery,")
        val accepted = expect(body, sign(body)) as FoxUpdate.Verdict.Accept
        assertEquals("1.11.0", accepted.manifest.recovery?.affectedVersion)
        assertEquals("1.10.0", accepted.manifest.recovery?.restoresVersion)
        assertEquals(38L, accepted.manifest.recovery?.recoveryCode)
        assertTrue(expect(body.replace("\"recovery_code\":38", "\"recovery_code\":39"), sign(body)) is FoxUpdate.Verdict.Reject)
        for (invalid in listOf(
            body.replace("\"recovery_code\":38", "\"recovery_code\":37"),
            body.replace("\"affected_code\":37", "\"affected_code\":\"37\""),
            body.replace("\"restores_version\":\"1.10.0\"", "\"restores_version\":\"1.12.0\""),
            body.replace("\"restores_commit\":\"${"b".repeat(40)}\"", "\"restores_commit\":\"bad\""),
            body.replace("\"rollback\":false", "\"rollback\":true")
        )) {
            assertTrue(expect(invalid, sign(invalid)) is FoxUpdate.Verdict.Reject)
        }
    }

    @Test
    fun tamperingWithTheManifestAfterSigningIsRejected() {
        val body = manifest()
        val signature = sign(body)
        val tampered = body.replace("1.12.0", "9.9.9")
        assertTrue(expect(tampered, signature) is FoxUpdate.Verdict.Reject)
    }

    @Test
    fun theWrongReleaseKeyIsRejected() {
        val body = manifest()
        assertTrue(expect(body, sign(body), key = otherKeyHex) is FoxUpdate.Verdict.Reject)
    }

    @Test
    fun malformedSignaturesAreRejected() {
        val body = manifest()
        assertTrue(expect(body, "!!!not base64!!!") is FoxUpdate.Verdict.Reject)
        assertTrue(expect(body, base64(ByteArray(63))) is FoxUpdate.Verdict.Reject)
        assertTrue(expect(body, "") is FoxUpdate.Verdict.Reject)
    }

    @Test
    fun oversizedManifestsAreRejected() {
        val padded = manifest().replace("\"notes\":\"n\"", "\"notes\":\"" + "x".repeat(70_000) + "\"")
        assertTrue(expect(padded, sign(padded)) is FoxUpdate.Verdict.Reject)
    }

    @Test
    fun schemaViolationsAreRejected() {
        // Bad JSON that still verifies as bytes is impossible to sign meaningfully,
        // so sign each broken body and confirm rejection happens after the signature.
        val noRollback = manifest().replace("\"rollback\":false,", "")
        assertTrue(expect(noRollback, sign(noRollback)) is FoxUpdate.Verdict.Reject)
        val badVersion = manifest(version = "1.12.0;rm")
        assertTrue(expect(badVersion, sign(badVersion)) is FoxUpdate.Verdict.Reject)
        val futureDated = manifest(issuedAt = 1_793_100_000 + 48 * 3600)
        assertTrue(expect(futureDated, sign(futureDated)) is FoxUpdate.Verdict.Reject)
        val negativeDate = manifest(issuedAt = -1)
        assertTrue(expect(negativeDate, sign(negativeDate)) is FoxUpdate.Verdict.Reject)
        val emptyArtifacts = manifest(artifacts = "[]")
        assertTrue(expect(emptyArtifacts, sign(emptyArtifacts)) is FoxUpdate.Verdict.Reject)
        val badSha = manifest(artifacts = """[{"name":"a.apk","sha256":"zz","size":10,"url":"https://github.com/bardia049b-stack/Gozar/releases/download/v/a.apk"}]""")
        assertTrue(expect(badSha, sign(badSha)) is FoxUpdate.Verdict.Reject)
        val badUrl = manifest(artifacts = """[{"name":"a.apk","sha256":"${"b".repeat(64)}","size":10,"url":"https://evil.example/a.apk"}]""")
        assertTrue(expect(badUrl, sign(badUrl)) is FoxUpdate.Verdict.Reject)
        val zeroSize = manifest(artifacts = """[{"name":"a.apk","sha256":"${"b".repeat(64)}","size":0,"url":"https://github.com/bardia049b-stack/Gozar/releases/download/v/a.apk"}]""")
        assertTrue(expect(zeroSize, sign(zeroSize)) is FoxUpdate.Verdict.Reject)
    }

    @Test
    fun signedButIllTypedFieldsAreRejectedWithoutThrowing() {
        val original = manifest()
        for (bad in listOf(
            original.replace("\"rollback\":false", "\"rollback\":\"false\""),
            original.replace("\"issued_at\":1793000000", "\"issued_at\":\"1793000000\""),
            original.replace("\"issued_at\":1793000000", "\"issued_at\":1.5"),
            original.replace("\"size\":1048576", "\"size\":\"1048576\""),
            original.replace("\"version\":\"1.12.0\"", "\"version\":\"1.12.0-beta..1\"")
        )) {
            assertTrue(expect(bad, sign(bad)) is FoxUpdate.Verdict.Reject)
        }
    }

    @Test
    fun signedDesktopTaggedReleaseCanCarryTheSameAndroidVersion() {
        val body = manifest().replace("/download/v1.12.0/", "/download/v1.12.0-desktop/")
        assertTrue(expect(body, sign(body)) is FoxUpdate.Verdict.Accept)
    }

    @Test
    fun signedArtifactMustMatchItsReleaseNameAndTag() {
        val wrongTag = manifest().replace("/download/v1.12.0/", "/download/v1.10.0/")
        val wrongName = manifest().replace("/gozar-1.12.0.apk\"", "/other.apk\"")
        val original = manifest()
        val item = original.substringAfter("[{").substringBefore("}]")
        val duplicate = original.replace("[{$item}]", "[{$item},{$item}]")
        for (bad in listOf(wrongTag, wrongName, duplicate)) {
            assertTrue(expect(bad, sign(bad)) is FoxUpdate.Verdict.Reject)
        }
    }

    @Test
    fun versionsTreatFinalReleasesAndPrereleasesCorrectly() {
        assertTrue(FoxUpdate.isNewer("1.12.0", "1.12.0-rc.1"))
        assertFalse(FoxUpdate.isNewer("1.12.0-rc.1", "1.12.0"))
        assertTrue(FoxUpdate.isNewer("1.12.0-rc.2", "1.12.0-rc.1"))
        assertTrue(FoxUpdate.isNewer("1.12.0-beta", "1.12.0-alpha"))
        assertFalse(FoxUpdate.isNewer("1.12.0-alpha.1", "1.12.0-alpha.2"))
        assertFalse(FoxUpdate.isNewer("1.12.0-beta..1", "1.11.0"))
    }

    @Test
    fun downgradesAndBadRollbacksAreRejected() {
        val downgrade = manifest(version = "1.10.0")
        assertTrue(expect(downgrade, sign(downgrade)) is FoxUpdate.Verdict.Reject)
        val same = manifest(version = "1.11.0")
        assertTrue(expect(same, sign(same)) is FoxUpdate.Verdict.Current)
        // An unauthenticated body can never be reported as current.
        assertTrue(expect(same, sign(manifest())) is FoxUpdate.Verdict.Reject)
        // A rollback that is actually newer is rejected.
        val fakeRollback = manifest(version = "1.12.0", rollback = true)
        assertTrue(expect(fakeRollback, sign(fakeRollback)) is FoxUpdate.Verdict.Reject)
        // A rollback below the channel floor is rejected.
        val tooOld = manifest(version = "1.7.0", channelMin = "1.8.0", rollback = true)
        assertTrue(expect(tooOld, sign(tooOld)) is FoxUpdate.Verdict.Reject)
    }

    @Test
    fun aSignedRollbackToAKnownFloorVersionIsAccepted() {
        val body = manifest(version = "1.10.5", channelMin = "1.8.0", rollback = true)
        val verdict = expect(body, sign(body))
        assertTrue(verdict is FoxUpdate.Verdict.Accept)
        assertTrue((verdict as FoxUpdate.Verdict.Accept).manifest.rollback)
    }

    @Test
    fun artifactBytesAreCheckedAgainstTheSignedManifest() {
        val bytes = ByteArray(64) { (it * 7).toByte() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val artifact = FoxUpdate.Artifact("a.apk", digest, 64, "https://github.com/bardia049b-stack/Gozar/releases/download/v/a.apk")
        assertTrue(FoxUpdate.verifyArtifact(bytes, artifact))
        assertFalse(FoxUpdate.verifyArtifact(bytes.copyOfRange(0, 63), artifact))
        assertFalse(FoxUpdate.verifyArtifact(bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, artifact))
    }

    @Test
    fun streamingDownloadsEnforceTheSignedSizeAndDigest() {
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val output = java.io.ByteArrayOutputStream()
        assertTrue(FoxUpdate.copyVerified(bytes.inputStream(), output, bytes.size.toLong(), digest))
        assertTrue(bytes.contentEquals(output.toByteArray()))
        assertFalse(FoxUpdate.copyVerified(bytes.inputStream(), java.io.ByteArrayOutputStream(), bytes.size - 1L, digest))
        assertFalse(FoxUpdate.copyVerified(bytes.inputStream(), java.io.ByteArrayOutputStream(), bytes.size + 1L, digest))
        assertFalse(FoxUpdate.copyVerified(bytes.inputStream(), java.io.ByteArrayOutputStream(), bytes.size.toLong(), "0".repeat(64)))
        assertFalse(FoxUpdate.copyVerified(bytes.inputStream(), java.io.ByteArrayOutputStream(), bytes.size.toLong(), "invalid"))
    }

    @Test
    fun onlyProjectReleaseDownloadUrlsAreTrusted() {
        assertTrue(FoxUpdate.trusted("https://github.com/bardia049b-stack/Gozar/releases/download/v1.12.0/a.apk"))
        assertTrue(FoxUpdate.trusted("https://github.com/bardia049b-stack/Gozar1/releases/download/v1.12.0/a.apk"))
        assertFalse(FoxUpdate.trusted("https://github.com/someone-else/Gozar/releases/download/v/a.apk"))
        assertFalse(FoxUpdate.trusted("https://raw.githubusercontent.com/bardia049b-stack/Gozar/main/a.apk"))
        assertFalse(FoxUpdate.trusted("http://github.com/bardia049b-stack/Gozar/releases/download/v/a.apk"))
        assertFalse(FoxUpdate.trusted("https://github.com@evil.example/bardia049b-stack/Gozar/releases/download/v/a.apk"))
        assertFalse(FoxUpdate.trusted("https://github.com/bardia049b-stack/Gozar/releases/download/v/../a.apk"))
        assertFalse(FoxUpdate.trusted("https://github.com/bardia049b-stack/Gozar/releases/download/v/%2e%2e"))
        assertFalse(FoxUpdate.trusted("https://github.com/bardia049b-stack/Gozar/releases/download/v/a.apk?target=other"))
        assertFalse(FoxUpdate.trusted("https://github.com/bardia049b-stack/Gozar/releases/download/v/a.apk#other"))
        assertFalse(FoxUpdate.trusted("not a url"))
    }

    // ------------------------------------------------------------------
    // Rollback journal
    // ------------------------------------------------------------------

    private fun journal(): FoxUpdateJournal = FoxUpdateJournal(File(folder.root, "journal/update.json"))

    @Test
    fun aFreshInstallStartsWithoutRollbackAdvice() {
        val journal = journal()
        assertEquals(0, journal.beginLaunch("1.11.0"))
        assertEquals(1, journal.beginLaunch("1.11.0"))
        assertNull(journal.rollbackTarget("1.11.0"))
        journal.markHealthy()
        assertEquals(0, journal.beginLaunch("1.11.0"))
        assertNull(journal.rollbackTarget("1.11.0"))
    }

    @Test
    fun threeUnprovenLaunchesAfterAnUpdateNameThePreviousVersion() {
        val journal = journal()
        val cached = File(folder.root, "previous.apk").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        journal.recordUpdate("1.12.0", "1.11.0", cached.absolutePath)
        assertEquals(1, journal.beginLaunch("1.12.0"))
        assertNull(journal.rollbackTarget("1.12.0"))
        assertEquals(2, journal.beginLaunch("1.12.0"))
        assertNull(journal.rollbackTarget("1.12.0"))
        assertEquals(3, journal.beginLaunch("1.12.0"))
        assertEquals("1.11.0", journal.rollbackTarget("1.12.0"))
        assertEquals(cached.absolutePath, journal.rollbackFile()?.absolutePath)
        // Proving health ends the trial for good.
        journal.markHealthy()
        assertNull(journal.rollbackTarget("1.12.0"))
        assertEquals(0, journal.beginLaunch("1.12.0"))
    }

    @Test
    fun rollbackNeverTargetsSomethingNewerOrUnknown() {
        val journal = journal()
        journal.recordUpdate("1.12.0", "1.13.0", "")
        repeat(FoxUpdateJournal.ROLLBACK_THRESHOLD) { journal.beginLaunch("1.12.0") }
        assertNull(journal.rollbackTarget("1.12.0"))
        // A different running version than the journal knows: no advice.
        journal.recordUpdate("1.12.0", "1.11.0", "")
        repeat(FoxUpdateJournal.ROLLBACK_THRESHOLD) { journal.beginLaunch("1.12.0") }
        assertNull(journal.rollbackTarget("1.11.5"))
    }

    @Test
    fun anUpdateAppliedOutsideTheJournalStillGetsATrialAndARollbackPath() {
        val journal = journal()
        journal.beginLaunch("1.11.0")
        journal.markHealthy()
        // The user updated through the browser; the journal sees a new version.
        assertEquals(0, journal.beginLaunch("1.12.0"))
        assertEquals(1, journal.beginLaunch("1.12.0"))
        assertEquals(2, journal.beginLaunch("1.12.0"))
        assertEquals(3, journal.beginLaunch("1.12.0"))
        assertEquals("1.11.0", journal.rollbackTarget("1.12.0"))
        assertNull(journal.rollbackFile())
    }

    @Test
    fun aCancelledInstallerDoesNotInventAPreviousVersion() {
        val journal = journal()
        journal.recordUpdate("1.12.0", "1.11.0", "/some/old.apk")
        assertEquals(0, journal.beginLaunch("1.11.0"))
        assertEquals("1.11.0", journal.state().installed)
        assertEquals("", journal.state().previous)
        assertNull(journal.rollbackFile())
        assertNull(journal.rollbackTarget("1.11.0"))
    }

    @Test
    fun anExternalUpdateNeverReusesACacheForADifferentVersion() {
        val journal = journal()
        val cached = File(folder.root, "older.apk").also { it.writeBytes(byteArrayOf(1)) }
        journal.recordUpdate("1.11.0", "1.10.0", cached.absolutePath)
        journal.markHealthy()
        journal.beginLaunch("1.12.0")
        repeat(3) { journal.beginLaunch("1.12.0") }
        assertEquals("1.11.0", journal.rollbackTarget("1.12.0"))
        assertNull(journal.rollbackFile())
    }

    @Test
    fun launchFailureCancelsThePendingTrial() {
        val journal = journal()
        journal.recordUpdate("1.12.0", "1.11.0", "")
        journal.cancelPending("1.12.0", "1.11.0")
        assertEquals("1.11.0", journal.state().installed)
        assertTrue(journal.state().healthy)
        assertEquals("", journal.state().previous)
    }

    @Test
    fun aCorruptJournalIsResetInsteadOfThrowing() {
        val file = File(folder.root, "journal/update.json")
        file.parentFile?.mkdirs()
        file.writeText("{{{ not json", Charsets.UTF_8)
        val journal = FoxUpdateJournal(file)
        assertEquals("", journal.state().installed)
        assertEquals(0, journal.beginLaunch("1.12.0"))
        assertNull(journal.rollbackTarget("1.12.0"))
    }

    @Test
    fun clearRemovesTheJournal() {
        val journal = journal()
        journal.recordUpdate("1.12.0", "1.11.0", "")
        journal.clear()
        assertEquals("", journal.state().installed)
    }

    // ------------------------------------------------------------------

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
