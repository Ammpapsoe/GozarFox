package com.gozar

import android.app.Activity
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * CI-only debug fixture. The build script copies this class and its manifest
 * into app/src/debug, then removes them. It is never part of release APKs.
 * It uses an ephemeral test public key and a local transport, while exercising
 * the actual manifest verifier, installer, journal, FileProvider and Android
 * package installer. It does not test a live GitHub connection or production
 * update key.
 */
class RecoveryFixtureActivity : Activity() {
    private fun record(value: String) {
        File(filesDir, "recovery-fixture-result").writeText(value, Charsets.UTF_8)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("mode") == "probe") {
            record("probe:@RECOVERY_FIXTURE_PROBE@")
            finish()
            return
        }
        val specName = intent.getStringExtra("spec").orEmpty()
        thread(name = "recovery-fixture") {
            try {
                require(specName.matches(Regex("fixture-(invalid-signature|tampered|valid)\\.json")))
                val spec = JSONObject(File(filesDir, specName).readText(Charsets.UTF_8))
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                @Suppress("DEPRECATION")
                val installedCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
                val release = JSONObject().put("assets", JSONArray()
                    .put(JSONObject().put("name", "update.json"))
                    .put(JSONObject().put("name", "update.json.sig")))
                val result = UpdateChecker.verdictFrom(
                    release,
                    packageInfo.versionName.orEmpty(),
                    keyHex = spec.getString("public_key"),
                    abis = listOf("x86_64"),
                    installedCode = installedCode
                ) { asset ->
                    when (asset) {
                        "update.json" -> spec.getString("manifest")
                        "update.json.sig" -> spec.getString("signature")
                        else -> null
                    }
                }
                if (result !is UpdateChecker.Result.Available) {
                    record("manifest-rejected")
                    return@thread
                }
                if (result.recovery == null) {
                    record("recovery-not-targeted")
                    return@thread
                }
                Store.setUpdate(this, result.version, result.url, result.sha256, result.size)
                val payloadName = spec.getString("payload")
                require(payloadName == "recovery-fixture-old.apk" || payloadName == "recovery-fixture-forward.apk")
                val payload = File(filesDir, payloadName)
                val handedOff = UpdateInstaller.downloadAndInstall(applicationContext) { url ->
                    object : HttpURLConnection(url) {
                        override fun connect() = Unit
                        override fun disconnect() = Unit
                        override fun usingProxy() = false
                        override fun getResponseCode() = HTTP_OK
                        override fun getInputStream(): InputStream = FileInputStream(payload)
                    }
                }
                record(if (handedOff) "installer-started" else "download-rejected")
            } catch (error: Exception) {
                record("exception:${error.javaClass.simpleName}:${error.message.orEmpty()}")
            }
        }
    }
}
