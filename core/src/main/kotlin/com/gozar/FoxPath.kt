package com.gozar

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FoxPathSample(
    val host: String,
    val port: Int,
    val averageMs: Long,
    val jitterMs: Long,
    val lossPercent: Int,
    val ok: Boolean
)

object FoxPath {

    const val SAMPLES = 3
    const val TIMEOUT_MS = 1200
    const val TTL_MS = 5 * 60 * 1000L

    fun score(sample: FoxPathSample): Long =
        if (!sample.ok) Long.MAX_VALUE
        else sample.averageMs + 2 * sample.jitterMs + 10L * sample.lossPercent

    fun rank(samples: List<FoxPathSample>): List<FoxPathSample> =
        samples.sortedWith(compareBy({ !it.ok }, { score(it) }))

    fun probe(host: String, port: Int, samples: Int = SAMPLES, timeoutMs: Int = TIMEOUT_MS): FoxPathSample {
        val count = samples.coerceAtLeast(1)
        val timeout = timeoutMs.coerceAtLeast(100)
        val durations = ArrayList<Long>(count)
        for (index in 0 until count) {
            val socket = Socket()
            socket.keepAlive = false
            socket.tcpNoDelay = true
            val started = System.nanoTime()
            try {
                socket.connect(InetSocketAddress(host, port), timeout)
                durations.add((System.nanoTime() - started) / 1_000_000L)
            } catch (e: Exception) {
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                }
            }
        }
        val loss = ((count - durations.size) * 100) / count
        if (durations.isEmpty()) return FoxPathSample(host, port, 0, 0, 100, false)
        val average = durations.average().toLong()
        var jitter = 0L
        if (durations.size > 1) {
            var total = 0L
            for (index in 1 until durations.size) {
                total += kotlin.math.abs(durations[index] - durations[index - 1])
            }
            jitter = total / (durations.size - 1)
        }
        return FoxPathSample(host, port, average, jitter, loss, true)
    }

    fun measure(
        locations: List<FoxLocation>,
        concurrency: Int = 8,
        samples: Int = SAMPLES,
        timeoutMs: Int = TIMEOUT_MS
    ): List<FoxPathSample> {
        if (locations.isEmpty()) return emptyList()
        val workers = concurrency.coerceIn(1, locations.size)
        val pool = Executors.newFixedThreadPool(workers) { task ->
            Thread(task, "fox-path").apply { isDaemon = true }
        }
        try {
            val tasks = locations.map { location ->
                Callable { probe(location.host, location.port, samples, timeoutMs) }
            }
            val waves = (locations.size + workers - 1) / workers
            val budget = waves * (samples.toLong() * timeoutMs + 500L)
            val futures = pool.invokeAll(tasks, budget, TimeUnit.MILLISECONDS)
            return futures.mapIndexed { index, future ->
                val location = locations[index]
                if (future.isCancelled) {
                    FoxPathSample(location.host, location.port, 0, 0, 100, false)
                } else {
                    try {
                        future.get()
                    } catch (e: Exception) {
                        FoxPathSample(location.host, location.port, 0, 0, 100, false)
                    }
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
