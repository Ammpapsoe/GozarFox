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
        return copy + byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, ttl.toByte(), 0, 4, 1, 2, 3, 4)
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
        clock += 1000
        val cached = resolver.answer(query(10))!!
        assertEquals(1, calls.get())
        assertEquals(10, cached[1].toInt() and 255)
        val ttlOffset = query().size + 6
        assertEquals(2, first[ttlOffset + 3].toInt() and 255)
        assertEquals(1, cached[ttlOffset + 3].toInt() and 255)
        assertArrayEquals(first.copyOfRange(2, ttlOffset), cached.copyOfRange(2, ttlOffset))
        clock += 1001
        resolver.answer(query(11))
        assertEquals(2, calls.get())
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
