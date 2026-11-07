package com.gozar

import android.app.Application
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpdateInstallerFileTest {
    @get:Rule val folder = TemporaryFolder()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test
    fun onlyVerifiedBytesReplaceTheExistingApk() {
        val target = File(folder.root, "update.apk")
        val part = File(folder.root, "update.apk.part")
        val old = "existing".toByteArray()
        val good = "verified update".toByteArray()
        target.writeBytes(old)
        assertFalse(UpdateInstaller.saveVerified("bad".byteInputStream(), part, target, good.size.toLong(), sha(good)))
        assertFalse(part.exists())
        assertArrayEquals(old, target.readBytes())
        assertTrue(UpdateInstaller.saveVerified(good.inputStream(), part, target, good.size.toLong(), sha(good)))
        assertFalse(part.exists())
        assertArrayEquals(good, target.readBytes())
    }

    @Test
    fun interruptedReadRemovesThePartialFile() {
        val target = File(folder.root, "update.apk")
        val part = File(folder.root, "update.apk.part")
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("interrupted")
        }
        assertFalse(UpdateInstaller.saveVerified(broken, part, target, 3, sha("abc".toByteArray())))
        assertFalse(part.exists())
        assertFalse(target.exists())
    }
}
