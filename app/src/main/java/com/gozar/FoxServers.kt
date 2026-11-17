package com.gozar

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.liquidglass.LiquidGlassListItem
import com.example.liquidglass.LiquidGlassChipGroup
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
                city.text = if (location.city.isEmpty()) host.getString(R.string.fox_recommended) else location.city
                val delay = Fox.delayOf(location)
                row.findViewById<TextView>(R.id.foxDelay).text = when {
                    delay < 0 -> host.getString(R.string.fox_ping_wait)
                    delay == 0 -> "—"
                    else -> "$delay ms"
                }
                row.findViewById<View>(R.id.foxCheck).visibility =
                    if (location.code == chosen) View.VISIBLE else View.INVISIBLE
                row.setOnClickListener {
                    Fox.select(host, location)
                    dialog.dismiss()
                    onPick()
                }
                list.addView(glassRow(host, row, servers.size, list.childCount) ?: row)
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
        // the sheet is a ModalBottomSheet: give its own panel rounded corners
        dialog.setCanceledOnTouchOutside(true)
        view.post { glassSheet(dialog, view) }
        dialog.show()
    }

    /**
     * Replace one list row with a glass list item that draws the same content.
     *
     * The row is moved into the item, so `paint()` keeps updating the same
     * [TextView]s and the click listener set on the row still fires. Returns
     * false when glass is unavailable, and the caller then adds the plain row.
     *
     * The item's `position` is what gives a stack of rows one shared outline:
     * only the first and last corners are rounded, so the list no longer reads
     * as a column of sharp-edged boxes.
     */
    private fun glassRow(host: Context, row: View, total: Int, index: Int): LiquidGlassListItem? {
        if (!FoxGlass.available() || total == 0) return null
        return try {
            val item = LiquidGlassListItem(host)
            item.position = when {
                total == 1 -> LiquidGlassListItem.Position.SINGLE
                index == 0 -> LiquidGlassListItem.Position.FIRST
                index == total - 1 -> LiquidGlassListItem.Position.LAST
                else -> LiquidGlassListItem.Position.MIDDLE
            }
            item.contentView = row
            item.enableDynamicBackground = true
            item.glassTint = GlassViewConfig.REGULAR_TINT
            item.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            item.setOnClickListener { row.performClick() }
            (row.parent as? ViewGroup)?.removeView(row)
            item
        } catch (_: Throwable) {
            null
        }
    }

    /** Give the sheet panel itself a glass surface. */
    private fun glassSheet(dialog: BottomSheetDialog, view: View) {
        try {
            FoxGlass.sheet(dialog, GlassViewConfig(
                cornerRadiusPx = FoxGlass.dp(view.context, FoxGlass.PANEL_RADIUS_DP),
                tint = GlassViewConfig.REGULAR_TINT,
                bevelWidthPx = FoxGlass.dp(view.context, 28f),
                refractionHeightPx = FoxGlass.dp(view.context, 16f),
                dispersion = 0.12f,
                keepBackdrop = true
            ))
        } catch (_: Throwable) {
        }
    }
}
