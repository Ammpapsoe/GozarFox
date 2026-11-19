package com.gozar

import android.app.Application
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FoxUdpLifecycleTest {
    private fun relaySocket() = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    private fun packet(port: Int) = byteArrayOf(0, 0, 0, 1, 203.toByte(), 0, 113, 1,
        (port ushr 8).toByte(), port.toByte(), 42)
    private fun send(client: DatagramSocket, server: DatagramSocket, port: Int) {
        val data = packet(port)
        client.send(DatagramPacket(data, data.size, InetAddress.getByName("127.0.0.1"), server.localPort))
    }

    private class Pipe : FoxDatagramPipe {
        val closed = CountDownLatch(1)
        val sends = AtomicInteger()
        override fun send(payload: ByteArray) { sends.incrementAndGet() }
        override fun receive(): ByteArray? { closed.await(10, TimeUnit.SECONDS); return null }
        override fun close() { closed.countDown() }
    }

    @Test
    fun reservedAssociationClosedBeforeStartReleasesSocketAndSlotExactlyOnce() {
        val baseline = FoxUdpAssociate.reservationCount()
        assertTrue(FoxUdpAssociate.reserve())
        val socket = relaySocket()
        val association = FoxUdpAssociate(socket, 1280, { null }, { _, _ -> null }, null, { false }, hasReservation = true)
        association.close()
        association.close()
        association.start() // a closed association cannot be revived
        assertTrue(socket.isClosed)
        assertEquals(baseline, FoxUdpAssociate.reservationCount())
    }

    @Test
    fun failedRelaySocketReceiveReleasesReservation() {
        val baseline = FoxUdpAssociate.reservationCount()
        assertTrue(FoxUdpAssociate.reserve())
        val socket = relaySocket()
        val association = FoxUdpAssociate(socket, 1280, { null }, { _, _ -> null }, null, { false }, hasReservation = true)
        try {
            association.start()
            socket.close() // simulate unexpected socket failure, not close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (FoxUdpAssociate.reservationCount() != baseline && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(baseline, FoxUdpAssociate.reservationCount())
        } finally { association.close() }
    }

    @Test
    fun flowOpeningAfterAssociationCloseIsClosedInsteadOfEscaping() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pipe = Pipe()
        val socket = relaySocket()
        val association = FoxUdpAssociate(socket, 1280, { null }, { _, _ ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            pipe
        }, null, { false })
        val client = relaySocket()
        try {
            association.start()
            send(client, socket, 443)
            assertTrue("flow open was not entered", entered.await(3, TimeUnit.SECONDS))
            association.close()
            release.countDown()
            assertTrue("late flow was not closed", pipe.closed.await(3, TimeUnit.SECONDS))
            assertEquals(0, pipe.sends.get())
        } finally {
            release.countDown()
            association.close()
            client.close()
        }
    }

    @Test
    fun idleFlowIsSweptWhileAnotherFlowContinuouslyReceivesPackets() {
        val opened = ConcurrentHashMap<Int, Pipe>()
        val socket = relaySocket()
        val association = FoxUdpAssociate(socket, 1280, { null }, { _, port ->
            Pipe().also { opened[port] = it }
        }, null, { false }, idleMillis = 450, sweepMillis = 75)
        val client = relaySocket()
        try {
            association.start()
            send(client, socket, 40001)
            send(client, socket, 40002)
            val initial = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (opened.size < 2 && System.nanoTime() < initial) Thread.sleep(10)
            assertEquals(2, opened.size)
            val idle = opened.getValue(40001)
            val active = opened.getValue(40002)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (idle.closed.count > 0 && System.nanoTime() < deadline) {
                send(client, socket, 40002)
                Thread.sleep(20)
            }
            assertEquals("idle flow was not reclaimed during traffic", 0L, idle.closed.count)
            assertFalse("active flow should stay open", active.closed.count == 0L)
            assertTrue(active.sends.get() > 1)
        } finally {
            association.close()
            client.close()
        }
    }
}
