package com.freeenterprise.ff4

/**
 * The interface as a list of drawing commands.
 *
 * The menus decide *what* to draw; a platform backend decides how to rasterise it.
 * That is the whole portability trick: one implementation of the interface, and a
 * thin renderer per platform (Android's Canvas today, the desktop's software
 * canvas and OpenGL next, Metal later if it comes to that).
 *
 * Commands are deliberately primitive: filled rectangles, stroked rectangles,
 * positioned text and sprites. That is all the FF4 interface has ever used, and
 * every platform can draw them the same way without a UI toolkit in between.
 */
sealed interface Ff4Op {

    /** Fill [rect] with [color]. */
    data class Fill(val rect: Ff4Rect, val color: Int) : Ff4Op

    /** Stroke [rect] with [widthPx], centred on the path, in [color]. */
    data class Stroke(val rect: Ff4Rect, val widthPx: Float, val color: Int) : Ff4Op

    /** Draw [run] at its baseline. */
    data class Text(val run: Ff4TextRun) : Ff4Op

    /** Draw [sprite] scaled into [rect]. */
    data class Sprite(val sprite: Ff4Sprite, val rect: Ff4Rect) : Ff4Op
}

/** Horizontal anchoring for a text run. */
enum class Ff4TextAlign { LEFT, RIGHT, CENTER }

/**
 * One string, positioned at its baseline so every backend places glyphs the same
 * way regardless of how it measures line boxes.
 */
data class Ff4TextRun(
    val text: String,
    /** Left edge, right edge or centre depending on [align]. */
    val x: Float,
    val baselineY: Float,
    val sizePx: Float,
    val color: Int,
    val align: Ff4TextAlign = Ff4TextAlign.LEFT,
)

/** Images the interface draws itself, rather than with text or rectangles. */
enum class Ff4Sprite {
    /** The game's own POINTER.png, beside the selected menu row. */
    CURSOR,
}

/** Accumulates ops in drawing order. */
class Ff4DrawList {
    private val ops = mutableListOf<Ff4Op>()

    val size: Int get() = ops.size
    fun ops(): List<Ff4Op> = ops
    fun clear() = ops.clear()

    fun fill(rect: Ff4Rect, color: Int) { ops.add(Ff4Op.Fill(rect, color)) }

    fun stroke(rect: Ff4Rect, widthPx: Float, color: Int) {
        ops.add(Ff4Op.Stroke(rect, widthPx, color))
    }

    fun text(
        text: String,
        x: Float,
        baselineY: Float,
        sizePx: Float,
        color: Int,
        align: Ff4TextAlign = Ff4TextAlign.LEFT,
    ) {
        if (text.isEmpty()) return
        ops.add(Ff4Op.Text(Ff4TextRun(text, x, baselineY, sizePx, color, align)))
    }

    fun sprite(sprite: Ff4Sprite, rect: Ff4Rect) { ops.add(Ff4Op.Sprite(sprite, rect)) }
}

/** A window's chrome, expressed as ops. Draw order is body, border, bevel. */
fun Ff4WindowChrome.emitTo(list: Ff4DrawList) {
    list.fill(body, bodyColor)
    list.stroke(border.rect, border.widthPx, border.color)
    list.stroke(bevel.rect, bevel.widthPx, bevel.color)
}

/** A progress bar, expressed as ops. Draw order is trough, fill, border. */
fun Ff4ProgressBarChrome.emitTo(list: Ff4DrawList) {
    list.fill(trough, troughColor)
    fill?.let { list.fill(it, fillColor) }
    list.stroke(border.rect, border.widthPx, border.color)
}
