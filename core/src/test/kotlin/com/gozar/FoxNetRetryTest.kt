package com.gozar

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class FoxNetRetryTest {
    @Test
    fun transientResponsesRetryOnlyTheHttpRequest() {
        val calls = AtomicInteger()
        val failures = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/retry") { exchange ->
            val code = if (calls.incrementAndGet() == 1) 503 else 200
            val body = if (code == 200) "ok" else "later"
            exchange.sendResponseHeaders(code, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/always") { exchange ->
            failures.incrementAndGet()
            val body = "later"
            exchange.sendResponseHeaders(503, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/retry"
            val reply = FoxNet.callWithRetry(url, timeout = 2_000, attempts = 3)
            assertEquals(200, reply.code)
            assertEquals("ok", reply.body)
            assertEquals(2, calls.get())
            val exhausted = FoxNet.callWithRetry(url.replace("/retry", "/always"), timeout = 2_000, attempts = 2)
            assertEquals(503, exhausted.code)
            assertEquals(2, failures.get())
        } finally {
            server.stop(0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun postRequestsAreNotRetriedAutomatically() {
        FoxNet.callWithRetry("https://example.invalid", "POST", "{}", attempts = 2)
    }
}
