package com.gozar

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class FoxReply(
    val code: Int, val body: String, val headers: Map<String, String>,
    val setCookies: List<String> = emptyList()
) {
    fun header(name: String): String? {
        for ((k, v) in headers) if (k.equals(name, true)) return v
        return null
    }
}

object FoxNet {

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"
    const val CLIENT_ID = "5882386c6d801776"
    const val SCOPE = "profile https://identity.mozilla.com/apps/vpn"
    // Use the OAuth account origin for the supported account/session endpoints.
    const val AUTH = "https://oauth.accounts.firefox.com/v1"
    const val ACCOUNT_AUTH = "https://api.accounts.firefox.com/v1"
    const val TOKEN_REFRESH = "https://oauth.accounts.firefox.com/v1/token"
    const val GUARDIAN = "https://vpn.mozilla.org"
    const val SERVERS = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"

    private val jar = FoxJar()
    // The scope is per-thread, not a process-wide proxy configuration.
    private val controlPort = ThreadLocal<Int>()
    private val loginSocksPort = ThreadLocal<Int>()
    private val loginProxyPort = ThreadLocal<Int>()

    fun forget() = jar.clear()

    internal fun controlAllowed(url: String, method: String): Boolean = try {
        val target = URL(url)
        val clean = target.protocol.equals("https", true) && (target.port == -1 || target.port == 443) &&
            target.userInfo == null && target.query == null && target.ref == null
        clean && when {
            target.host.equals("vpn.mozilla.org", true) && target.path == "/api/v1/fpn/token" ->
                method == "GET" || method == "HEAD"
            target.host.equals("oauth.accounts.firefox.com", true) && target.path == "/v1/token" -> method == "POST"
            // Mozilla's public server list: exact host, path and GET only. It carries no credential.
            target.host.equals("firefox.settings.services.mozilla.com", true) &&
                target.path == "/v1/buckets/main/collections/vpn-serverlist/records" -> method == "GET"
            else -> false
        }
    } catch (_: Exception) { false }

    fun <T> throughControlTunnel(port: Int, action: () -> T): T {
        require(port in 1..65535 && controlPort.get() == null && loginSocksPort.get() == null && loginProxyPort.get() == null) { "invalid control tunnel" }
        controlPort.set(port)
        try { return action() } finally { controlPort.remove() }
    }

    fun <T> throughLoginSocks(port: Int, action: () -> T): T {
        require(port in 1..65535 && loginSocksPort.get() == null && controlPort.get() == null && loginProxyPort.get() == null) { "invalid login transport" }
        FoxSocksHttpBridge(port).use { bridge ->
            loginSocksPort.set(port)
            loginProxyPort.set(bridge.port)
            try {
                return action()
            } finally {
                loginProxyPort.remove()
                loginSocksPort.remove()
            }
        }
        throw IllegalStateException("unreachable")
    }

    internal fun loginSocksPort(): Int? = loginSocksPort.get()

    internal fun controlScoped(): Boolean = controlPort.get() != null

    internal fun loginAllowed(url: String, method: String): Boolean = try {
        val target = URL(url)
        val clean = target.protocol.equals("https", true) && (target.port == -1 || target.port == 443) &&
            target.userInfo == null && target.query == null && target.ref == null
        clean && when {
            target.host.equals("oauth.accounts.firefox.com", true) -> when (target.path) {
                "/v1/account/create", "/v1/account/login", "/v1/token", "/v1/oauth/token",
                "/v1/recovery_email/verify_code", "/v1/recovery_email/resend_code", "/v1/session/verify_code" -> method == "POST"
                "/v1/recovery_email/status" -> method == "GET"
                else -> false
            }
            target.host.equals("vpn.mozilla.org", true) -> when (target.path) {
                "/api/v1/fpn/token" -> method == "GET" || method == "HEAD"
                "/api/v1/fpn/activate" -> method == "POST"
                else -> false
            }
            target.host.equals("firefox.settings.services.mozilla.com", true) ->
                target.path == "/v1/buckets/main/collections/vpn-serverlist/records" && method == "GET"
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    internal fun open(target: URL): HttpURLConnection {
        val control = controlPort.get()
        val login = loginProxyPort.get()
        if (control == null && login == null) return target.openConnection() as HttpURLConnection
        val port = control ?: login ?: throw IllegalStateException("missing login proxy")
        return target.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
    }

    internal fun mayRetryRefreshWithTls12(url: String, method: String, status: Int): Boolean =
        status == 406 && method == "POST" && url == TOKEN_REFRESH

    internal fun mayRetryPassWithTls12(url: String, method: String, status: Int): Boolean = try {
        val target = URL(url)
        status == 406 && (method == "GET" || method == "HEAD") &&
            target.protocol.equals("https", true) && target.host.equals("vpn.mozilla.org", true) &&
            (target.port == -1 || target.port == 443) && target.path == "/api/v1/fpn/token" &&
            target.query == null && target.ref == null && target.userInfo == null
    } catch (_: Exception) { false }

    fun call(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Int = 25000
    ): FoxReply {
        val port = controlPort.get()
        val socks = loginSocksPort.get()
        if (port != null && !controlAllowed(url, method)) {
            throw java.io.IOException("Control tunnel destination is not permitted")
        }
        if (socks != null && !loginAllowed(url, method)) {
            throw java.io.IOException("Login destination is not permitted")
        }
        var reply = once(url, method, body, headers, timeout)
        if (mayRetryPassWithTls12(url, method, reply.code) || mayRetryRefreshWithTls12(url, method, reply.code)) {
            // Normal system trust and hostname verification remain enabled.
            reply = once(url, method, body, headers, timeout, tls12 = true)
        }
        var round = 0
        // The challenge solver can use direct sockets and is never invoked by
        // an explicitly tunnel-scoped request.
        while (port == null && socks == null && reply.code == 406 && round < 4) {
            round++
            val origin = try {
                val parsed = URL(url)
                parsed.protocol + "://" + parsed.host
            } catch (_: Exception) {
                "https://accounts.firefox.com"
            }
            val solved = FoxShield.solve(jar, origin)
            if (!solved) break
            reply = once(url, method, body, headers, timeout)
            if (mayRetryPassWithTls12(url, method, reply.code) || mayRetryRefreshWithTls12(url, method, reply.code)) {
                reply = once(url, method, body, headers, timeout, tls12 = true)
            }
        }
        return reply
    }

    private fun once(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        timeout: Int,
        tls12: Boolean = false
    ): FoxReply {
        val target = URL(url)
        val connection = open(target)
        if (tls12) {
            val secure = connection as? HttpsURLConnection ?: throw java.io.IOException("HTTPS required")
            secure.sslSocketFactory = FoxTls12(secure.sslSocketFactory)
        }
        connection.requestMethod = method
        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        // Never forward manually supplied Authorization or cookies to a
        // redirect target; API callers handle non-2xx responses explicitly.
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", UA)
        connection.setRequestProperty("Accept", "*/*")
        if (url.startsWith(AUTH + "/") || url.startsWith(ACCOUNT_AUTH + "/")) {
            connection.setRequestProperty("Accept-Encoding", "identity")
        }
        connection.setRequestProperty("Cache-Control", "max-age=0")
        val cookie = jar.header(url)
        if (cookie.isNotEmpty()) connection.setRequestProperty("Cookie", cookie)
        if (body != null) connection.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in headers) connection.setRequestProperty(k, v)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream: InputStream? = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val found = HashMap<String, String>()
            val cookies = ArrayList<String>()
            for ((k, values) in connection.headerFields) {
                if (k == null || values.isEmpty()) continue
                found[k] = values.joinToString(",")
                if (k.equals("Set-Cookie", true)) cookies.addAll(values)
            }
            val reply = FoxReply(code, text, found, cookies)
            jar.absorb(url, reply)
            return reply
        } finally {
            connection.disconnect()
        }
    }
}
