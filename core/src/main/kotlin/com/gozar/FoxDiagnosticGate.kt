package com.gozar

/**
 * A newer probe or an invalidated VPN session supersedes every older probe.
 * All state transitions are serialized so a slow callback cannot restore an
 * obsolete result after disconnect/reconnect or after a more recent check.
 */
class FoxDiagnosticGate<T> {
    private var serial = 0L
    private var value: T? = null
    private var active = false

    @Synchronized
    fun begin(): Long {
        serial += 1
        value = null
        active = true
        return serial
    }

    @Synchronized
    fun publish(ticket: Long, result: T): Boolean {
        if (!active || ticket != serial) return false
        active = false
        value = result
        return true
    }

    @Synchronized
    fun invalidate() {
        serial += 1
        active = false
        value = null
    }

    @Synchronized
    fun current(): T? = value
}
