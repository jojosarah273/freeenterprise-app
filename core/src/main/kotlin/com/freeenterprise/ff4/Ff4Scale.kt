package com.freeenterprise.ff4

import kotlin.math.max

/**
 * The FF4 layout is expressed in "SNES pixels": one unit is the short edge of the
 * display divided by 224, the height of the game's own screen. Borders, text and
 * spacing all derive from it, so the interface fills any screen while staying
 * proportional.
 *
 * The platform supplies only two facts — the short edge in pixels and the device
 * pixel density — and every other measurement in the interface follows from them
 * here. That is what lets Android, the desktop client and (later) iOS produce the
 * same geometry from the same numbers.
 */
class Ff4Scale(
    shortEdgePx: Int,
    /** Device pixels per dp. Only used for the screen-edge buffer. */
    val density: Float,
) {
    /** One SNES pixel in device px, never below 2px so a border stays visible. */
    val unit: Float = max(MIN_UNIT_PX, shortEdgePx.toFloat() / SNES_SHORT_EDGE_PX)

    val borderWidthPx: Float get() = unit
    val innerWidthPx: Float get() = unit

    /** Content inset inside a window, in px. Truncated, as it always has been. */
    val contentPaddingPx: Int get() = (unit * 3f).toInt()

    /** Menu text: an 8 SNES-pixel cell. */
    val textPx: Float get() = unit * 8f

    /** Smaller text: a 6 SNES-pixel cell. */
    val textSmallPx: Float get() = unit * 6f

    /** The game's POINTER.png beside a selected row, measured in SNES pixels. */
    val cursorWidthPx: Float get() = unit * CURSOR_WIDTH_UNITS
    val cursorHeightPx: Float get() = unit * CURSOR_HEIGHT_UNITS

    fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        /** The SNES screen is 256x224, so the short edge is 224. */
        const val SNES_SHORT_EDGE_PX = 224f

        /** Borders below two device pixels are not worth drawing. */
        const val MIN_UNIT_PX = 2f

        const val CURSOR_WIDTH_UNITS = 16f
        const val CURSOR_HEIGHT_UNITS = 13f
    }
}
