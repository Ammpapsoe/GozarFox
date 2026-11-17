package com.gozar

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object MozillaDiagnostics {
    private val lock = Any()
    private val events = ArrayDeque<String>()

    /**
     * Per-flow events (a CONNECT per connection, a UDP association per
     * destination) would otherwise grow this buffer for as long as the
     * tunnel stays up, so only the most recent [LIMIT] survive.
     */
    private const val LIMIT = 600

    fun clear() = synchronized(lock) { events.clear() }

    fun hasEvents(): Boolean = synchronized(lock) { events.isNotEmpty() }

    fun record(event: String) {
        synchronized(lock) {
            events.addLast(event)
            while (events.size > LIMIT) events.removeFirst()
        }
        Log.d("MozillaWebView", event)
    }

    private fun snapshot(): String = synchronized(lock) { events.joinToString("\n") }

    fun show(activity: Activity) {
        val snapshot = snapshot()
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val text = TextView(activity).apply {
            text = snapshot.ifEmpty { activity.getString(R.string.mozilla_logs_empty) }
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(activity).apply { addView(text) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.mozilla_logs)
            .setView(scroll)
            .setPositiveButton(R.string.mozilla_logs_copy) { _, _ ->
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.mozilla_logs), snapshot))
                Toast.makeText(activity, R.string.mozilla_logs_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }
}
