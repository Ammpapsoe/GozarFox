package com.gozar

import java.util.concurrent.atomic.AtomicInteger

/** Tracks concurrent UI operations; the callback is an injectable test seam. */
internal class FoxBusyCounter(private val afterDecrement: () -> Unit = {}) {
    private val active = AtomicInteger()
    @Volatile private var forcedBusy = false

    // Read the current count directly. Publishing a separate Boolean after a
    // decrement can race a new begin() and incorrectly expose an idle UI.
    var busy: Boolean
        get() = forcedBusy || active.get() > 0
        set(value) { forcedBusy = value }

    fun begin() {
        active.incrementAndGet()
    }

    fun end() {
        check(active.decrementAndGet() >= 0) { "operation count underflow" }
        afterDecrement()
    }
}
