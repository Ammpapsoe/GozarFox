package com.gozar

/**
 * Serializes short lifecycle transitions with the generation check. Work done
 * outside [monitor] must carry its original generation and use [ifCurrent]
 * before mutating resources belonging to the active attempt.
 */
class FoxAttemptFence {
    val monitor = Any()
    private var value = 0L

    val generation: Long get() = synchronized(monitor) { value }

    fun advance(): Long = synchronized(monitor) { ++value }

    fun <T : Any> ifCurrent(attempt: Long, action: () -> T): T? = synchronized(monitor) {
        if (attempt == value) action() else null
    }
}
