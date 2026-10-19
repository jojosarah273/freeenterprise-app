package com.freeenterprise.app.ff4ui

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A modal, centred FF4 progress window shown while a seed is being generated.
 *
 * Generation is a single blocking engine call and the menus are useless (and
 * easy to mis-navigate) during it, so this overlay dims the screen, takes focus
 * and swallows every key/touch event until it is dismissed.
 */
class FF4ProgressOverlay(context: Context) : FrameLayout(context) {

    private val stageText: TextView
    private val bar: FF4ProgressBar
    private val detailText: TextView
    private val elapsedText: TextView

    private var dots = 0

    /**
     * Heading shown above the bar. Callers set this for what they are actually
     * doing — it was hardcoded to "GENERATING SEED", so update downloads claimed
     * to be generating a seed.
     */
    var title: String = "GENERATING SEED"
        set(value) {
            field = value
            stageText.text = value
        }

    init {
        setBackgroundColor(0xE6000000.toInt())
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true

        stageText = TextView(context).apply {
            text = title
            FF4Theme.applyText(context, this)
            setTextColor(FF4Theme.WHITE)
        }
        bar = FF4ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (FF4Theme.unit(context) * 3).toInt() }
        }
        detailText = TextView(context).apply {
            FF4Theme.applyText(context, this, FF4Theme.textSmallPx(context))
            setTextColor(FF4Theme.YELLOW)
            setPadding(0, (FF4Theme.unit(context) * 2).toInt(), 0, 0)
        }
        elapsedText = TextView(context).apply {
            FF4Theme.applyText(context, this, FF4Theme.textSmallPx(context))
            setTextColor(FF4Theme.GREY)
            setPadding(0, (FF4Theme.unit(context) * 1).toInt(), 0, 0)
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(stageText)
            addView(bar)
            addView(detailText)
            addView(elapsedText)
        }
        val window = FF4Window(context).apply { addView(column) }

        addView(
            window,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                val m = (FF4Theme.unit(context) * 10).toInt()
                marginStart = m; marginEnd = m
            }
        )
    }

    /** Feed the latest progress. [pct] 0..100, [stage] e.g. "Compiling". */
    fun update(pct: Int, stage: String, detail: String, seconds: Long) {
        bar.progress = pct
        dots = (dots + 1) % 4
        val ellipsis = ".".repeat(dots)
        stageText.text = "GENERATING SEED"
        val line = buildString {
            append(stage.ifBlank { "Working" })
            if (detail.isNotBlank()) {
                append("  ·  ")
                append(detail)
            }
            append("  ")
            append(pct)
            append("%")
        }
        detailText.text = "$line$ellipsis"
        elapsedText.text = "Elapsed %d:%02d".format(seconds / 60, seconds % 60)
    }

    /** Everything is swallowed while this overlay is up. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = true
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = true

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = true

    fun show() {
        visibility = View.VISIBLE
        requestFocus()
    }

    fun hide() {
        visibility = View.GONE
        clearFocus()
    }

}