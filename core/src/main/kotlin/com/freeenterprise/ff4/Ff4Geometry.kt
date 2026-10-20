package com.freeenterprise.ff4

/**
 * An axis-aligned rectangle in device pixels.
 *
 * Coordinates are deliberately floating point: the FF4 layout derives from a
 * fractional unit (short edge / 224), and rounding it early would change the
 * pixel output that the interface already ships. Backends rasterise these rects
 * exactly the way Android's Canvas does today.
 */
data class Ff4Rect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun inset(amount: Float): Ff4Rect =
        Ff4Rect(left + amount, top + amount, right - amount, bottom - amount)

    fun translated(dx: Float, dy: Float): Ff4Rect =
        Ff4Rect(left + dx, top + dy, right + dx, bottom + dy)

    /**
     * The rect covered by stroking this rectangle with [widthPx], centred on the
     * path, which is what a stroke does on every platform we target.
     */
    fun stroked(widthPx: Float): Ff4Rect {
        val half = widthPx / 2f
        return Ff4Rect(left - half, top - half, right + half, bottom + half)
    }
}

/** A rectangle to fill, or to stroke with [widthPx], in [color]. */
data class Ff4Stroke(
    val rect: Ff4Rect,
    val widthPx: Float,
    val color: Int,
)
