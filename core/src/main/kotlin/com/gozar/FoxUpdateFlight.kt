package com.gozar

/**
 * One update request at a time, including publication of its result. Keeping
 * publication inside the critical section prevents an older request from
 * overwriting a newer request's stored state after it has already finished.
 */
class FoxUpdateFlight<T> {
    private val lock = Any()
    private var running = false
    private var notify = false
    private val callbacks = mutableListOf<(T) -> Unit>()

    /** Returns true only for the caller that must start the network request. */
    fun join(requestNotification: Boolean, callback: ((T) -> Unit)? = null): Boolean = synchronized(lock) {
        callback?.let { callbacks.add(it) }
        notify = notify || requestNotification
        if (running) false else {
            running = true
            true
        }
    }

    data class Completion<T>(val result: T, val callbacks: List<(T) -> Unit>)

    /** Publish before allowing the next request; callbacks run outside the lock. */
    fun complete(result: T, failed: T, publish: (T, Boolean) -> Unit): Completion<T> = synchronized(lock) {
        check(running) { "no update request is running" }
        val delivered = try {
            publish(result, notify)
            result
        } catch (_: Exception) {
            failed
        }
        val completion = Completion(delivered, callbacks.toList())
        callbacks.clear()
        notify = false
        running = false
        completion
    }
}
