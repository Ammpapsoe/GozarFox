package com.gozar

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Downloads the APK named by a previously verified manifest into a temporary
 * file, checks its exact size and SHA-256, then hands it to Android's system
 * installer. The user must confirm; starting the installer does not prove that
 * Android actually installed the package.
 */
object UpdateInstaller {
    private val main = Handler(Looper.getMainLooper())
    private val running = java.util.concurrent.atomic.AtomicBoolean()

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread(name = "gozar-update-download") {
            val handedOff = try { downloadAndInstall(app) } catch (_: Exception) { false }
            running.set(false)
            main.post { Notify.installResult(app, handedOff) }
        }
    }

    private fun downloadAndInstall(context: Context): Boolean {
        val version = Store.updateVersion(context)
        val url = Store.updateUrl(context)
        val sha = Store.updateSha(context)
        val size = Store.updateSize(context)
        val installed = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) { "" }
        if (!FoxUpdate.isNewer(version, installed) || !FoxUpdate.trusted(url)) return false
        if (size <= 0 || size > 512L * 1024 * 1024 || !sha.matches(Regex("[0-9a-f]{64}"))) return false

        val directory = File(context.filesDir, "updates")
        if (!directory.isDirectory && !directory.mkdirs()) return false
        val target = File(directory, "gozar-$version.apk")
        val temporary = File(directory, "gozar-$version.apk.part")
        val connection = URL(url).openConnection() as HttpURLConnection
        var complete = false
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Gozar updater")
            connection.setRequestProperty("Connection", "close")
            if (connection.responseCode !in 200..299) return false
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    if (!FoxUpdate.copyVerified(input, output, size, sha)) return false
                    output.fd.sync()
                }
            }
            // Both files are in app-private storage on the same filesystem.
            if (!temporary.renameTo(target)) return false
            complete = true
        } catch (_: Exception) {
            return false
        } finally {
            connection.disconnect()
            if (!complete) temporary.delete()
        }

        val journal = App.journal(context)
        journal.recordUpdate(version, installed, "")
        val handedOff = launch(context, target)
        if (!handedOff) journal.cancelPending(version, installed)
        return handedOff
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
