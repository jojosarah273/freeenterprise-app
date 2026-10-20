package com.freeenterprise.ff4

/**
 * The progress bar inside the generation and update overlays: a dark trough, a
 * yellow filled proportion, and the same white border an FF4 window has.
 *
 * Small, but worth sharing for the same reason as the window chrome: it is drawn
 * on every platform, and the moment two implementations exist they drift.
 */
data class Ff4ProgressBarChrome(
    /** The dark trough, inset by the border on every side. */
    val trough: Ff4Rect,
    val troughColor: Int,
    /** The filled portion of the track, or null when nothing is filled yet. */
    val fill: Ff4Rect?,
    val fillColor: Int,
    /** The white border, stroked centred on the outer edge. */
    val border: Ff4Stroke,
) {
    /** The same bar moved by ([dx], [dy]). */
    fun translated(dx: Float, dy: Float): Ff4ProgressBarChrome = Ff4ProgressBarChrome(
        trough = trough.translated(dx, dy),
        troughColor = troughColor,
        fill = fill?.translated(dx, dy),
        fillColor = fillColor,
        border = Ff4Stroke(border.rect.translated(dx, dy), border.widthPx, border.color),
    )

    companion object {
        /** The trough colour the bar has always used. */
        const val TROUGH_COLOR = 0xFF000050.toInt()

        /** Height a bar asks for when the layout lets it choose, in SNES pixels. */
        const val WANTED_HEIGHT_UNITS = 10f

        fun of(
            widthPx: Float,
            heightPx: Float,
            scale: Ff4Scale,
            progressPercent: Int,
        ): Ff4ProgressBarChrome? {
            if (widthPx <= 0f || heightPx <= 0f) return null

            val border = scale.borderWidthPx
            val trough = Ff4Rect(border, border, widthPx - border, heightPx - border)

            // The track is inset by two borders from the bar's edge. Written as
            // `border * 2f` rather than as an inset of the trough on purpose: it is
            // what the view computed inline, and the two forms differ in the last
            // float bits, which is enough to move a rounded pixel. The parity test
            // in this module caught exactly that.
            val left = border * 2f
            val top = border * 2f
            val right = widthPx - border * 2f
            val bottom = heightPx - border * 2f
            val innerWidth = right - left
            val fill = if (innerWidth > 0f) {
                val proportion = progressPercent.coerceIn(0, 100) / 100f
                val filled = innerWidth * proportion
                if (filled > 0f) Ff4Rect(left, top, left + filled, bottom) else null
            } else {
                null
            }

            return Ff4ProgressBarChrome(
                trough = trough,
                troughColor = TROUGH_COLOR,
                fill = fill,
                fillColor = Ff4Palette.YELLOW,
                border = Ff4Stroke(
                    rect = Ff4Rect(
                        border / 2f,
                        border / 2f,
                        widthPx - border / 2f,
                        heightPx - border / 2f,
                    ),
                    widthPx = border,
                    color = Ff4Palette.WHITE,
                ),
            )
        }
    }
}
