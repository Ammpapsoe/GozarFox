package com.gozar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

class TrafficChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val path = Path()
    private var samples: List<Pair<Float, Float>> = emptyList()

    fun setSamples(value: List<Pair<Float, Float>>) {
        samples = value.takeLast(60)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) return
        grid.color = ContextCompat.getColor(context, R.color.m3_outline_variant)
        grid.alpha = 90
        for (i in 0..3) {
            val y = top + (bottom - top) * i / 3f
            canvas.drawLine(left, y, right, y, grid)
        }
        if (samples.size < 2) return
        val peak = max(1024f, samples.maxOf { max(it.first, it.second) })
        drawSeries(canvas, left, top, right, bottom, peak, true, R.color.fox_red)
        drawSeries(canvas, left, top, right, bottom, peak, false, R.color.fox_orange)
    }

    private fun drawSeries(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, peak: Float, download: Boolean, color: Int) {
        path.reset()
        val step = (right - left) / 59f
        val start = right - step * (samples.size - 1)
        samples.forEachIndexed { index, sample ->
            val value = if (download) sample.first else sample.second
            val x = start + index * step
            val y = bottom - (value / peak).coerceIn(0f, 1f) * (bottom - top)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        line.color = ContextCompat.getColor(context, color)
        canvas.drawPath(path, line)
    }
}
