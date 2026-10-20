package com.freeenterprise.app.ff4ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.freeenterprise.ff4.Ff4ProgressBarChrome

/**
 * A chunky FF4-styled progress bar (bordered trough + filled proportion).
 *
 * The geometry comes from the portable core, so the same bar can be drawn by the
 * desktop client. The core has a test asserting these numbers are unchanged from
 * when this arithmetic lived in [onDraw].
 */
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
        color = Ff4ProgressBarChrome.TROUGH_COLOR
    }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = false
        color = FF4Theme.WHITE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unit = FF4Theme.unit(context)
        val wantedHeight = (unit * Ff4ProgressBarChrome.WANTED_HEIGHT_UNITS).toInt()
        val height = resolveSize(wantedHeight, heightMeasureSpec)
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val chrome = Ff4ProgressBarChrome.of(
            width.toFloat(),
            height.toFloat(),
            FF4Theme.scale(context),
            progress,
        ) ?: return

        troughPaint.color = chrome.troughColor
        canvas.drawRect(
            chrome.trough.left,
            chrome.trough.top,
            chrome.trough.right,
            chrome.trough.bottom,
            troughPaint,
        )

        // inner track, then filled proportion
        chrome.fill?.let { fill ->
            fillPaint.color = chrome.fillColor
            canvas.drawRect(fill.left, fill.top, fill.right, fill.bottom, fillPaint)
        }

        borderPaint.strokeWidth = chrome.border.widthPx
        borderPaint.color = chrome.border.color
        canvas.drawRect(
            chrome.border.rect.left,
            chrome.border.rect.top,
            chrome.border.rect.right,
            chrome.border.rect.bottom,
            borderPaint,
        )
    }
}
