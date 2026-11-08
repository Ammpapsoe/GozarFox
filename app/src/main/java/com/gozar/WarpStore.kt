package com.gozar

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Encrypted, no-backup WARP device credentials; never fall back to plaintext. */
internal object WarpStore {
    private fun file(context: Context) = AtomicFile(File(context.applicationContext.noBackupFilesDir, "warp-device.enc"))

    @Synchronized fun load(context: Context): String? {
        val target = file(context)
        if (!target.baseFile.exists()) return null
        if (target.baseFile.length() > 1_048_576L) throw IOException("invalid encrypted WARP identity")
        val value = try { target.openRead().use { it.readBytes().toString(Charsets.UTF_8) } }
        catch (_: Exception) { throw IOException("could not read WARP identity") }
        if (value.length > 1_048_576 || !SecretBox.isSealed(value)) throw IOException("invalid encrypted WARP identity")
        val plain = SecretBox.open(context, value)
        if (plain.isEmpty()) throw IOException("could not unlock WARP identity")
        return plain
    }

    @Synchronized fun save(context: Context, value: String): Boolean {
        if (value.isEmpty() || value.length > 1_048_576) return false
        val sealed = try { SecretBox.seal(context, value) } catch (_: Exception) { "" }
        if (sealed.isEmpty() || !SecretBox.isSealed(sealed)) return false
        val target = file(context)
        var output: java.io.FileOutputStream? = null
        return try {
            output = target.startWrite()
            output.write(sealed.toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
            true
        } catch (_: Exception) {
            if (output != null) target.failWrite(output)
            false
        }
    }
}
