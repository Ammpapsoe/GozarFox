package com.gozar

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class FoxCookieTest {
    @Test fun tls12RetryIsLimitedToMozillaPassReads() {
        val pass = "https://vpn.mozilla.org/api/v1/fpn/token"
        assertTrue(FoxNet.mayRetryPassWithTls12(pass, "GET", 406))
        assertTrue(FoxNet.mayRetryPassWithTls12(pass, "HEAD", 406))
        assertFalse(FoxNet.mayRetryPassWithTls12(pass, "GET", 403))
        assertFalse(FoxNet.mayRetryPassWithTls12(pass, "POST", 406))
        assertFalse(FoxNet.mayRetryPassWithTls12("http://vpn.mozilla.org/api/v1/fpn/token", "GET", 406))
        assertFalse(FoxNet.mayRetryPassWithTls12("https://vpn.mozilla.org:8443/api/v1/fpn/token", "GET", 406))
        assertFalse(FoxNet.mayRetryPassWithTls12("https://vpn.mozilla.org.evil.test/api/v1/fpn/token", "GET", 406))
        assertFalse(FoxNet.mayRetryPassWithTls12("https://evil.test@vpn.mozilla.org/api/v1/fpn/token", "GET", 406))
        assertFalse(FoxNet.mayRetryPassWithTls12(pass + "?redirect=evil", "GET", 406))
        assertFalse(FoxNet.mayRetryPassWithTls12("https://vpn.mozilla.org/api/v1/fpn/activate", "GET", 406))
    }

    @Test fun tls12FactoryLimitsProtocolsWithoutChangingTrust() {
        val delegate = javax.net.ssl.SSLContext.getDefault().socketFactory
        val socket = FoxTls12(delegate).createSocket() as javax.net.ssl.SSLSocket
        try {
            assertArrayEquals(arrayOf("TLSv1.2"), socket.enabledProtocols)
            assertArrayEquals(delegate.defaultCipherSuites, FoxTls12(delegate).defaultCipherSuites)
        } finally { socket.close() }
    }

    @Test fun cookiesAreScopedToTheirOriginAndSecureTransport() {
        val jar = FoxJar()
        val login = "https://oauth.accounts.firefox.com/v1/account/login"
        jar.absorb(login, FoxReply(200, "", emptyMap(), listOf(
            "session=example; Secure; HttpOnly; Path=/v1",
            "second=value; Secure; Path=/v1"
        )))
        val same = jar.header("https://oauth.accounts.firefox.com/v1/oauth/token")
        assertTrue(same.contains("session=example"))
        assertTrue(same.contains("second=value"))
        assertFalse(jar.header("https://vpn.mozilla.org/api/v1/fpn/token").contains("session=example"))
        assertFalse(jar.header("https://firefox.settings.services.mozilla.com/v1/").contains("session=example"))
        assertFalse(jar.header("http://oauth.accounts.firefox.com/v1/").contains("session=example"))
        assertFalse(jar.header("https://oauth.accounts.firefox.com/not-v1").contains("session=example"))
        jar.clear()
        assertTrue(jar.header(login).isEmpty())
    }

    @Test fun apiNeverForwardsAuthorizationToRedirectTargets() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val origin = ServerSocket(0, 1, loopback)
        val destination = ServerSocket(0, 1, loopback).apply { soTimeout = 1500 }
        val worker = thread(isDaemon = true) {
            try {
                origin.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val response = "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${destination.localPort}/capture\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                }
            } catch (_: Exception) { }
        }
        try {
            FoxNet.forget()
            val reply = FoxNet.call("http://127.0.0.1:${origin.localPort}/login", "GET", null,
                mapOf("Authorization" to "Bearer synthetic-test-only"), 3000)
            assertEquals(302, reply.code)
            try {
                destination.accept().close()
                fail("API followed an untrusted redirect")
            } catch (_: SocketTimeoutException) { }
        } finally {
            origin.close(); destination.close(); worker.join(3000); FoxNet.forget()
        }
    }
}
