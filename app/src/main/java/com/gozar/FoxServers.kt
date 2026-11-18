package com.gozar

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

object FoxServers {

    fun show(context: Context, onPick: () -> Unit) {
        val host = context
        val dialog = BottomSheetDialog(host)
        val view = LayoutInflater.from(host).inflate(R.layout.sheet_fox_servers, null)
        dialog.setContentView(view)
        val list = view.findViewById<LinearLayout>(R.id.foxSheetList)
        val empty = view.findViewById<TextView>(R.id.foxSheetEmpty)
        val count = view.findViewById<TextView>(R.id.foxSheetCount)

        fun paint() {
            list.removeAllViews()
            val recent = if (Store.recents(host)) Store.lastServer(host) else ""
            val servers = if (recent.isEmpty()) Fox.servers else
                Fox.servers.sortedBy { if (it.host.equals(recent, true)) 0 else 1 }
            empty.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
            val ready = servers.count { Fox.delayOf(it) > 0 }
            count.text = if (servers.isEmpty()) "" else if (ready == 0) "" else "$ready / ${servers.size}"
            val chosen = FoxStore.selected(host)
            FlagAssets.prefetch(host, servers.map { it.code })
            for (location in servers) {
                val row = LayoutInflater.from(host).inflate(R.layout.item_fox_server, list, false)
                row.findViewById<CountryFlagView>(R.id.foxFlag).countryCode = location.code
                row.findViewById<TextView>(R.id.foxCountry).text = location.country
                val city = row.findViewById<TextView>(R.id.foxCity)
                // One clean line per row: show the city only when there is one.
                city.text = location.city
                city.visibility = if (location.city.isEmpty()) View.GONE else View.VISIBLE
                val delay = Fox.delayOf(location)
                val delayText = row.findViewById<TextView>(R.id.foxDelay)
                delayText.text = when {
                    delay < 0 -> host.getString(R.string.fox_ping_wait)
                    delay == 0 -> ""
                    else -> "$delay ms"
                }
                delayText.visibility = if (delayText.text.isEmpty()) View.GONE else View.VISIBLE
                row.findViewById<View>(R.id.foxCheck).visibility =
                    if (location.code == chosen) View.VISIBLE else View.INVISIBLE
                row.setOnClickListener {
                    Fox.select(host, location)
                    dialog.dismiss()
                    onPick()
                }
                list.addView(row)
            }
        }

        val listener: () -> Unit = {
            if (dialog.isShowing) {
                try {
                    paint()
                } catch (e: Exception) {
                }
            }
        }
        Fox.watch(listener)
        view.findViewById<View>(R.id.foxSheetMeasure).setOnClickListener { Fox.measure(host) }
        dialog.setOnDismissListener { Fox.unwatch(listener) }
        paint()
        if (!Fox.busy && Fox.servers.isNotEmpty() && !Fox.hasDelays()) Fox.measure(host)
        dialog.setCanceledOnTouchOutside(true)
        // The sheet is a plain rounded panel now; only the top corners are
        // rounded and the list rows sit on it as flat rows with separators.
        view.post { roundedSheet(dialog) }
        dialog.show()
    }

    /**
     * Give the sheet the top-rounded surface declared in the layout.
     *
     * The rows are plain list rows with a hairline separator, a check mark on
     * the right of the chosen one, and no card of their own, which is what the
     * reference screen shows. Nothing here draws glass.
     */
    private fun roundedSheet(dialog: BottomSheetDialog) {
        try {
            val frame = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) as? ViewGroup ?: return
            frame.setBackgroundResource(R.drawable.sheet_surface)
            frame.clipChildren = false
        } catch (_: Throwable) {
        }
    }
}
