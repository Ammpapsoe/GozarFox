package com.gozar

import android.app.Application
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DnsTunnelIntegrationTest {
    private val password = "local-test-only".toCharArray()

    private fun tlsContext(): SSLContext {
        val store = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/tls/dns-loopback.p12")!!.use { store.load(it, password) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, SecureRandom()) }
    }

    private fun header(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 16384) {
            val value = input.read()
            if (value < 0) break
            bytes.write(value)
            if (bytes.toString("ISO-8859-1").endsWith("\r\n\r\n")) break
        }
        return bytes.toString("ISO-8859-1")
    }

    @Test
    fun encryptedDnsPassesThroughTheForcedProxyAndTlsRelayDespiteDirectRules() {
        repeat(3) { exerciseEncryptedDns() }
    }

    private fun exerciseEncryptedDns() {
        val context = tlsContext()
        val oldContext = SSLContext.getDefault()
        val oldFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val oldLocation = FoxLink.location
        val oldHops = FoxUpstream.hops
        val oldLan = FoxRoute.bypassLan
        val oldExtra = FoxRoute.extra
        val relayRequests = LinkedBlockingQueue<String>()
        val dnsRequests = LinkedBlockingQueue<ByteArray>()
        val failures = LinkedBlockingQueue<String>()
        val events = LinkedBlockingQueue<String>()
        val loopback = InetAddress.getByName("127.0.0.1")
        val dns = context.serverSocketFactory.createServerSocket(0, 10, loopback) as javax.net.ssl.SSLServerSocket
        val relay = ServerSocket(0, 10, loopback)
        dns.soTimeout = 15000
        relay.soTimeout = 15000
        val dnsWorker = thread(isDaemon = true) {
            try {
                dns.accept().use { client ->
                    events.offer("origin accepted")
                    client.soTimeout = 10000
                    val input = client.getInputStream()
                    val request = header(input)
                    events.offer("origin header=$request")
                    val length = request.lineSequence().firstOrNull { it.startsWith("content-length:", true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: error("missing DNS request length")
                    events.offer("origin content length=$length")
                    val query = ByteArray(length)
                    var received = 0
                    while (received < length) {
                        val count = input.read(query, received, length - received)
                        if (count < 0) error("origin EOF after $received of $length bytes")
                        received += count
                        events.offer("origin body=$received/$length")
                    }
                    dnsRequests.put(query)
                    val answer = query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1 } +
                        byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: ${answer.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(answer)
                        flush()
                    }
                }
            } catch (error: Exception) {
                failures.offer("DNS: $error")
            }
        }
        val relayWorker = thread(isDaemon = true) {
            try {
                while (!relay.isClosed) {
                    val raw = relay.accept()
                    thread(isDaemon = true) connection@{
                        try {
                            val secure = context.socketFactory.createSocket(raw, "localhost", raw.port, true) as SSLSocket
                            secure.use {
                                secure.useClientMode = false
                                secure.soTimeout = 10000
                                secure.sslParameters = secure.sslParameters.apply { applicationProtocols = arrayOf("http/1.1") }
                                secure.startHandshake()
                                val request = header(secure.inputStream)
                                if (request.isBlank()) return@connection
                                relayRequests.put(request)
                                events.offer("relay CONNECT")
                                secure.outputStream.apply {
                                    write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                                    flush()
                                }
                                Socket("127.0.0.1", dns.localPort).use { upstream ->
                                    events.offer("relay reached origin")
                                    upstream.soTimeout = 10000
                                    val upload = thread(isDaemon = true) {
                                        try {
                                            val buffer = ByteArray(8192)
                                            while (true) {
                                                val n = secure.inputStream.read(buffer)
                                                if (n < 0) break
                                                events.offer("relay upload=$n")
                                                upstream.getOutputStream().write(buffer, 0, n)
                                                upstream.getOutputStream().flush()
                                            }
                                            upstream.shutdownOutput()
                                        } catch (_: Exception) {
                                        }
                                    }
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val n = upstream.getInputStream().read(buffer)
                                        if (n < 0) break
                                        events.offer("relay download=$n")
                                        secure.outputStream.write(buffer, 0, n)
                                        secure.outputStream.flush()
                                    }
                                    upload.join(1000)
                                }
                            }
                        } catch (error: javax.net.ssl.SSLException) {
                            events.offer(error.toString())
                        } catch (error: Exception) {
                            if (!relay.isClosed) failures.offer("relay: $error")
                        }
                    }
                }
            } catch (_: java.net.SocketException) {
            } catch (error: Exception) {
                if (!relay.isClosed) failures.offer("accept: $error")
            }
        }
        val proxyPort = ServerSocket(0).use { it.localPort }
        val proxy = FoxProxy(proxyPort, forceTunnel = true, countTraffic = false, tunnelScope = {
            val place = FoxLink.location
            val ticket = FoxTunnel.currentTransport()
            if (place == null || ticket == null) null else FoxLink.TransportScope(place, ticket)
        }, scopeCurrent = { FoxTunnel.transportIsCurrent(it) }) { "local-test-pass" }
        try {
            SSLContext.setDefault(context)
            HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
            FoxRoute.apply(true, "localhost")
            FoxUpstream.hops = emptyList()
            FoxTunnel.startTransport()
            FoxLink.location = FoxLocation("NL", "Test", "Local", "localhost", relay.localPort, false)
            assertTrue(FoxRoute.direct("localhost"))
            assertTrue(proxy.start())
            val query = byteArrayOf(0, 42, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 3, 119, 119, 119,
                7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
            val resolver = FoxResolver(proxyPort, endpoints = listOf("https://localhost:${dns.localPort}/dns-query"))
            val directAddress = resolver.directAddress("www.example.com")
            assertEquals("relay=$relayRequests dns=${dnsRequests.size} failures=$failures events=$events", "1.2.3.4", directAddress)
            val answer = resolver.answer(query)
            assertNotNull(answer)
            assertEquals(0, answer!![3].toInt() and 15)
            val connect = relayRequests.poll(5, TimeUnit.SECONDS)
            assertNotNull(connect)
            assertTrue(connect!!.startsWith("CONNECT localhost:${dns.localPort} HTTP/1.1"))
            assertTrue(connect.contains("Proxy-Authorization: Bearer local-test-pass"))
            val received = dnsRequests.poll(5, TimeUnit.SECONDS)
            assertNotNull(received)
            assertTrue(query.copyOfRange(2, query.size).contentEquals(received!!.copyOfRange(2, received.size)))
            assertTrue(failures.toString(), failures.isEmpty())
        } finally {
            proxy.stop()
            relay.close()
            dns.close()
            relayWorker.join(1000)
            dnsWorker.join(1000)
            FoxLink.location = oldLocation
            FoxUpstream.hops = oldHops
            FoxRoute.bypassLan = oldLan
            FoxRoute.extra = oldExtra
            FoxTunnel.resetTransport()
            SSLContext.setDefault(oldContext)
            HttpsURLConnection.setDefaultSSLSocketFactory(oldFactory)
        }
    }
}
