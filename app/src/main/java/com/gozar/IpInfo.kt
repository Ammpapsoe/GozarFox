package com.gozar

import android.content.Context
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.util.Locale

data class IpInfo(
    val ip: String,
    val city: String,
    val country: String,
    val code: String,
    val provider: String
)

object Ip {
    fun fetch(): IpInfo? {
        return fetchFrom("https://ipapi.co/json/", "ip", "country_name", "country_code") { it.optString("org") }
            ?: fetchFrom("https://ipwho.is/", "ip", "country", "country_code") {
                val connection = it.optJSONObject("connection")
                connection?.optString("isp").orEmpty().ifBlank { connection?.optString("org").orEmpty() }
            }
            ?: fetchIpInfo()
            ?: fetchCloudflareTrace()
            ?: fetchIpOnly()
    }

    fun fetchOrigin(context: Context): IpInfo? {
        val networkInfo = fetch()
        val carrierCode = carrierCountry(context) ?: return networkInfo
        if (networkInfo?.code?.equals(carrierCode, true) == true) return networkInfo
        return (networkInfo ?: IpInfo("", "", "", "", "")).copy(
            city = "",
            country = countryName(carrierCode),
            code = carrierCode
        )
    }

    private fun fetchFrom(
        url: String,
        ipKey: String,
        countryKey: String,
        codeKey: String,
        providerOf: (JSONObject) -> String
    ): IpInfo? {
        return try {
            val json = JSONObject(request(url) ?: return null)
            val ip = json.optString(ipKey)
            if (ip.isBlank()) return null
            IpInfo(
                ip,
                json.optString("city"),
                json.optString(countryKey),
                json.optString(codeKey).uppercase(),
                providerOf(json)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchIpInfo(): IpInfo? {
        return try {
            val json = JSONObject(request("https://ipinfo.io/json") ?: return null)
            val ip = json.optString("ip")
            val code = json.optString("country").trim().uppercase()
            if (ip.isBlank()) return null
            IpInfo(
                ip = ip,
                city = json.optString("city"),
                country = if (code.length == 2) countryName(code) else "",
                code = code.takeIf { it.length == 2 }.orEmpty(),
                provider = json.optString("org")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchCloudflareTrace(): IpInfo? {
        return try {
            val fields = request("https://www.cloudflare.com/cdn-cgi/trace")
                ?.lineSequence()
                ?.mapNotNull { line ->
                    val split = line.indexOf('=')
                    if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
                }
                ?.toMap()
                ?: return null
            val ip = fields["ip"].orEmpty()
            val code = fields["loc"].orEmpty().trim().uppercase()
            if (ip.isBlank()) return null
            IpInfo(ip, "", if (code.length == 2) countryName(code) else "", code.take(2), "")
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchIpOnly(): IpInfo? {
        return try {
            val json = JSONObject(request("https://api.ipify.org?format=json") ?: return null)
            val ip = json.optString("ip")
            if (ip.isBlank()) null else IpInfo(ip, "", "", "", "")
        } catch (_: Exception) {
            null
        }
    }

    private fun request(url: String): String? = try {
        // Browsers/HttpURLConnection followed redirects here; keep it.
        val client = okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(4_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(4_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Gozar")
            .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")
            .header("Connection", "close")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code !in 200..299) null else response.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    fun carrierCountry(context: Context): String? {
        return try {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            sequenceOf(telephony?.networkCountryIso, telephony?.simCountryIso)
                .map { it.orEmpty().trim().uppercase() }
                .firstOrNull { it.length == 2 }
        } catch (_: Exception) {
            null
        }
    }

    private fun countryName(code: String): String =
        Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }
}
