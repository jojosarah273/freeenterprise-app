package com.freeenterprise.desktop

import com.freeenterprise.ff4.Ff4TextMetrics
import java.awt.Font
import java.awt.font.FontRenderContext
import kotlin.math.ceil

/**
 * Text measurement for the desktop, from the same TTF the Android app uses.
 *
 * The numbers this returns are what the layout is built on: row heights, box
 * heights, whether a status line fits its two-line slot. So it answers with what
 * it will actually draw with, exactly as the Android side answers with its Paint.
 */
class AwtTextMetrics : Ff4TextMetrics {

    private val fonts = HashMap<Float, Font>()

    // Anti-aliasing off and fractional metrics off, matching the app's rendering
    // hints: whole-pixel glyph placement and integer advances.
    private val frc = FontRenderContext(null, false, false)

    fun font(sizePx: Float): Font = fonts.getOrPut(sizePx) { Assets.baseFont.deriveFont(sizePx) }

    private fun ascent(sizePx: Float): Float = font(sizePx).getLineMetrics("Ag", frc).ascent
    private fun descent(sizePx: Float): Float = font(sizePx).getLineMetrics("Ag", frc).descent

    override fun lineHeightPx(textPx: Float): Int = ceil(ascent(textPx) + descent(textPx)).toInt()

    override fun ascentPx(textPx: Float): Int = ceil(ascent(textPx)).toInt()

    override fun widthPx(text: CharSequence, textPx: Float): Int =
        ceil(font(textPx).getStringBounds(TextForFont.apply(text), frc).width).toInt()
}
