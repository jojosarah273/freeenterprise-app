package com.freeenterprise.ff4

/**
 * The chrome of one FF4 window: a solid body, a white border, and a black inner
 * bevel line, with square corners.
 *
 * This is the calculation that `FF4Window` used to perform inline while drawing.
 * It lives here so the result is one set of numbers rather than one per platform:
 * each backend performs exactly these fills and strokes, in this order, and gets
 * the same pixels. A focused window uses the game's blue body and white border;
 * an unfocused or dimmed one uses its grey pair.
 */
data class Ff4WindowChrome(
    /** Filled first: the body, inset by the border on every side. */
    val body: Ff4Rect,
    val bodyColor: Int,
    /** Stroked second, centred on the window edge so it covers the outer pixel row. */
    val border: Ff4Stroke,
    /** Stroked third: the one-pixel black bevel just inside the border. */
    val bevel: Ff4Stroke,
) {
    companion object {
        /**
         * Chrome for a window of [widthPx] by [heightPx], or null when the window
         * has no area to draw into (which happens on the first layout pass).
         */
        fun of(
            widthPx: Float,
            heightPx: Float,
            scale: Ff4Scale,
            dimmed: Boolean,
        ): Ff4WindowChrome? {
            if (widthPx <= 0f || heightPx <= 0f) return null

            val border = scale.borderWidthPx
            val inner = scale.innerWidthPx

            // The body stops at the border, the border is stroked centred on the
            // edge (so it covers 0..border exactly), and the bevel sits one inner
            // width inside that.
            val body = Ff4Rect(border, border, widthPx - border, heightPx - border)
            val borderRect = Ff4Rect(
                border / 2f,
                border / 2f,
                widthPx - border / 2f,
                heightPx - border / 2f,
            )
            val bevelOffset = border + inner / 2f
            val bevelRect = Ff4Rect(
                bevelOffset,
                bevelOffset,
                widthPx - bevelOffset,
                heightPx - bevelOffset,
            )

            return Ff4WindowChrome(
                body = body,
                bodyColor = if (dimmed) Ff4Palette.GREY_DARK else Ff4Palette.BLUE,
                border = Ff4Stroke(
                    rect = borderRect,
                    widthPx = border,
                    color = if (dimmed) Ff4Palette.GREY else Ff4Palette.WHITE,
                ),
                bevel = Ff4Stroke(
                    rect = bevelRect,
                    widthPx = inner,
                    color = Ff4Palette.BLACK,
                ),
            )
        }
    }
}
