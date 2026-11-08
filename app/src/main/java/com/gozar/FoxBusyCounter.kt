package com.gozar

import java.util.concurrent.atomic.AtomicInteger

/** Tracks concurrent UI operations; the callback is an injectable test seam. */
internal class FoxBusyCounter(private val afterDecrement: () -> Unit = {}) {
    private val active = AtomicInteger()
    @Volatile private var publishedBusy = false
    @Volatile private var forcedBusy = false

    var busy: Boolean
        get() = forcedBusy || publishedBusy
        set(value) { forcedBusy = value }

    fun begin() {
        active.incrementAndGet()
        publishedBusy = true
    }

    fun end() {
        val remaining = active.decrementAndGet()
        afterDecrement()
        publishedBusy = remaining > 0
    }
}
