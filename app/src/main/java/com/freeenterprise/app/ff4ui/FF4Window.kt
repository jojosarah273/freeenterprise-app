package com.freeenterprise.app.ff4ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.FrameLayout
import com.freeenterprise.ff4.Ff4WindowChrome

/**
 * A Final Fantasy IV menu window: solid #000083 body, 1px white border, and a
 * 1px black inner line (the game's bevel), square corners. Unfocused/dimmed
 * windows use the game's grey variants.
 *
 * The geometry is calculated in the portable core ([Ff4WindowChrome]); this class
 * only performs the three fills and strokes it describes. Keeping the calculation
 * out of the view is what lets other platforms draw the identical window, and the
 * core has a test asserting the numbers are unchanged from when this arithmetic
 * lived here.
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
        val chrome = Ff4WindowChrome.of(
            width.toFloat(),
            height.toFloat(),
            FF4Theme.scale(context),
            dimmed = dimmed,
        ) ?: return

        // body
        bodyPaint.color = chrome.bodyColor
        canvas.drawRect(
            chrome.body.left,
            chrome.body.top,
            chrome.body.right,
            chrome.body.bottom,
            bodyPaint,
        )

        // outer white border (centred on the edge, so it covers the outer pixel row)
        borderPaint.strokeWidth = chrome.border.widthPx
        borderPaint.color = chrome.border.color
        canvas.drawRect(
            chrome.border.rect.left,
            chrome.border.rect.top,
            chrome.border.rect.right,
            chrome.border.rect.bottom,
            borderPaint,
        )

        // inner black bevel line
        innerPaint.strokeWidth = chrome.bevel.widthPx
        innerPaint.color = chrome.bevel.color
        canvas.drawRect(
            chrome.bevel.rect.left,
            chrome.bevel.rect.top,
            chrome.bevel.rect.right,
            chrome.bevel.rect.bottom,
            innerPaint,
        )
    }
}
