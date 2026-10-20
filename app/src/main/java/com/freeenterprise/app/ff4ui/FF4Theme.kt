package com.freeenterprise.app.ff4ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import com.freeenterprise.ff4.Ff4Palette
import com.freeenterprise.ff4.Ff4Scale

/**
 * Android's view of the FF4 design tokens.
 *
 * The numbers themselves live in the portable `:core` module ([Ff4Scale] for
 * geometry, [Ff4Palette] for colour). This object supplies only what the platform
 * must contribute: the two facts the layout unit derives from (the short screen
 * edge and the pixel density), and the Android-only pieces -- the game font, text
 * painting and the typeface cache.
 *
 * "Fill the screen" scaling: one SNES pixel (unit) is the short screen edge / 224,
 * so borders and text stay proportional while windows fill the whole display. Draw
 * with anti-aliasing off for crisp chunky pixels.
 */
object FF4Theme {
    // palette -- values and provenance live in Ff4Palette
    const val BLUE = Ff4Palette.BLUE
    const val BLUE_SELECTED = Ff4Palette.BLUE_SELECTED
    const val WHITE = Ff4Palette.WHITE
    const val BLACK = Ff4Palette.BLACK
    const val GREY = Ff4Palette.GREY
    const val GREY_DARK = Ff4Palette.GREY_DARK
    const val GREEN = Ff4Palette.GREEN
    const val YELLOW = Ff4Palette.YELLOW
    const val PINK = Ff4Palette.PINK
    const val PEACH = Ff4Palette.PEACH

    private var cachedTypeface: Typeface? = null

    /** The layout unit for this display, derived in the portable core. */
    fun scale(context: Context): Ff4Scale {
        val m = context.resources.displayMetrics
        return Ff4Scale(minOf(m.widthPixels, m.heightPixels), m.density)
    }

    fun font(context: Context): Typeface {
        cachedTypeface?.let { return it }
        val tf = Typeface.createFromAsset(context.assets, "fonts/ff4.ttf")
        cachedTypeface = tf
        return tf
    }

    /** One "SNES pixel" in device px. Short edge / 224, min 2px. */
    fun unit(context: Context): Float = scale(context).unit

    fun borderWidth(context: Context): Float = scale(context).borderWidthPx
    fun innerWidth(context: Context): Float = scale(context).innerWidthPx
    fun contentPadding(context: Context): Int = scale(context).contentPaddingPx

    /** SNES 8px cell height expressed in device px. */
    fun textPx(context: Context): Float = scale(context).textPx

    /** Smaller text (6px cell). */
    fun textSmallPx(context: Context): Float = scale(context).textSmallPx

    fun applyText(context: Context, tv: android.widget.TextView, sizePx: Float = textPx(context)) {
        tv.typeface = font(context)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        tv.setShadowLayer(maxOf(1f, unit(context) * 0.5f), unit(context), unit(context), 0xFF000000.toInt())
        tv.includeFontPadding = false
    }
}
