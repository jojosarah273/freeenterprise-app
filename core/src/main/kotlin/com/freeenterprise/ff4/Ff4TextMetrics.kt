package com.freeenterprise.ff4

/**
 * Text measurement, supplied by the platform.
 *
 * The interface's geometry depends on text: a row's height, whether a status line
 * fits its two-line slot, where a value is right-aligned. If two platforms answer
 * these questions differently, the boxes differ and the layout drifts, which is
 * exactly the class of bug (menus appearing at one size and then resizing) that
 * this module exists to make impossible.
 *
 * So the rule is: the platform must answer with the numbers it will actually
 * render with. On Android that is the same `Paint` used for drawing; on the
 * desktop it will be the same font at the same pixel size. [Ff4TextMetrics.Empty]
 * exists for tests and for headless rendering, so layout can be exercised without
 * a graphics stack.
 */
interface Ff4TextMetrics {

    /** Height of one line box at [textPx]: ceil(descent - ascent). */
    fun lineHeightPx(textPx: Float): Int

    /** Advance width of [text] at [textPx]. */
    fun widthPx(text: CharSequence, textPx: Float): Int

    /** Distance from the top of the line box down to the baseline at [textPx]. */
    fun ascentPx(textPx: Float): Int

    /**
     * A deterministic stand-in: every glyph is half its pixel size wide, and a
     * line box is the pixel size itself. Useful for tests and for any headless
     * path that only needs stable numbers, never for shipping layout.
     */
    object Empty : Ff4TextMetrics {
        override fun lineHeightPx(textPx: Float): Int = textPx.toInt()
        override fun widthPx(text: CharSequence, textPx: Float): Int =
            (text.length * textPx * 0.5f).toInt()
        override fun ascentPx(textPx: Float): Int = (textPx * 0.75f).toInt()
    }
}
