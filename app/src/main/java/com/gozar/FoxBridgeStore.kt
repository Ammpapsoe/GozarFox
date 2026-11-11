package com.gozar

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal object FoxBridgeStore {
    private const val ROOT = "https://raw.githubusercontent.com/Ammpapsoe/GozarFox/bridge-feed/packaging/bridges.json"

    @Volatile internal var manual: String = ""
    @Volatile internal var fetcher: (String, Int) -> ByteArray? = { url, limit -> download(url, limit) }
    @Volatile internal var verifier: (String, String) -> List<FoxBridge.Candidate>? = { json, signature -> FoxBridge.verify(json, signature) }

    fun manualCandidate(): FoxBridge.Candidate? = FoxBridge.manual(manual)

    fun automatic(context: Context): List<FoxBridge.Candidate> {
        context.applicationContext
        val json = try {
            fetcher(ROOT, FoxBridge.MAX_FEED_BYTES)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val signature = try {
            fetcher(ROOT + ".sig", 512)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val manifest = try {
            String(json, Charsets.UTF_8)
        } catch (_: Exception) {
            return emptyList()
        }
        val signatureText = try {
            String(signature, Charsets.US_ASCII)
        } catch (_: Exception) {
            return emptyList()
        }
        return FoxBridge.rotation(verifier(manifest, signatureText) ?: return emptyList())
    }

    fun clear() {
        manual = ""
    }

    private fun download(url: String, limit: Int): ByteArray? {
        val target = URL(url)
        if (target.protocol != "https" || target.host != "raw.githubusercontent.com" || target.port != -1 || target.userInfo != null) return null
        val connection = (target.openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("User-Agent", "Gozar bridge verifier")
            connection.setRequestProperty("Accept", "text/plain")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Connection", "close")
            if (connection.responseCode != 200) return null
            val length = connection.contentLengthLong
            if (length > limit) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(limit, 16_384))
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size() + count > limit) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
