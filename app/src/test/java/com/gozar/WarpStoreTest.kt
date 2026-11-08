package com.gozar

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WarpStoreTest {
    private lateinit var context: Context
    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("store", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.noBackupFilesDir, "warp-device.enc").delete()
        File(context.noBackupFilesDir, "warp-device.enc.bak").delete()
    }
    @Test fun identityIsEncryptedAndRejectsPlaintext() {
        assertNull(WarpStore.load(context))
        val value = "{\"token\":\"private-warp-token\",\"private_key\":\"secret-key\"}"
        assertTrue(WarpStore.save(context, value))
        val target = File(context.noBackupFilesDir, "warp-device.enc")
        val stored = target.readText()
        assertFalse(stored.contains("private-warp-token"))
        assertFalse(stored.contains("secret-key"))
        assertEquals(value, WarpStore.load(context))
        target.writeText(value)
        try { WarpStore.load(context); fail("plaintext identity accepted") }
        catch (_: IOException) { }
    }
    @Test fun udpDefaultsOffAndSettingPersists() {
        assertFalse(Store.warpUdp(context))
        assertTrue(Store.setWarpUdp(context, true))
        assertTrue(Store.warpUdp(context))
        assertTrue(Store.setWarpUdp(context, false))
        assertFalse(Store.warpUdp(context))
    }
}
