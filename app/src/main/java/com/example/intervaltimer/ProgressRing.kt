package com.example.intervaltimer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A circular ring that empties as the current round counts down. */
class ProgressRing @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val stroke = 14f * resources.displayMetrics.density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = context.getColor(R.color.track)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.accent)
    }
    private val bounds = RectF()

    /** Fraction of the ring that is filled, 0..1. */
    var fraction = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) { field = v; invalidate() }
        }

    fun setRingColor(color: Int) { arc.color = color; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val pad = stroke / 2 + 2
        val size = minOf(width, height).toFloat()
        val left = (width - size) / 2 + pad
        val top = (height - size) / 2 + pad
        bounds.set(left, top, left + size - 2 * pad, top + size - 2 * pad)
        canvas.drawOval(bounds, track)
        if (fraction > 0f) canvas.drawArc(bounds, -90f, 360f * fraction, false, arc)
    }
}

/** A row of segments, one per round; completed rounds are filled. */
class RoundsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.track) }
    private val full = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val rect = RectF()

    var rounds = 10
        set(value) { field = value; invalidate() }

    /** Progress across all rounds in "rounds" units, e.g. 2.5 = halfway through round 3. */
    var progress = 0f
        set(value) { if (value != field) { field = value; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2
        val w = width.toFloat()
        if (rounds > 30) {   // too many to draw as segments: one continuous bar
            rect.set(0f, 0f, w, h); canvas.drawRoundRect(rect, r, r, empty)
            rect.set(0f, 0f, w * (progress / rounds).coerceIn(0f, 1f), h)
            if (rect.width() > 0) canvas.drawRoundRect(rect, r, r, full)
            return
        }
        val gap = 4 * density
        val seg = (w - gap * (rounds - 1)) / rounds
        for (i in 0 until rounds) {
            val x = i * (seg + gap)
            rect.set(x, 0f, x + seg, h)
            canvas.drawRoundRect(rect, r, r, empty)
            val fill = (progress - i).coerceIn(0f, 1f)
            if (fill > 0f) {
                rect.set(x, 0f, x + seg * fill, h)
                canvas.drawRoundRect(rect, r, r, full)
            }
        }
    }
}
