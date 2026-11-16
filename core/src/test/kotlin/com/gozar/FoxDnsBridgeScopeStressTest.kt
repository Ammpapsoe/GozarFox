package com.gozar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DNS control plane that answers port 53 for the native tunnel must hold
 * the same epoch fence the hand-rolled stack enforced: an answer minted by a
 * stale transport attempt is never published to the wire or to the name
 * table.
 */
class FoxDnsBridgeScopeStressTest {

    private fun query(id: Int) = byteArrayOf(
        0, id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        3, 119, 119, 119, 4, 116, 101, 115, 116, 0, 0, 1, 0, 1
    )

    private fun answer(q: ByteArray, third: Int, fourth: Int): ByteArray {
        val head = q.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
        }
        return head + byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 10, 0, third.toByte(), fourth.toByte())
    }

    @Test
    fun udpDnsDiscardsOldAttemptAndServesNewAcrossFiftyRaces() {
        data class Gate(val entered: CountDownLatch, val release: CountDownLatch)
        val gates = LinkedBlockingQueue<Gate>()
        val settled = LinkedBlockingQueue<Boolean>()
        val epoch = AtomicLong(1)
        val replies = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        val resolver = FoxResolver(0, { _, q ->
            val id = q[1].toInt() and 255
            val iteration = (id - 1) / 2
            val body = if (id % 2 == 1) {
                val gate = gates.poll(5, TimeUnit.SECONDS) ?: error("missing gate")
                gate.entered.countDown()
                if (!gate.release.await(5, TimeUnit.SECONDS)) error("old DNS timed out")
                answer(q, 1, iteration + 1)
            } else {
                answer(q, 2, iteration + 1)
            }
            settled.offer(true)
            body
        }, endpoints = listOf("primary"))
        val bridge = FoxDnsBridge(resolver, 1280) {
            val captured = epoch.get()
            FoxDnsScope(captured) { epoch.get() == captured }
        }
        try {
            repeat(50) { iteration ->
                val gate = Gate(CountDownLatch(1), CountDownLatch(1))
                gates.put(gate)
                bridge.answer(query(iteration * 2 + 1)) { replies.offer((iteration * 2 + 1) to it) }
                assertTrue("old DNS did not begin at $iteration", gate.entered.await(5, TimeUnit.SECONDS))
                epoch.incrementAndGet()
                gate.release.countDown()
                assertNotNull("old DNS did not finish at $iteration", settled.poll(5, TimeUnit.SECONDS))
                // Bounded settle window for the worker's tail (learn/publish).
                Thread.sleep(100)
                assertNull("old response emitted at $iteration", replies.poll())
                assertNull("old response changed DNS names at $iteration", resolver.nameOf(0x0a000100 + iteration + 1))
                bridge.answer(query(iteration * 2 + 2)) { replies.offer((iteration * 2 + 2) to it) }
                val reply = replies.poll(5, TimeUnit.SECONDS)
                assertNotNull("replacement response missing at $iteration", reply)
                assertEquals(iteration * 2 + 2, reply!!.second[1].toInt() and 255)
                assertNotNull("replacement DNS did not finish at $iteration", settled.poll(5, TimeUnit.SECONDS))
                assertEquals("www.test", resolver.nameOf(0x0a000200 + iteration + 1))
                epoch.incrementAndGet()
            }
        } finally {
            bridge.close()
        }
    }
}
