package com.gozar

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.concurrent.atomic.AtomicBoolean

/** Shape of one glass surface. Pixel values, converted from dp by the caller. */
internal data class GlassViewConfig(
    val cornerRadiusPx: Float = 28f,
    val tint: Int = REGULAR_TINT,
    val bevelWidthPx: Float = 0f,
    val refractionHeightPx: Float = 0f,
    val dispersion: Float = 0.10f,
    val sensorHighlight: Boolean = false,
    /** Keep the container's own background as the thing the glass refracts. */
    val keepBackdrop: Boolean = false,
    val material: GlassMaterial = GlassMaterial.REGULAR
) {
    companion object {
        /**
         * iOS "regular" material reads as tinted glass around 20-40% alpha.
         * Kept low so the text on top stays at full contrast.
         */
        const val REGULAR_TINT = 0x2EFFFFFF.toInt()
        const val DARK_TINT = 0x33101418
    }
}

/**
 * Glass surfaces for the panels that used to read as flat cards.
 *
 * The refraction is real: every surface samples the view drawn behind it, so it
 * is inserted *behind* existing content instead of replacing it. Content keeps
 * its ids, adapters and click handlers - only the drawing changes.
 *
 * ## Why every call is guarded
 *
 * The library ships prebuilt native blur kernels for arm64-v8a and
 * armeabi-v7a only. On any other ABI (an x86 emulator, a JVM unit test) the
 * first touch of one of its classes throws `UnsatisfiedLinkError` from a static
 * initialiser, and an `Error` cannot be recovered from reliably. [available]
 * probes once and every other entry point becomes a no-op when the probe fails,
 * so unsupported devices keep their plain Material surfaces instead of
 * crashing.
 */
internal object FoxGlass {

    private val nativeReady = AtomicBoolean(false)
    private var probed = false
    private var appContext: Context? = null

    /** Remember the application context for the one-time probe. */
    fun install(context: Context) {
        appContext = context.applicationContext ?: context
        available()
    }

    /** Whether this process can draw glass at all. */
    fun available(): Boolean {
        if (probed) return nativeReady.get()
        probed = true
        val context = appContext ?: return false
        val ready = try {
            // Construction runs the library's static initialisers, which is what
            // loads libnativegauss.so.
            LiquidGlassView(context)
            true
        } catch (_: Throwable) {
            false
        }
        nativeReady.set(ready)
        return ready
    }

    fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    /**
     * Insert a glass pane behind [container]'s existing children.
     *
     * With [GlassViewConfig.keepBackdrop] the container's background stays where
     * it is and becomes the thing the glass refracts (a gradient, for example);
     * otherwise it moves onto the content so it is still drawn exactly once.
     *
     * Returns null when glass is unavailable or the swap could not be done, and
     * the container is then left untouched.
     */
    fun wrap(container: ViewGroup, config: GlassViewConfig): LiquidGlassView? {
        if (!available() || container.childCount == 0) return null
        if (container.getTag(R.id.glass_host) == true) return null
        return try {
            val context = container.context
            val backdrop = container.background
            val children = ArrayList<View>(container.childCount)
            for (index in 0 until container.childCount) children.add(container.getChildAt(index))
            container.removeAllViews()

            val content = FrameLayout(context).apply {
                clipToPadding = false
                clipChildren = false
            }
            if (!config.keepBackdrop) {
                content.background = backdrop
                container.background = null
            }
            for (child in children) content.addView(child, child.layoutParams)

            val glass = newGlass(context, config)
            glass.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            glass.addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            container.addView(glass, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            container.clipChildren = false
            container.clipToPadding = false
            container.setTag(R.id.glass_host, true)
            glass
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Insert a glass pane *under* [view], which stays exactly where it is.
     *
     * Wrapping is not an option for views that manage their own children
     * (`BottomNavigationView` inflates its menu into one), so this variant only
     * adds a drawer below the view and makes the view transparent. The glass
     * then refracts whatever the view is drawn over, and the view keeps its own
     * hierarchy, ids and listeners.
     */
    fun rehost(view: View, config: GlassViewConfig): LiquidGlassView? {
        if (!available()) return null
        val parent = view.parent as? ViewGroup ?: return null
        if (view.getTag(R.id.glass_pane) == true) return null
        return try {
            val index = parent.indexOfChild(view)
            val params = view.layoutParams
            val glass = newGlass(view.context, config)
            parent.removeViewAt(index)
            parent.addView(glass, index, params)
            parent.addView(view, index + 1, params)
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            view.setTag(R.id.glass_pane, true)
            glass
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Give a `BottomSheetDialog` a glass panel.
     *
     * The sheet's own frame gets transparent, and the glass takes its place; the
     * sampled backdrop is the dimmed screen behind the sheet, which is the one
     * place in this app with enough detail for refraction to read.
     */
    fun sheet(dialog: BottomSheetDialog, config: GlassViewConfig): Boolean {
        if (!available()) return false
        return try {
            val frame = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? ViewGroup
                ?: return false
            if (frame.getTag(R.id.glass_host) == true) return true
            frame.background = null
            frame.clipChildren = false
            // The sheet content is a single child (the inflated layout); wrap it.
            wrap(frame, config) != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun newGlass(context: Context, config: GlassViewConfig): LiquidGlassView {
        val glass = LiquidGlassView(context)
        glass.material = config.material
        glass.cornerRadius = config.cornerRadiusPx
        glass.glassTint = config.tint
        glass.enableDynamicBackground = true
        glass.enableAdaptiveTint = true
        glass.enableSensorHighlight = config.sensorHighlight
        glass.dispersionStrength = config.dispersion
        glass.downsampleScale = 2
        glass.collectFrameStats = false
        if (config.bevelWidthPx > 0f) glass.bevelWidth = config.bevelWidthPx
        if (config.refractionHeightPx > 0f) glass.refractionHeight = config.refractionHeightPx
        return glass
    }

    /** Corner radius used by every glass panel, in dp. */
    const val PANEL_RADIUS_DP = 28f
}
