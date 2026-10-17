package com.freeenterprise.app.ff4ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue

/**
 * FF4 design tokens — exact palette decoded from the real game data
 * (FF4_ASMTOC window_pal.bin): body #000083, border #FFFFFF, inner #000000.
 *
 * "Fill the screen" scaling: one SNES pixel (unit) is derived from the short
 * screen edge / 224, so borders and text stay proportional while windows fill
 * the whole display. Draw with anti-aliasing off for crisp chunky pixels.
 */
object FF4Theme {
    // palette
    const val BLUE = 0xFF000083.toInt()
    const val BLUE_SELECTED = 0xFF1B1B9E.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val GREY = 0xFF7B7B7B.toInt()
    const val GREY_DARK = 0xFF414141.toInt()
    const val GREEN = 0xFF00A400.toInt()
    const val YELLOW = 0xFFFFDE00.toInt()
    const val PINK = 0xFFFF3983.toInt()
    const val PEACH = 0xFFFF9C5A.toInt()

    private var cachedTypeface: Typeface? = null

    fun font(context: Context): Typeface {
        cachedTypeface?.let { return it }
        val tf = Typeface.createFromAsset(context.assets, "fonts/ff4.ttf")
        cachedTypeface = tf
        return tf
    }

    /** One "SNES pixel" in device px. Short edge / 224, min 2px. */
    fun unit(context: Context): Float {
        val m = context.resources.displayMetrics
        val short = minOf(m.widthPixels, m.heightPixels).toFloat()
        return maxOf(2f, short / 224f)
    }

    fun borderWidth(context: Context): Float = unit(context)
    fun innerWidth(context: Context): Float = unit(context)
    fun contentPadding(context: Context): Int =
        (unit(context) * 3f).toInt()

    /** SNES 8px cell height expressed in device px. */
    fun textPx(context: Context): Float = unit(context) * 8f
    /** Smaller text (6px cell). */
    fun textSmallPx(context: Context): Float = unit(context) * 6f

    fun applyText(context: Context, tv: android.widget.TextView, sizePx: Float = textPx(context)) {
        tv.typeface = font(context)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        tv.setShadowLayer(maxOf(1f, unit(context) * 0.5f), unit(context), unit(context), 0xFF000000.toInt())
        tv.includeFontPadding = false
    }
}