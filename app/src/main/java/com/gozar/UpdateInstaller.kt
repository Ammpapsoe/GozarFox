package com.gozar

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Downloads the update named by the signed manifest, proves the bytes match
 * the signed sha256 and size, caches the running APK so the update can be
 * reversed, records the trial in the journal, and hands the file to the
 * system installer. Nothing is installed silently — the user always confirms
 * the system prompt.
 */
object UpdateInstaller {

    private val main = Handler(Looper.getMainLooper())
    private val running = java.util.concurrent.atomic.AtomicBoolean()

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread(name = "gozar-update-download") {
            val ok = try {
                downloadAndInstall(app)
            } catch (_: Exception) {
                false
            }
            running.set(false)
            main.post { Notify.installResult(app, ok) }
        }
    }

    /** Reinstalls the cached previous build when the journal advises a rollback. */
    fun rollback(context: Context): Boolean {
        val file = App.journal(context).rollbackFile() ?: return false
        return launch(context.applicationContext, file)
    }

    private fun downloadAndInstall(context: Context): Boolean {
        val version = Store.updateVersion(context)
        val url = Store.updateUrl(context)
        val sha = Store.updateSha(context)
        val size = Store.updateSize(context)
        if (version.isBlank() || url.isBlank() || sha.isBlank() || size <= 0) return false
        if (!FoxUpdate.trusted(url)) return false
        val target = File(context.filesDir, "updates/gozar-$version.apk")
        target.parentFile?.mkdirs()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Gozar updater")
            connection.setRequestProperty("Connection", "close")
            if (connection.responseCode !in 200..299) return false
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        // The signed manifest bounds the download exactly.
                        if (total > size) return false
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total != size) return false
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha, ignoreCase = true)) {
                target.delete()
                return false
            }
        } catch (_: Exception) {
            target.delete()
            return false
        } finally {
            connection.disconnect()
        }
        val journal = App.journal(context)
        try {
            val installedVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName.orEmpty()
            val source = File(context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir)
            val cached = File(context.filesDir, "updates/rollback-$installedVersion.apk")
            source.copyTo(cached, overwrite = true)
            journal.recordUpdate(version, installedVersion, cached.absolutePath)
        } catch (_: Exception) {
            journal.recordUpdate(version, "", "")
        }
        return launch(context, target)
    }

    private fun launch(context: Context, apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
