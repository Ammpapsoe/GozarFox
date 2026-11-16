package com.gozar

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The DNS control plane behind the packet engine.
 *
 * The data plane (hev-socks5-tunnel) can only forward packets; it cannot peek
 * at the single tun fd to divert port 53 locally the way the hand-rolled
 * stack did. DNS is therefore intercepted one layer up, at the local proxy
 * that hev forwards through: the proxy recognizes port 53 and hands the
 * payload here. This class keeps the anti-censorship behavior that used to
 * live in the hand-rolled stack's DNS branch, byte for byte: DoH race with custom
 * profiles, BlockAds, TTL patching, anti-poison cache rules, the bounded
 * worker queue, and the epoch fence so an answer minted by a stale transport
 * attempt is never published.
 */
class FoxDnsBridge(
    private val resolver: FoxResolver,
    mtu: Int,
    /** Captured per request; when it yields null the query is dropped, exactly
     *  like the stack dropped packets from a stale attempt. */
    private val scopeProvider: () -> FoxDnsScope?
) {
    // UDP payload the local resolver may emit; answers are truncated to the
    // same budget the stack used so they fit a 1280-byte path.
    private val payloadLimit = (mtu - 28).coerceAtLeast(512)
    private val closed = AtomicBoolean(false)

    private val workers = ThreadPoolExecutor(8, 8, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(128)) { task ->
        Thread(task, "fox-dns").apply { isDaemon = true }
    }

    fun isOpen(): Boolean = !closed.get()

    /**
     * Resolve a wire-format DNS query and hand the (truncated) wire answer to
     * [listener]. Never blocks the caller; a stale attempt observes the same
     * silent drop the hand-rolled stack produced.
     */
    fun answer(query: ByteArray, listener: (ByteArray) -> Unit) {
        val scope = scopeProvider() ?: return
        if (!scope.isCurrent()) return
        try {
            workers.execute {
                if (closed.get() || !scope.isCurrent()) return@execute
                val answer = try {
                    resolver.answer(query, scope)
                } catch (_: Exception) {
                    null
                } ?: return@execute
                if (closed.get() || !scope.isCurrent()) return@execute
                // Publishing the learned name and checking closure stay atomic:
                // a slow lookup cannot teach a dying transport a route.
                resolver.learn(query, answer)
                val limited = resolver.truncate(query, answer, payloadLimit)
                if (limited.isNotEmpty() && !closed.get() && scope.isCurrent()) listener(limited)
            }
        } catch (_: RejectedExecutionException) {
            val failure = resolver.unavailable(query)
            if (failure != null && !closed.get() && scope.isCurrent()) listener(failure)
        }
    }

    /** A length-prefixed TCP DNS pipe, or null when the attempt is stale. */
    fun tcpPipe(): FoxPipe? {
        val scope = scopeProvider() ?: return null
        if (!scope.isCurrent()) return null
        return FoxDnsPipe(resolver, scope, resolver::learn)
    }

    fun close() {
        closed.set(true)
        workers.shutdownNow()
    }
}
