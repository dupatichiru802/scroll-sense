package com.dupati.scrollsense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A simple ring chart: one arc per segment, sized to its share of the total, with a
 *  small gap between segments so adjacent colors stay visually distinct. */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Segment(val fraction: Float, val color: Int)

    private var segments: List<Segment> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = RectF()

    fun setSegments(newSegments: List<Segment>) {
        segments = newSegments
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty()) return

        val strokeWidth = minOf(width, height) * 0.16f
        paint.strokeWidth = strokeWidth
        val inset = strokeWidth / 2 + 4
        arcRect.set(inset, inset, width - inset, height - inset)

        val gapDegrees = if (segments.size > 1) 4f else 0f
        var startAngle = -90f
        segments.forEach { segment ->
            val sweep = (segment.fraction * 360f - gapDegrees).coerceAtLeast(0f)
            paint.color = segment.color
            canvas.drawArc(arcRect, startAngle, sweep, false, paint)
            startAngle += segment.fraction * 360f
        }
    }
}
