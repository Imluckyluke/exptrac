package com.hadi.expensetracker

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A simple animated donut chart. Slices are revealed clockwise from the top as [progress]
 * sweeps from 0 to 1, with a small gap between each slice for a modern "expressive" look
 * (rounded stroke caps, segmented ring) instead of a flat filled pie.
 */
class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Slice(val color: Int, val amount: Float)

    private var slices: List<Slice> = emptyList()
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val bounds = RectF()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var trackColor: Int = 0x00000000

    fun setTrackColor(color: Int) {
        trackColor = color
        invalidate()
    }

    fun setData(newSlices: List<Slice>, animate: Boolean = true) {
        slices = newSlices.filter { it.amount > 0f }
        animator?.cancel()
        contentDescription = if (slices.isEmpty()) "No data" else "${slices.size} categories"
        if (!animate) {
            progress = 1f
            invalidate()
            return
        }
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val diameter = width.coerceAtMost(height).toFloat()
        if (diameter <= 0f) return

        val strokeWidth = diameter * 0.16f
        ringPaint.strokeWidth = strokeWidth
        trackPaint.strokeWidth = strokeWidth
        trackPaint.color = trackColor

        val inset = strokeWidth / 2f + (width - diameter) / 2f + (diameter * 0.02f)
        bounds.set(inset, inset, width - inset, height - inset)

        canvas.drawOval(bounds, trackPaint)

        val total = slices.sumOf { it.amount.toDouble() }.toFloat()
        if (total <= 0f) return

        var revealed = 360f * progress
        var start = -90f
        val gap = if (slices.size > 1) 4f else 0f

        for (slice in slices) {
            val fullSweep = (slice.amount / total) * 360f
            val drawableSweep = (fullSweep - gap).coerceAtLeast(0f)
            val sweep = drawableSweep.coerceAtMost((revealed - gap).coerceAtLeast(0f))
            if (sweep > 0.5f) {
                ringPaint.color = slice.color
                canvas.drawArc(bounds, start, sweep, false, ringPaint)
            }
            revealed -= fullSweep
            start += fullSweep
            if (revealed <= 0f) break
        }
    }
}
