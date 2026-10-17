package com.freeenterprise.app.ff4ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** A chunky FF4-styled progress bar (bordered trough + filled proportion). */
class FF4ProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
        color = FF4Theme.YELLOW
    }
    private val troughPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
        color = 0xFF000050.toInt()
    }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = false
        color = FF4Theme.WHITE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unit = FF4Theme.unit(context)
        val wantedHeight = (unit * 10f).toInt()
        val height = resolveSize(wantedHeight, heightMeasureSpec)
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val border = FF4Theme.unit(context)
        borderPaint.strokeWidth = border

        canvas.drawRect(border, border, w - border, h - border, troughPaint)

        // inner track, then filled proportion
        val left = border * 2
        val top = border * 2
        val right = w - border * 2
        val bottom = h - border * 2
        val innerWidth = right - left
        if (innerWidth > 0) {
            val filled = innerWidth * (progress / 100f)
            if (filled > 0f) {
                canvas.drawRect(left, top, left + filled, bottom, fillPaint)
            }
        }

        canvas.drawRect(border / 2f, border / 2f, w - border / 2f, h - border / 2f, borderPaint)
    }
}