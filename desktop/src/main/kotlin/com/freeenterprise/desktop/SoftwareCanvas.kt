package com.freeenterprise.desktop

import com.freeenterprise.ff4.Ff4DrawList
import com.freeenterprise.ff4.Ff4Op
import com.freeenterprise.ff4.Ff4Palette
import com.freeenterprise.ff4.Ff4Rect
import com.freeenterprise.ff4.Ff4TextAlign
import com.freeenterprise.ff4.Ff4TextRun
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Rasterises an [Ff4DrawList] into an image.
 *
 * One renderer serves both the window and the headless PNG export, so what you see
 * on screen is exactly what can be inspected from a test run. Anti-aliasing and
 * fractional metrics are off, which is what gives the FF4 look its hard pixel
 * edges, and text is drawn twice to reproduce the drop shadow the app's theme
 * applies.
 */
class SoftwareCanvas(
    val width: Int,
    val height: Int,
    private val metrics: AwtTextMetrics,
    private val shadowOffsetPx: Float,
) {
    val image: BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    private val g = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
        setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
    }

    fun clear(color: Int = Ff4Palette.BLACK) {
        g.color = Color(color, true)
        g.fillRect(0, 0, width, height)
    }

    fun draw(list: Ff4DrawList) {
        for (op in list.ops()) {
            when (op) {
                is Ff4Op.Fill -> fill(op.rect, op.color)
                is Ff4Op.Stroke -> stroke(op.rect, op.widthPx, op.color)
                is Ff4Op.Text -> text(op.run)
                is Ff4Op.Sprite -> sprite(op)
            }
        }
    }

    fun writePng(file: File) {
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
    }

    private fun fill(rect: Ff4Rect, color: Int) {
        val left = rect.left.roundToInt()
        val top = rect.top.roundToInt()
        val w = rect.right.roundToInt() - left
        val h = rect.bottom.roundToInt() - top
        if (w <= 0 || h <= 0) return
        g.color = Color(color, true)
        g.fillRect(left, top, w, h)
    }

    /**
     * A stroke is centred on the path, as it is on every platform we target, so the
     * band runs half outside and half inside the rectangle. Drawn as four filled
     * bands, which is exactly what a stroked rectangle is.
     */
    private fun stroke(rect: Ff4Rect, widthPx: Float, color: Int) {
        val outer = rect.stroked(widthPx)
        val inner = rect.inset(widthPx / 2f)
        fill(Ff4Rect(outer.left, outer.top, outer.right, inner.top), color)
        fill(Ff4Rect(outer.left, inner.bottom, outer.right, outer.bottom), color)
        fill(Ff4Rect(outer.left, inner.top, inner.left, inner.bottom), color)
        fill(Ff4Rect(inner.right, inner.top, outer.right, inner.bottom), color)
    }

    private fun text(run: Ff4TextRun) {
        // Same substitution the measurer used, so positions match the layout.
        val text = TextForFont.apply(run.text)
        g.font = metrics.font(run.sizePx)
        val advance = metrics.widthPx(run.text, run.sizePx)
        val x = when (run.align) {
            Ff4TextAlign.LEFT -> run.x
            Ff4TextAlign.RIGHT -> run.x - advance
            Ff4TextAlign.CENTER -> run.x - advance / 2f
        }
        if (shadowOffsetPx > 0f) {
            g.color = Color(0, 0, 0, 255)
            g.drawString(text, x + shadowOffsetPx, run.baselineY + shadowOffsetPx)
        }
        g.color = Color(run.color, true)
        g.drawString(text, x, run.baselineY)
    }

    private fun sprite(op: Ff4Op.Sprite) {
        val image = when (op.sprite) {
            com.freeenterprise.ff4.Ff4Sprite.CURSOR -> Assets.cursor
        }
        g.drawImage(
            image,
            op.rect.left.roundToInt(),
            op.rect.top.roundToInt(),
            op.rect.width.roundToInt(),
            op.rect.height.roundToInt(),
            null,
        )
    }
}
