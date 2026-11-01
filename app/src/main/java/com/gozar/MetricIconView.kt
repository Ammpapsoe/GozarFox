package com.gozar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.ColorInt
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import kotlin.math.min

class MetricIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.m3_surface_container_high)
    }
    private val outline = Path()
    private val forms = listOf(
        RoundedPolygon(numVertices = 5, rounding = CornerRounding(0.3f, 0.55f)),
        RoundedPolygon.star(numVerticesPerRadius = 8, innerRadius = 0.76f,
            rounding = CornerRounding(0.16f), innerRounding = CornerRounding(0.14f)),
        RoundedPolygon(numVertices = 8, rounding = CornerRounding(0.16f, 0.6f)),
        RoundedPolygon.star(numVerticesPerRadius = 5, innerRadius = 0.82f,
            rounding = CornerRounding(0.22f), innerRounding = CornerRounding(0.18f))
    )
    private var current = 0
    private var destination = 0
    private var shape = Morph(forms[0], forms[0])
    private var animator: ValueAnimator? = null
    private var pending = false
    internal var progress = 1f
        private set
    internal var shapeIndex = 0
        private set

    fun setAppearance(@ColorInt color: Int, index: Int) {
        paint.color = color
        current = Math.floorMod(index, forms.size)
        destination = current
        shapeIndex = current
        shape = Morph(forms[current], forms[current])
        progress = 1f
        invalidate()
    }

    fun changeShape() {
        if (animator?.isRunning == true) {
            pending = true
            return
        }
        destination = (current + 1) % forms.size
        shape = Morph(forms[current], forms[destination])
        progress = 0f
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            finishShape()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) finishShape()
                }
            })
            start()
        }
    }

    private fun finishShape() {
        current = destination
        shapeIndex = current
        progress = 1f
        animator = null
        invalidate()
        if (pending) {
            pending = false
            post { if (isAttachedToWindow) changeShape() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) * 0.47f
        if (radius > 0f) {
            shape.toPath(progress.coerceIn(0f, 1f), outline)
            canvas.save()
            canvas.translate(width / 2f, height / 2f)
            canvas.scale(radius, radius)
            canvas.drawPath(outline, paint)
            canvas.restore()
        }
        super.onDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        pending = false
        animator?.cancel()
        animator = null
        destination = current
        shape = Morph(forms[current], forms[current])
        progress = 1f
        super.onDetachedFromWindow()
    }
}
