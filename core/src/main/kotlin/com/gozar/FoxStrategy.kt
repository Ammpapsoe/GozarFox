package com.gozar

/**
 * The self-tuning transport brain. It learns, per host, which reachability
 * mode works - the system resolver, or the last known-good address spoken to
 * directly under our certificate pins - and escalates automatically when the
 * preferred mode starts failing. There is deliberately no user-facing switch:
 * the core decides, the user only sees a connection that keeps working.
 *
 * The learning maths mirror [FoxAdaptive] (which already does this for
 * HTTP/2 vs HTTP/1): an exponentially weighted average per mode, a short
 * quarantine after consecutive failures, and an occasional probe of the
 * non-preferred mode so a healed path is rediscovered.
 */
object FoxStrategy {

    enum class Mode { PLAIN, CACHED_IP }

    private class Measure(var average: Double = 0.0, var successes: Int = 0, var failures: Int = 0, var until: Long = 0L)
    private class State(val plain: Measure = Measure(), val cached: Measure = Measure(), var count: Int = 0)

    private const val QUARANTINE_MS = 10 * 60 * 1000L
    private const val PROBE_EVERY = 12

    private val lock = Any()
    private val states = LinkedHashMap<String, State>()

    private fun now() = System.currentTimeMillis()

    /** Candidate modes, best first, for this host right now. */
    fun order(key: String): List<Mode> = synchronized(lock) {
        if (states.size >= 64 && key !in states) states.remove(states.keys.first())
        val state = states.getOrPut(key) { State() }
        state.count++
        val time = now()
        val plainReady = state.plain.until <= time
        val cachedReady = state.cached.until <= time
        val preferCached = when {
            cachedReady && !plainReady -> true
            plainReady && !cachedReady -> false
            !plainReady && !cachedReady -> false
            state.cached.successes == 0 -> false
            state.plain.successes == 0 -> true
            // Only abandon the plain path when the cached address is
            // decisively better.
            state.cached.average < state.plain.average * 0.8 -> true
            else -> false
        }
        val first = if (plainReady && cachedReady && state.count % PROBE_EVERY == 0) {
            if (preferCached) Mode.PLAIN else Mode.CACHED_IP
        } else {
            if (preferCached) Mode.CACHED_IP else Mode.PLAIN
        }
        if (first == Mode.PLAIN) listOf(Mode.PLAIN, Mode.CACHED_IP) else listOf(Mode.CACHED_IP, Mode.PLAIN)
    }

    fun success(key: String, mode: Mode, elapsedMs: Long) = synchronized(lock) {
        val measure = measure(key, mode)
        val value = elapsedMs.coerceAtLeast(1).toDouble()
        measure.average = if (measure.successes == 0) value else measure.average * 0.8 + value * 0.2
        measure.successes++
        measure.failures = 0
        measure.until = 0L
    }

    fun failure(key: String, mode: Mode) = synchronized(lock) {
        val measure = measure(key, mode)
        measure.failures++
        if (measure.failures >= 2) {
            measure.until = now() + QUARANTINE_MS
            measure.failures = 0
        }
    }

    private fun measure(key: String, mode: Mode): Measure {
        val state = states.getOrPut(key) { State() }
        return if (mode == Mode.PLAIN) state.plain else state.cached
    }
}
