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
class FoxSignInProxyTest {

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
        val proxy = FoxSignInProxy(allowed = setOf("localhost"), tlsPort = fake.localPort)
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
        val proxy = FoxSignInProxy(allowed = setOf("localhost"))
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
        val proxy = FoxSignInProxy(allowed = setOf("localhost"), handshakeReadMs = 300)
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
        val proxy = FoxSignInProxy(
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
        val proxy = FoxSignInProxy()
        val hello = byteArrayOf(0x16, 3, 3, 0, 60) + "accounts.firefox.com".toByteArray() + ByteArray(30) { 1 }
        val at = proxy.splitPoint(hello, "accounts.firefox.com")
        val nameStart = hello.toList().indexOf('a'.code.toByte())
        assertTrue("split at $at must fall inside the name starting at $nameStart", at in (nameStart + 1) until hello.size)
        assertTrue(at < nameStart + "accounts.firefox.com".length)
    }

    @Test
    fun stopIsIdempotentAndFreesThePort() {
        val proxy = FoxSignInProxy()
        val port = proxy.start()
        assertTrue(port > 0)
        proxy.stop()
        proxy.stop()
        // The port is ours again: binding it must succeed once the proxy let go.
        ServerSocket(port).use { }
    }
}
