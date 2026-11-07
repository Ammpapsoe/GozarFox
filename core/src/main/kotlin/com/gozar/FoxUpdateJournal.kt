package com.gozar

import org.json.JSONObject
import java.io.File

/**
 * A small crash-journal that makes updates reversible. An update is a trial
 * until the app proves itself healthy (a tunnel came up); if three launches
 * in a row fail that proof, the journal names the previous version as the
 * rollback target and — when the previous build was cached before the update
 * — the file to reinstall.
 *
 * The journal never throws: a corrupt or missing file simply starts a fresh
 * trial, because an updater that can brick the app is worse than no updater.
 */
class FoxUpdateJournal(private val file: File) {

    data class State(
        val installed: String = "",
        val previous: String = "",
        val previousPath: String = "",
        val attempts: Int = 0,
        val healthy: Boolean = false
    )

    companion object {
        const val ROLLBACK_THRESHOLD = 3
    }

    @Synchronized
    fun state(): State = load()

    /** Called when a new build has been applied, before it is launched. */
    @Synchronized
    fun recordUpdate(newVersion: String, previousVersion: String, cachedPreviousPath: String) {
        save(
            State(
                installed = newVersion,
                previous = previousVersion,
                previousPath = cachedPreviousPath,
                attempts = 0,
                healthy = false
            )
        )
    }

    /**
     * Called on every launch with the version that is actually running.
     * Returns the number of consecutive unproven launches of this build.
     */
    @Synchronized
    fun beginLaunch(installedNow: String): Int {
        var state = load()
        if (state.installed != installedNow) {
            // A build the journal did not apply (fresh install, or an update
            // through the browser): start its trial, remember where we came
            // from when the journal knows it.
            val cameFrom = state.installed.takeIf { it.isNotBlank() && it != installedNow } ?: ""
            val path = state.previousPath.takeIf { cameFrom.isNotBlank() && state.installed == cameFrom } ?: ""
            state = State(installed = installedNow, previous = cameFrom, previousPath = path, attempts = 0, healthy = false)
        } else if (!state.healthy) {
            state = state.copy(attempts = state.attempts + 1)
        }
        save(state)
        return state.attempts
    }

    /** Called once the tunnel is proven up; ends the trial. */
    @Synchronized
    fun markHealthy() {
        val state = load()
        if (state.installed.isBlank() || state.healthy) return
        save(state.copy(attempts = 0, healthy = true))
    }

    /** The version to fall back to, or null when this build is fine (or unknown). */
    @Synchronized
    fun rollbackTarget(installedNow: String): String? {
        val state = load()
        if (state.installed != installedNow || state.healthy) return null
        if (state.attempts < ROLLBACK_THRESHOLD) return null
        if (state.previous.isBlank()) return null
        if (!FoxUpdate.isNewer(installedNow, state.previous)) return null
        return state.previous
    }

    /** The cached previous build to reinstall, when one exists on disk. */
    @Synchronized
    fun rollbackFile(): File? {
        val path = load().previousPath
        if (path.isBlank()) return null
        val candidate = File(path)
        return if (candidate.isFile && candidate.length() > 0) candidate else null
    }

    @Synchronized
    fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
    }

    private fun load(): State {
        return try {
            if (!file.isFile) return State()
            val json = JSONObject(file.readText(Charsets.UTF_8))
            State(
                installed = json.optString("installed"),
                previous = json.optString("previous"),
                previousPath = json.optString("previous_path"),
                attempts = json.optInt("attempts", 0).coerceAtLeast(0),
                healthy = json.optBoolean("healthy", false)
            )
        } catch (_: Exception) {
            State()
        }
    }

    private fun save(state: State) {
        try {
            file.parentFile?.mkdirs()
            val json = JSONObject()
                .put("installed", state.installed)
                .put("previous", state.previous)
                .put("previous_path", state.previousPath)
                .put("attempts", state.attempts)
                .put("healthy", state.healthy)
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(json.toString(), Charsets.UTF_8)
            if (file.exists() && !file.delete()) return
            temporary.renameTo(file)
        } catch (_: Exception) {
            // A journal we cannot write is not worth crashing the app over.
        }
    }
}
