package com.freeenterprise.ff4

/**
 * The FF4 palette, taken from the game's own window palette (FF4_ASMTOC
 * `window_pal.bin`).
 *
 * Values are ARGB ints so every backend can use them directly, whether that is
 * Android's Canvas, an OpenGL clear colour, or a CoreGraphics fill.
 */
object Ff4Palette {
    const val BLUE = 0xFF000083.toInt()          // window body
    const val BLUE_SELECTED = 0xFF1B1B9E.toInt() // selected menu row
    const val WHITE = 0xFFFFFFFF.toInt()         // window border, menu text
    const val BLACK = 0xFF000000.toInt()         // inner bevel, backdrop
    const val GREY = 0xFF7B7B7B.toInt()          // dimmed border, disabled text
    const val GREY_DARK = 0xFF414141.toInt()     // dimmed body
    const val GREEN = 0xFF00A400.toInt()
    const val YELLOW = 0xFFFFDE00.toInt()        // menu values
    const val PINK = 0xFFFF3983.toInt()          // errors
    const val PEACH = 0xFFFF9C5A.toInt()
}

/** One line in a screen's status box, with its own colour. */
data class Ff4StatusLine(val text: String, val color: Int = Ff4Palette.WHITE)
