package com.freeenterprise.app.ff4ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A Final Fantasy IV menu window: solid #000083 body, 1px white border, and a
 * 1px black inner line (the game's bevel), square corners. Unfocused/dimmed
 * windows use the game's grey variants.
 *
 * Chrome is drawn behind the children, so you can add any content view.
 */
class FF4Window @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val bodyPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = false
    }
    private val innerPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = false
    }

    var focused: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var dimmed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        val pad = FF4Theme.contentPadding(context)
        setPadding(pad, pad, pad, pad)
        setWillNotDraw(false)
    }

    override fun dispatchDraw(canvas: Canvas) {
        drawChrome(canvas)
        super.dispatchDraw(canvas)
    }

    private fun drawChrome(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val border = FF4Theme.borderWidth(context)
        val inner = FF4Theme.innerWidth(context)

        bodyPaint.color = if (dimmed) FF4Theme.GREY_DARK else FF4Theme.BLUE
        borderPaint.strokeWidth = border
        borderPaint.color = if (dimmed) FF4Theme.GREY else FF4Theme.WHITE
        innerPaint.strokeWidth = inner
        innerPaint.color = if (dimmed) FF4Theme.BLACK else FF4Theme.BLACK

        // body
        canvas.drawRect(border, border, w - border, h - border, bodyPaint)
        // outer white border (centered on the edge)
        canvas.drawRect(border / 2f, border / 2f, w - border / 2f, h - border / 2f, borderPaint)
        // inner black bevel line
        val off = border + inner / 2f
        canvas.drawRect(off, off, w - off, h - off, innerPaint)
    }
}