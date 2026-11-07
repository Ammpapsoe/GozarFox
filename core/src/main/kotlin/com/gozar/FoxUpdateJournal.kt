package com.gozar

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks unconfirmed launches after an update. This is a health signal, not
 * proof of a crash: lack of a VPN connection may simply mean the user has
 * not connected yet or the network is offline.
 *
 * Android normally prevents reinstalling an older versionCode. A recovery
 * build must therefore be released with a higher versionCode; a journal entry
 * or cached APK alone cannot promise a successful rollback.
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
        private val locks = ConcurrentHashMap<String, Any>()
    }

    // Multiple callers may construct journals for the same path. Serialize
    // read/modify/write across instances as well as across threads.
    private val lock: Any = synchronized(locks) {
        locks.getOrPut(file.absoluteFile.normalize().path) { Any() }
    }

    fun state(): State = synchronized(lock) { load() }

    /** Called before presenting the system installer for a newer build. */
    fun recordUpdate(newVersion: String, previousVersion: String, cachedPreviousPath: String) = synchronized(lock) {
        save(State(installed = newVersion, previous = previousVersion, previousPath = cachedPreviousPath))
    }

    /** Cancel a pending trial when the installer could not be launched. */
    fun cancelPending(newVersion: String, installedNow: String) = synchronized(lock) {
        val state = load()
        if (state.installed == newVersion && state.previous == installedNow) {
            save(State(installed = installedNow, healthy = true))
        }
    }

    /** Called on launch; counts launches that have not proven tunnel health. */
    fun beginLaunch(installedNow: String): Int = synchronized(lock) {
        var state = load()
        if (state.installed != installedNow) {
            state = if (installedNow.isNotBlank() && state.previous == installedNow) {
                // The system installer was cancelled or rejected; do not
                // treat the proposed, newer build as the previous version.
                State(installed = installedNow, healthy = true)
            } else {
                // An externally applied update has no trustworthy cached copy
                // of the version it replaced; never reuse an older cache path.
                val cameFrom = state.installed.takeIf {
                    it.isNotBlank() && FoxUpdate.isNewer(installedNow, it)
                } ?: ""
                State(installed = installedNow, previous = cameFrom)
            }
        } else if (!state.healthy) {
            state = state.copy(attempts = if (state.attempts == Int.MAX_VALUE) Int.MAX_VALUE else state.attempts + 1)
        }
        save(state)
        state.attempts
    }

    /** Called once the tunnel is proven up; ends the trial. */
    fun markHealthy() = synchronized(lock) {
        val state = load()
        if (state.installed.isNotBlank() && !state.healthy) save(state.copy(attempts = 0, healthy = true))
    }

    /** A previous version associated with repeated unconfirmed launches, if known. */
    fun rollbackTarget(installedNow: String): String? = synchronized(lock) {
        val state = load()
        if (state.installed != installedNow || state.healthy || state.attempts < ROLLBACK_THRESHOLD ||
            state.previous.isBlank() || !FoxUpdate.isNewer(installedNow, state.previous)) null else state.previous
    }

    /** Legacy cached path, never a guarantee that Android will allow a downgrade. */
    fun rollbackFile(): File? = synchronized(lock) {
        val path = load().previousPath
        if (path.isBlank()) null else File(path).takeIf { it.isFile && it.length() > 0 }
    }

    fun clear() = synchronized(lock) {
        try {
            if (file.exists()) file.delete()
            File(file.absoluteFile.parentFile, file.name + ".tmp").delete()
        } catch (_: Exception) {
        }
    }

    private fun load(): State = try {
        if (!file.isFile) State() else {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            State(
                installed = json.optString("installed"),
                previous = json.optString("previous"),
                previousPath = json.optString("previous_path"),
                attempts = json.optInt("attempts", 0).coerceAtLeast(0),
                healthy = json.optBoolean("healthy", false)
            )
        }
    } catch (_: Exception) {
        State()
    }

    private fun save(state: State) {
        try {
            val parent = file.absoluteFile.parentFile ?: return
            if (!parent.isDirectory && !parent.mkdirs()) return
            val json = JSONObject()
                .put("installed", state.installed)
                .put("previous", state.previous)
                .put("previous_path", state.previousPath)
                .put("attempts", state.attempts)
                .put("healthy", state.healthy)
            val temporary = File(parent, file.name + ".tmp")
            FileOutputStream(temporary).use { out ->
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            // On Android/Linux this rename atomically replaces the old file.
            // If replacement is unsupported, retain the old journal rather
            // than deleting it first and leaving a crash window.
            if (!temporary.renameTo(file.absoluteFile)) temporary.delete()
        } catch (_: Exception) {
            // A failed journal must never crash the VPN application.
        }
    }
}
