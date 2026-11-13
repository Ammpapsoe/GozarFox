package com.gozar

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proxy is plain JVM, so its race, its bounds and its refusals are
 * checked here without a device. Every wait in these tests is a fraction
 * of a second: the production 30 s budget is injected, not assumed.
 */
class SignInTunnelTest {

    private fun clientTo(port: Int): Socket =
        Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 5_000) }

    private fun connectLine(host: String, port: Int) =
        "CONNECT $host:$port HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

    private fun statusLine(socket: Socket): String {
        socket.soTimeout = 10_000
        val line = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
        return line.orEmpty()
    }

    @Test
    fun tunnelsAnAllowedHostAndReassemblesTheSplitHello() {
        val fake = ServerSocket(0)
        val proxy = SignInTunnel(allowed = setOf("localhost"), tlsPort = fake.localPort)
        val port = proxy.start()
        try {
            val client = clientTo(port)
            client.getOutputStream().write(connectLine("localhost", fake.localPort))
            client.getOutputStream().flush()
            val status = statusLine(client)
            assertTrue("proxy must answer 200: $status", status.contains("200"))

            // A handshake-shaped block carrying the name, as a ClientHello would.
            val hello = byteArrayOf(0x16, 3, 3, 0, 40) + "localhost".toByteArray() + ByteArray(24) { 7 }
            client.getOutputStream().write(hello)
            client.getOutputStream().flush()

            val target = fake.accept()
            target.soTimeout = 10_000
            val seen = ByteArray(hello.size)
            var filled = 0
            while (filled < seen.size) {
                val read = target.getInputStream().read(seen, filled, seen.size - filled)
                if (read < 0) break
                filled += read
            }
            assertEquals("the two fragments must reassemble byte for byte", hello.toList(), seen.toList())

            // And the way back carries the answer.
            target.getOutputStream().write(byteArrayOf(0x17, 3, 3, 0, 1, 42))
            target.getOutputStream().flush()
            val back = ByteArray(6)
            var got = 0
            while (got < back.size) {
                val read = client.getInputStream().read(back, got, back.size - got)
                if (read < 0) break
                got += read
            }
            assertEquals(42, back[5].toInt())
            client.close()
            target.close()
        } finally {
            proxy.stop()
            fake.close()
        }
    }

    @Test
    fun blocksAHostOutsideTheAllowlist() {
        val proxy = SignInTunnel(allowed = setOf("localhost"))
        val port = proxy.start()
        try {
            val client = clientTo(port)
            client.getOutputStream().write(connectLine("evil.example.com", 443))
            client.getOutputStream().flush()
            assertTrue(statusLine(client).contains("403"))
            client.close()
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun boundsTheHandshakeWaitWhenTheClientSendsNothing() {
        val proxy = SignInTunnel(allowed = setOf("localhost"), handshakeReadMs = 300)
        val port = proxy.start()
        try {
            val client = clientTo(port)
            val started = System.currentTimeMillis()
            val eof = client.getInputStream().read()
            val waited = System.currentTimeMillis() - started
            assertEquals("a silent client must be dropped, not kept", -1, eof)
            assertTrue("waited $waited ms, expected the 300 ms bound", waited < 5_000)
            client.close()
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun boundsTheDialWhenNoAddressAnswers() {
        // 10.255.255.1 is TEST-NET-adjacent private space: connects hang on
        // ordinary networks, which is exactly the shape of a blackhole.
        val proxy = SignInTunnel(
            allowed = setOf("10.255.255.1"),
            dialBudgetMs = 400,
            handshakeReadMs = 5_000
        )
        val port = proxy.start()
        try {
            val client = clientTo(port)
            client.getOutputStream().write(connectLine("10.255.255.1", 443))
            client.getOutputStream().flush()
            val started = System.currentTimeMillis()
            val line = statusLine(client)
            val waited = System.currentTimeMillis() - started
            assertFalse("a blackholed dial must not return 200: $line", line.contains("200"))
            assertTrue("waited $waited ms, expected the 400 ms budget", waited < 5_000)
            client.close()
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun splitsInsideTheServerNameWhenOneIsPresent() {
        val proxy = SignInTunnel()
        val hello = byteArrayOf(0x16, 3, 3, 0, 60) + "accounts.firefox.com".toByteArray() + ByteArray(30) { 1 }
        val at = proxy.splitPoint(hello, "accounts.firefox.com")
        val nameStart = hello.toList().indexOf('a'.code.toByte())
        assertTrue("split at $at must fall inside the name starting at $nameStart", at in (nameStart + 1) until hello.size)
        assertTrue(at < nameStart + "accounts.firefox.com".length)
    }

    @Test
    fun stopIsIdempotentAndFreesThePort() {
        val proxy = SignInTunnel()
        val port = proxy.start()
        assertTrue(port > 0)
        proxy.stop()
        proxy.stop()
        // The port is ours again: binding it must succeed once the proxy let go.
        ServerSocket(port).use { }
    }

    @Test
    fun parsesTheAAnswersOfADnsResponse() {
        val proxy = SignInTunnel()
        val query = proxy.buildQuery("accounts.firefox.com")
        val question = query.copyOfRange(12, query.size)
        val message = java.io.ByteArrayOutputStream()
        message.write(byteArrayOf(0x4f, 0x2a, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0))
        message.write(question)
        message.write(byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 1, 0, 0, 4))
        message.write(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val answers = proxy.parseAnswers(message.toByteArray())
        assertEquals(1, answers.size)
        assertEquals("93.184.216.34", answers[0].hostAddress)
    }

    /**
     * The allowlist is domain-shaped, the way the sign-in is: the real page
     * spreads over subdomains and named partner hosts, and an exact-host
     * list starves it into a blank screen. These pin the shape down.
     */
    @Test
    fun allowlistAcceptsSignInDomainsPartnersAndRefusesEverythingElse() {
        val proxy = SignInTunnel() // defaults; never started, permits only

        // Inside the sign-in domains, any depth.
        assertTrue(proxy.permits("accounts.firefox.com"))
        assertTrue(proxy.permits("api.accounts.firefox.com"))
        assertTrue(proxy.permits("oauth.accounts.firefox.com"))
        assertTrue(proxy.permits("firefox.com"))
        assertTrue(proxy.permits("www.mozilla.org"))
        assertTrue(proxy.permits("mozorg.cdn.mozilla.net"))
        // Named partner hosts.
        assertTrue(proxy.permits("accounts.google.com"))
        assertTrue(proxy.permits("appleid.apple.com"))
        assertTrue(proxy.permits("fonts.gstatic.com"))
        // Normalization: case and trailing dot are not differences.
        assertTrue(proxy.permits("ACCOUNTS.Firefox.COM"))
        assertTrue(proxy.permits("accounts.firefox.com."))
        // Refusals: strangers, lookalikes, addresses, empties.
        assertFalse(proxy.permits("evil.example.com"))
        assertFalse(proxy.permits("accounts.firefox.com.evil.example"))
        assertFalse(proxy.permits("notfirefox.com"))
        assertFalse(proxy.permits("1.2.3.4"))
        assertFalse(proxy.permits("::1"))
        assertFalse(proxy.permits(""))
        assertFalse(proxy.permits("."))
    }
}
