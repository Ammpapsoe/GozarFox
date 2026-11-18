package com.gozar

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.liquidglass.LiquidGlassListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.18.0 drew every server row as an empty glass bar.
 *
 * `LiquidGlassListItem.contentView`'s setter detaches the value from its current
 * parent, and the row was still attached to the list when it was first added as
 * the item's content and then removed from that list a second time, by the item,
 * after the item had already adopted it. The row ended up with no parent, and
 * the item drew its rim around nothing. These tests pin the order that works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FoxServerRowTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun row(): View {
        val row = LinearLayout(context)
        row.addView(TextView(context).apply { text = "Netherlands" })
        return row
    }

    /** The exact sequence FoxServers.glassRow uses. */
    private fun adopt(item: LiquidGlassListItem, row: View, list: ViewGroup) {
        list.addView(row)
        list.removeView(row)
        item.contentView = row
        list.addView(item)
    }

    @Test
    fun `the row stays inside the glass item`() {
        val list = LinearLayout(context)
        val row = row()
        val item = LiquidGlassListItem(context)
        adopt(item, row, list)

        assertEquals(1, list.childCount)
        assertEquals(item, list.getChildAt(0))
        assertEquals("the row must not be orphaned", item, row.parent)
        assertEquals(View.VISIBLE, row.visibility)
    }

    @Test
    fun `adopting before detaching loses the row`() {
        val list = LinearLayout(context)
        val row = row()
        val item = LiquidGlassListItem(context)
        list.addView(row)
        item.contentView = row
        // What the shipped code did next: remove the row from its old parent,
        // which by now is the item itself.
        (row.parent as? ViewGroup)?.removeView(row)
        assertFalse("this is the bug the fix removes", item === row.parent)
    }

    @Test
    fun `rows keep their own background and text`() {
        // item_fox_server resolves ?attr/ color and selectableItemBackground,
        // so it has to be inflated inside the activity the app really uses.
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        val list = LinearLayout(activity)
        val row = LayoutInflater.from(activity).inflate(R.layout.item_fox_server, list, false)
        val country = row.findViewById<TextView>(R.id.foxCountry)
        country.text = "Netherlands"
        val item = LiquidGlassListItem(context)
        adopt(item, row, list)
        assertEquals("Netherlands", country.text.toString())
        assertNotNull(row.background)
    }

    @Test
    fun `an item without glass falls back to the plain row`() {
        val list = LinearLayout(context)
        val row = row()
        list.addView(row)
        assertEquals(1, list.childCount)
        assertEquals(row, list.getChildAt(0))
    }
}
