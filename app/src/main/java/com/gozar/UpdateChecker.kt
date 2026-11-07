package com.gozar

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Checks the published GitHub release without a tracker, account, or background SDK. */
object UpdateChecker {
    private const val JOB_ID = 7_324
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val OWNER = "bardia049b-stack"
    private val REPOSITORIES = listOf("Gozar", "Gozar1")
    private const val LATEST_RELEASE = "https://api.github.com/repos/bardia049b-stack/Gozar1/releases/latest"
    private const val MANIFEST_ASSET = "update.json"
    private const val SIGNATURE_ASSET = "update.json.sig"
    private const val FoxUpdateManifestLimit = 65_536
    private val VERSION = Regex("^\\d{1,4}(\\.\\d{1,4}){1,3}(-[0-9A-Za-z.]{1,16})?$")
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val callbacks = mutableListOf<(Result) -> Unit>()
    private var checking = false
    private var notifyWhenDone = false

    sealed class Result {
        data class Available(val version: String, val url: String, val sha256: String = "", val size: Long = 0L) : Result()
        data object Current : Result()
        data object Failed : Result()
    }

    /**
     * Decides what a release means for this device. Fail-closed: without both
     * signed-manifest assets, or when the Ed25519 signature or any rule in
     * FoxUpdate rejects, the release is treated as unusable. [fetchAsset]
     * returns the text of a release asset by name, or null when unavailable.
     */
    internal fun verdictFrom(
        releaseJson: JSONObject,
        installed: String,
        keyHex: String = FoxUpdate.KEY_HEX,
        fetchAsset: (String) -> String?
    ): Result {
        val assets = releaseJson.optJSONArray("assets") ?: return Result.Failed
        var hasManifest = false
        var hasSignature = false
        for (index in 0 until assets.length()) {
            when (assets.optJSONObject(index)?.optString("name")) {
                MANIFEST_ASSET -> hasManifest = true
                SIGNATURE_ASSET -> hasSignature = true
            }
        }
        if (!hasManifest || !hasSignature) return Result.Failed
        val manifestJson = fetchAsset(MANIFEST_ASSET) ?: return Result.Failed
        val signature = fetchAsset(SIGNATURE_ASSET) ?: return Result.Failed
        val verdict = FoxUpdate.verify(manifestJson.trim(), signature.trim(), installed, keyHex = keyHex)
        val accepted = verdict as? FoxUpdate.Verdict.Accept
        if (accepted == null) {
            // "Up to date" is only reported when the manifest plainly declares a
            // version that is not newer; everything else stays a hard failure.
            // The declared version is untrusted input — it can at worst hide an
            // update, never install one.
            val declared = try {
                JSONObject(manifestJson).optString("version")
            } catch (_: Exception) {
                ""
            }
            return if (declared.isNotEmpty() && VERSION.matches(declared) && !isNewer(declared, installed)) {
                Result.Current
            } else {
                Result.Failed
            }
        }
        val apk = accepted.manifest.artifactFor(".apk") ?: return Result.Failed
        return Result.Available(accepted.manifest.version, apk.url, apk.sha256, apk.size)
    }

    /** Runs at app startup and then at most once per day while Android permits background work. */
    fun schedule(context: Context) {
        try {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(CHECK_INTERVAL_MS)
                .build()
            scheduler.schedule(job)
        } catch (_: Exception) {
        }
    }

    /** A concurrent caller shares the same network request and receives its result on the main thread. */
    fun check(context: Context, notify: Boolean = true, callback: ((Result) -> Unit)? = null) {
        val appContext = context.applicationContext
        synchronized(lock) {
            callback?.let { callbacks += it }
            notifyWhenDone = notifyWhenDone || notify
            if (checking) return
            checking = true
        }
        thread(name = "gozar-update-check") {
            val result = latest(appContext)
            val shouldNotify: Boolean
            val pending: List<(Result) -> Unit>
            synchronized(lock) {
                checking = false
                shouldNotify = notifyWhenDone
                notifyWhenDone = false
                pending = callbacks.toList()
                callbacks.clear()
            }
            when (result) {
                is Result.Available -> {
                    Store.setUpdate(appContext, result.version, result.url, result.sha256, result.size)
                    if (shouldNotify && Store.notifiedUpdate(appContext) != result.version &&
                        Notify.updateAvailable(appContext, result.version, result.url)
                    ) {
                        Store.setNotifiedUpdate(appContext, result.version)
                    }
                }
                Result.Current -> Store.clearUpdate(appContext)
                Result.Failed -> Unit
            }
            Store.setUpdateCheckedAt(appContext, System.currentTimeMillis())
            main.post { pending.forEach { it.invoke(result) } }
        }
    }

    private fun latest(context: Context): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(LATEST_RELEASE).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "Gozar update checker")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Connection", "close")
            if (connection.responseCode !in 200..299) return Result.Failed
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val installed = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (installed.isBlank()) return Result.Failed
            verdictFrom(json, installed) { name -> assetText(json, name) }
        } catch (_: Exception) {
            Result.Failed
        } finally {
            connection?.disconnect()
        }
    }

    /** Downloads a small text asset of the latest release; null on any problem. */
    private fun assetText(releaseJson: JSONObject, name: String): String? {
        val assets = releaseJson.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") != name) continue
            val url = asset.optString("browser_download_url")
            if (!url.startsWith("https://")) return null
            var connection: HttpURLConnection? = null
            return try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("User-Agent", "Gozar update checker")
                connection.setRequestProperty("Connection", "close")
                if (connection.responseCode !in 200..299) return null
                val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
                val buffer = CharArray(FoxUpdateManifestLimit)
                var total = 0
                while (total < buffer.size) {
                    val read = reader.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                if (reader.read() >= 0) return null // larger than any legitimate manifest
                String(buffer, 0, total)
            } catch (_: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    internal fun trusted(url: String): Boolean {
        val parsed = try {
            java.net.URI(url)
        } catch (_: Exception) {
            return false
        }
        if (!parsed.scheme.equals("https", true) || parsed.userInfo != null || parsed.port != -1) return false
        if (!parsed.host.equals("github.com", true)) return false
        val path = parsed.path ?: return false
        return REPOSITORIES.any { path.startsWith("/$OWNER/$it/releases/") }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = versionParts(remote)
        val localParts = versionParts(local)
        val count = maxOf(remoteParts.size, localParts.size, 3)
        for (index in 0 until count) {
            val r = remoteParts.getOrElse(index) { 0 }
            val l = localParts.getOrElse(index) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private fun versionParts(value: String): List<Int> =
        Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.take(4).toList()
}
