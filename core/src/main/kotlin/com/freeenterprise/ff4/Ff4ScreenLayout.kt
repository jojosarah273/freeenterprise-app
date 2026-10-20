package com.freeenterprise.ff4

/** The three stacked boxes every FF4 screen is built from. */
data class Ff4ScreenBoxes(
    /** Top box: what the screen has to say, two lines tall. */
    val status: Ff4Rect,
    /** Middle box: the menu itself, taking whatever is left. */
    val menu: Ff4Rect,
    /** Bottom box: the control hint, one line tall. */
    val hint: Ff4Rect,
) {
    val all: List<Ff4Rect> get() = listOf(status, menu, hint)
}

/**
 * Where the boxes go on a screen.
 *
 * This is the structure the Android layouts express in XML: an edge inset (which
 * exists so a rounded screen corner cannot clip a border), a status box pinned to
 * two lines, a hint box pinned to one, and a menu box that absorbs the rest. The
 * heights are fixed rather than content-driven on purpose: if they wrapped their
 * content, the menu box would resize as text arrived, which is the "menu appears
 * at one size then reshapes" bug this layout was built to stop.
 */
object Ff4ScreenLayout {

    /** Space between two boxes. Matches the 6dp margins in the Android layouts. */
    fun gapPx(scale: Ff4Scale): Float = scale.dp(6).toFloat()

    /** Screen-edge inset for a screen whose corners are not rounded. */
    fun defaultEdgeInsetPx(scale: Ff4Scale): Float = scale.dp(8).toFloat()

    fun statusHeightPx(scale: Ff4Scale, metrics: Ff4TextMetrics): Float =
        2f * metrics.lineHeightPx(scale.textSmallPx) + 2f * scale.contentPaddingPx

    fun hintHeightPx(scale: Ff4Scale, metrics: Ff4TextMetrics): Float =
        metrics.lineHeightPx(scale.textSmallPx).toFloat() + 2f * scale.contentPaddingPx

    fun boxes(
        widthPx: Float,
        heightPx: Float,
        scale: Ff4Scale,
        metrics: Ff4TextMetrics,
        edgeInsetPx: Float = defaultEdgeInsetPx(scale),
    ): Ff4ScreenBoxes {
        val gap = gapPx(scale)
        val left = edgeInsetPx
        val right = widthPx - edgeInsetPx
        val statusHeight = statusHeightPx(scale, metrics)
        val hintHeight = hintHeightPx(scale, metrics)

        val status = Ff4Rect(left, edgeInsetPx, right, edgeInsetPx + statusHeight)
        val menuTop = status.bottom + gap
        val hintTop = heightPx - edgeInsetPx - hintHeight

        return Ff4ScreenBoxes(
            status = status,
            menu = Ff4Rect(left, menuTop, right, maxOf(menuTop, hintTop - gap)),
            hint = Ff4Rect(left, hintTop, right, hintTop + hintHeight),
        )
    }
}
