package com.gozar

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The glass renderer ships native blur kernels for arm64-v8a and
 * armeabi-v7a only. On every other ABI - including this JVM test - touching
 * one of its classes raises `UnsatisfiedLinkError` from a static initialiser,
 * which is an `Error` and not recoverable. These tests pin the contract the
 * app relies on: the surface helpers never throw, and when glass is not
 * available the caller is told so and its view hierarchy is left untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FoxGlassTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun container(): FrameLayout {
        val parent = FrameLayout(context)
        val child = TextView(context).apply { text = "row" }
        child.id = 4242
        parent.addView(child, ViewGroup.LayoutParams(100, 40))
        return parent
    }

    @Test
    fun `probing the renderer never throws`() {
        FoxGlass.install(context)
        // Either answer is valid; the contract is that asking never throws and
        // that the answer is stable.
        val first = FoxGlass.available()
        assertEquals(first, FoxGlass.available())
    }

    @Test
    fun `wrap reports failure instead of throwing on an unsupported abi`() {
        FoxGlass.install(context)
        val parent = container()
        val child = parent.getChildAt(0)
        val glass = FoxGlass.wrap(parent, GlassViewConfig())
        if (!FoxGlass.available()) {
            assertNull("an unsupported ABI must not claim a glass pane", glass)
            assertEquals(1, parent.childCount)
            assertSame(child, parent.getChildAt(0))
        } else {
            assertNotNull(glass)
        }
    }

    @Test
    fun `rehost keeps the host view and its listeners in place`() {
        FoxGlass.install(context)
        val host = FrameLayout(context)
        val bar = TextView(context).apply { text = "nav" }
        var clicked = 0
        bar.setOnClickListener { clicked++ }
        host.addView(bar, ViewGroup.LayoutParams(200, 60))

        val glass = FoxGlass.rehost(bar, GlassViewConfig())
        if (!FoxGlass.available()) {
            assertNull(glass)
            assertEquals(1, host.childCount)
            assertSame(bar, host.getChildAt(0))
            bar.performClick()
            assertEquals(1, clicked)
        }
    }

    @Test
    fun `dp follows the display density`() {
        val px = FoxGlass.dp(context, 28f)
        assertTrue(px > 0f)
        assertEquals(28f * context.resources.displayMetrics.density, px, 0.001f)
    }

    @Test
    fun `a container marked as a glass host is not wrapped twice`() {
        FoxGlass.install(context)
        val parent = container()
        parent.setTag(R.id.glass_host, true)
        assertNull(FoxGlass.wrap(parent, GlassViewConfig()))
        assertEquals(1, parent.childCount)
    }

    @Test
    fun `every surface entry point tolerates an empty container`() {
        FoxGlass.install(context)
        val empty = FrameLayout(context)
        assertNull(FoxGlass.wrap(empty, GlassViewConfig()))
        val lonely = TextView(context)
        assertNull(FoxGlass.rehost(lonely, GlassViewConfig()))
        assertFalse(empty.childCount > 0)
    }
}
