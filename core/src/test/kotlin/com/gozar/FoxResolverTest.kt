package com.gozar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxResolverTest {
    private fun query(id: Int = 1, label: String = "one"): ByteArray = byteArrayOf(
        (id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        label.length.toByte()
    ) + label.toByteArray() + byteArrayOf(4, 116, 101, 115, 116, 0, 0, 1, 0, 1)

    private fun answer(query: ByteArray, ttl: Int = 2, rcode: Int = 0): ByteArray {
        val copy = query.copyOf()
        copy[2] = 0x81.toByte()
        copy[3] = (0x80 or rcode).toByte()
        if (rcode != 0) return copy
        copy[7] = 1
        return copy + byteArrayOf(
            0xc0.toByte(), 0x0c, 0, 1, 0, 1,
            ((ttl ushr 24) and 255).toByte(), ((ttl ushr 16) and 255).toByte(),
            ((ttl ushr 8) and 255).toByte(), (ttl and 255).toByte(),
            0, 4, 1, 2, 3, 4
        )
    }

    @Test
    fun recoversAfterMoreThanFortyFailuresAndDoesNotCacheServfail() {
        val calls = AtomicInteger()
        var offline = true
        val resolver = FoxResolver(0, { _, query ->
            calls.incrementAndGet()
            if (offline) answer(query, rcode = 2) else answer(query)
        })
        repeat(41) { assertEquals(2, resolver.answer(query(it))!![3].toInt() and 15) }
        offline = false
        val recovered = resolver.answer(query(100))!!
        assertEquals(0, recovered[3].toInt() and 15)
        assertEquals(41 * DnsPolicy.endpoints(DnsPolicy.AUTO, "", false).size + 1, calls.get())
    }

    @Test
    fun respectsDnsTtlAndRetagsCachedTransactionIds() {
        val calls = AtomicInteger()
        var clock = 1000L
        val resolver = FoxResolver(0, { _, query -> calls.incrementAndGet(); answer(query) }, { clock })
        val first = resolver.answer(query(9))!!
        // The 2-second record TTL is lifted to the 60-second cache floor.
        clock += 59_000
        val cached = resolver.answer(query(10))!!
        assertEquals(1, calls.get())
        assertEquals(10, cached[1].toInt() and 255)
        val ttlOffset = query().size + 6
        assertEquals(2, first[ttlOffset + 3].toInt() and 255)
        assertEquals(1, cached[ttlOffset + 3].toInt() and 255)
        assertArrayEquals(first.copyOfRange(2, ttlOffset), cached.copyOfRange(2, ttlOffset))
        clock += 1_001
        resolver.answer(query(11))
        assertEquals(2, calls.get())
    }

    @Test
    fun longTtlsAreClampedToTheCacheCeiling() {
        val calls = AtomicInteger()
        var clock = 0L
        val resolver = FoxResolver(0, { _, query -> calls.incrementAndGet(); answer(query, ttl = 86400) }, { clock })
        resolver.answer(query(3))!!
        assertEquals(1, calls.get())
        clock += 299_000
        resolver.answer(query(4))!!
        assertEquals("a day-long TTL must be clamped to five minutes", 1, calls.get())
        clock += 2_000
        resolver.answer(query(5))!!
        assertEquals(2, calls.get())
    }

    @Test
    fun nxdomainIsCachedForThirtySeconds() {
        val calls = AtomicInteger()
        var clock = 0L
        val resolver = FoxResolver(0, { _, query -> calls.incrementAndGet(); answer(query, rcode = 3) }, { clock })
        val first = resolver.answer(query(6))!!
        assertEquals(3, first[3].toInt() and 15)
        assertEquals(1, calls.get())
        clock += 29_000
        val cached = resolver.answer(query(7))!!
        assertEquals(1, calls.get())
        assertEquals(3, cached[3].toInt() and 15)
        assertEquals(7, cached[1].toInt() and 255)
        clock += 1_001
        resolver.answer(query(8))!!
        assertEquals(2, calls.get())
    }

    @Test
    fun parallelIdenticalQuestionsShareOneLookup() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resolver = FoxResolver(0, { _, query ->
            calls.incrementAndGet()
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            answer(query)
        })
        val results = arrayOfNulls<ByteArray>(2)
        val done = CountDownLatch(2)
        for (index in 0..1) {
            thread(isDaemon = true) {
                try {
                    results[index] = resolver.answer(query(7))
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        // Give the second thread time to queue on the in-flight lookup.
        Thread.sleep(150)
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
        for (result in results) assertTrue(result != null && result.size > 12)
    }

    @Test
    fun slowEndpointDoesNotDelayTheAnswerWhenAnotherWins() {
        val endpoints = listOf("https://one.example/dns-query", "https://two.example/dns-query")
        val resolver = FoxResolver(0, { url, query ->
            if (url == endpoints[0]) {
                Thread.sleep(2000)
                null
            } else {
                answer(query)
            }
        }, endpoints = endpoints, raceHeadStartMs = 50)
        val started = System.nanoTime()
        val body = resolver.answer(query(21))!!
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertEquals(0, body[3].toInt() and 15)
        assertTrue("the healthy endpoint should win the race, took ${elapsed}ms", elapsed < 1900)
    }

    @Test
    fun malformedQuestionsAreRejectedAndLargeUdpRepliesAreTruncated() {
        val resolver = FoxResolver(0, { _, query -> answer(query) })
        val malformed = query().copyOf(query().size - 1)
        assertEquals(null, resolver.answer(malformed))
        val request = query()
        val large = answer(request) + ByteArray(1800)
        val short = resolver.truncate(request, large, 1372)
        assertEquals(request.size, short.size)
        assertTrue(short[2].toInt() and 0x82 == 0x82)
        assertEquals(0, short[7].toInt() and 255)
    }

    @Test
    fun independentDnsQuestionsRunInParallel() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val resolver = FoxResolver(0, { _, query ->
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            answer(query)
        })
        for (name in listOf("one", "two")) thread(isDaemon = true) {
            try { resolver.answer(query(label = name)) } finally { done.countDown() }
        }
        try { assertTrue(entered.await(2, TimeUnit.SECONDS)) } finally { release.countDown() }
        assertTrue(done.await(3, TimeUnit.SECONDS))
    }
}
