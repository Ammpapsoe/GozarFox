package com.gozar

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.SocketException
import java.net.URI
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Synthetic local TLS proxy. The test-only key is never part of an APK. */
class FoxControlTest {
    private lateinit var originalFactory: javax.net.ssl.SSLSocketFactory
    private lateinit var originalSelector: ProxySelector

    @Before fun setup() {
        originalFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        originalSelector = ProxySelector.getDefault()
        HttpsURLConnection.setDefaultSSLSocketFactory(clientContext().socketFactory)
        FoxNet.forget()
    }

    @After fun cleanup() {
        HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory)
        ProxySelector.setDefault(originalSelector)
        FoxNet.forget()
    }

    @Test fun allowlistRequiresExactHostPathPortAndMethod() {
        assertTrue(FoxNet.controlAllowed("https://vpn.mozilla.org/api/v1/fpn/token", "GET"))
        assertTrue(FoxNet.controlAllowed("https://oauth.accounts.firefox.com/v1/token", "POST"))
        val rejected = listOf(
            "http://vpn.mozilla.org/api/v1/fpn/token" to "GET",
            "https://vpn.mozilla.org.evil.invalid/api/v1/fpn/token" to "GET",
            "https://vpn.mozilla.org:444/api/v1/fpn/token" to "GET",
            "https://user@vpn.mozilla.org/api/v1/fpn/token" to "GET",
            "https://vpn.mozilla.org/api/v1/fpn/token?next=evil" to "GET",
            "https://vpn.mozilla.org/api/v1/fpn/token#evil" to "GET",
            "https://vpn.mozilla.org/api/v1/fpn/activate" to "POST",
            "https://vpn.mozilla.org/api/v1/fpn/token" to "POST",
            "https://oauth.accounts.firefox.com/v1/token" to "GET",
            "https://oauth.accounts.firefox.com/v1/account/create" to "POST"
        )
        for ((url, method) in rejected) assertFalse("accepted $url", FoxNet.controlAllowed(url, method))
        assertTrue(FoxNet.mayRetryRefreshWithTls12(FoxNet.TOKEN_REFRESH, "POST", 406))
        assertFalse(FoxNet.mayRetryRefreshWithTls12(FoxNet.TOKEN_REFRESH + "?evil", "POST", 406))
        assertFalse(FoxNet.mayRetryRefreshWithTls12(FoxNet.TOKEN_REFRESH, "POST", 451))
    }

    @Test fun simulatedDirect451CanRenewThroughExistingTunnel() {
        LocalProxy { _, _, _ -> Answer(451, "{}") }.use { direct ->
            LocalProxy { head, _, _ ->
                when {
                    head.startsWith("GET /api/v1/fpn/token ") -> Answer(200, "{\"token\":\"fresh-pass\"}")
                    head.startsWith("POST /v1/token ") -> Answer(200, "{\"access_token\":\"fresh-access\",\"refresh_token\":\"fresh-refresh\",\"expires_in\":3600}")
                    else -> Answer(404, "{}")
                }
            }.use { tunnel ->
                useDefaultProxy(direct.port)
                val blocked = runCatching { FoxApi.existingPass("synthetic") }.exceptionOrNull() as? FoxError
                assertEquals(451, blocked?.status)
                val result = FoxNet.throughControlTunnel(tunnel.port) {
                    FoxApi.refresh("synthetic-refresh") to FoxApi.existingPass("fresh-access")
                }
                assertEquals("fresh-refresh", result.first.refresh)
                assertEquals("fresh-pass", result.second.token)
                assertEquals(1, direct.requests.size)
                assertEquals(2, tunnel.requests.size)
                assertTrue(direct.problems.isEmpty())
                assertTrue(tunnel.problems.isEmpty())
            }
        }
    }

    @Test fun tunnel451DoesNotFallBackAnd403DoesNotActivate() {
        LocalProxy { _, _, _ -> Answer(451, "{}") }.use { outside ->
            useDefaultProxy(outside.port)
            for (status in listOf(451, 403)) {
                LocalProxy { _, _, _ -> Answer(status, "{}") }.use { tunnel ->
                    val failure = runCatching {
                        FoxNet.throughControlTunnel(tunnel.port) { FoxApi.existingPass("synthetic") }
                    }.exceptionOrNull() as? FoxError
                    assertEquals(status, failure?.status)
                    assertEquals(1, tunnel.requests.size)
                    assertTrue(tunnel.requests.single().startsWith("GET /api/v1/fpn/token "))
                }
            }
            assertEquals(0, outside.requests.size)
        }
    }

    @Test fun scoped406RetriesOnlyWithVerifiedTls12() {
        val protocols = Collections.synchronizedList(mutableListOf<String>())
        LocalProxy { _, _, version ->
            protocols.add(version)
            if (version == "TLSv1.3") Answer(406, "{}") else Answer(200, "{\"token\":\"fresh\"}")
        }.use { proxy ->
            val result = FoxNet.throughControlTunnel(proxy.port) { FoxApi.existingPass("synthetic") }
            assertEquals("fresh", result.token)
            assertEquals(listOf("TLSv1.3", "TLSv1.2"), protocols.toList())
        }
    }

    @Test fun untrustedCertificateIsRejectedBeforeBearerIsSent() {
        HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory)
        LocalProxy { _, _, _ -> Answer(200, "{\"token\":\"unexpected\"}") }.use { proxy ->
            assertTrue(runCatching {
                FoxNet.throughControlTunnel(proxy.port) { FoxApi.existingPass("synthetic") }
            }.isFailure)
            assertEquals(0, proxy.requests.size)
        }
    }

    @Test fun syntheticSignupUsesOAuthAccountOrigin() {
        LocalProxy { head, body, _ ->
            assertTrue(head.startsWith("POST /v1/account/create "))
            assertTrue(body.contains("\"email\":\"person@example.invalid\""))
            Answer(200, "{\"uid\":\"synthetic\",\"sessionToken\":\"synthetic\",\"verified\":false}")
        }.use { proxy ->
            useDefaultProxy(proxy.port)
            assertTrue(FoxApi.register("person@example.invalid", "synthetic-password").needsCode)
            assertEquals(1, proxy.connects.size)
            assertTrue(proxy.connects.single().startsWith("CONNECT oauth.accounts.firefox.com:443 "))
            assertTrue(proxy.problems.isEmpty())
        }
    }

    private fun useDefaultProxy(port: Int) {
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            override fun connectFailed(uri: URI?, address: SocketAddress?, error: java.io.IOException?) { }
        })
    }

    private data class Answer(val code: Int, val body: String)
    private class LocalProxy(val answer: (String, String, String) -> Answer) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val connects = Collections.synchronizedList(mutableListOf<String>())
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val problems = ConcurrentLinkedQueue<Throwable>()
        private val worker = thread(isDaemon = true, name = "control-test-proxy") {
            while (!server.isClosed) {
                val raw = try { server.accept() } catch (_: SocketException) { break }
                try {
                    raw.soTimeout = 4000
                    connects.add(readHead(raw.getInputStream()))
                    raw.getOutputStream().apply {
                        write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        flush()
                    }
                    val socket = serverContext().socketFactory.createSocket(raw, "127.0.0.1", raw.port, true) as SSLSocket
                    socket.use {
                        socket.useClientMode = false
                        socket.startHandshake()
                        val head = readHead(socket.inputStream)
                        val size = head.split("\r\n").firstOrNull { it.startsWith("Content-Length:", true) }
                            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                        val body = ByteArray(size)
                        var offset = 0
                        while (offset < size) {
                            val n = socket.inputStream.read(body, offset, size - offset)
                            if (n < 0) throw EOFException()
                            offset += n
                        }
                        requests.add(head)
                        val result = answer(head, String(body, Charsets.UTF_8), socket.session.protocol)
                        val data = result.body.toByteArray(Charsets.UTF_8)
                        val header = "HTTP/1.1 ${result.code} Test\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${data.size}\r\nConnection: close\r\n\r\n"
                        socket.outputStream.write(header.toByteArray(Charsets.US_ASCII))
                        socket.outputStream.write(data)
                        socket.outputStream.flush()
                    }
                } catch (_: SSLException) {
                    // Expected for the untrusted-certificate test.
                } catch (e: Throwable) { problems.add(e) }
                finally { runCatching { raw.close() } }
            }
        }
        override fun close() { server.close(); worker.join(5000) }
    }

    companion object {
        private fun store(): KeyStore = KeyStore.getInstance("PKCS12").apply {
            FoxControlTest::class.java.getResourceAsStream("/tls/control-test-only.p12")!!.use {
                load(it, "local-test-only".toCharArray())
            }
        }
        private fun clientContext(): SSLContext {
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(store())
            return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, SecureRandom()) }
        }
        private fun serverContext(): SSLContext {
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            managers.init(store(), "local-test-only".toCharArray())
            return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
        }
        private fun readHead(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (out.size() < 16384) {
                val next = input.read()
                if (next < 0) throw EOFException()
                out.write(next)
                val bytes = out.toByteArray()
                if (bytes.size >= 4 && bytes.takeLast(4) == listOf(13.toByte(), 10.toByte(), 13.toByte(), 10.toByte())) {
                    return String(bytes, Charsets.US_ASCII)
                }
            }
            error("header too large")
        }
    }
}
