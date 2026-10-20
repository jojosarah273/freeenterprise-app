package com.freeenterprise.desktop

import com.freeenterprise.ff4.Ff4DrawList
import com.freeenterprise.ff4.Ff4KeyItems
import com.freeenterprise.ff4.Ff4MenuItem
import com.freeenterprise.ff4.Ff4MenuLayout
import com.freeenterprise.ff4.Ff4Palette
import com.freeenterprise.ff4.Ff4ProgressBarChrome
import com.freeenterprise.ff4.Ff4Rect
import com.freeenterprise.ff4.Ff4Scale
import com.freeenterprise.ff4.Ff4ScreenLayout
import com.freeenterprise.ff4.Ff4WindowChrome
import com.freeenterprise.ff4.emitTo

/**
 * Builds the draw lists for the desktop build's screens.
 *
 * The structure — three boxes, fixed status and hint heights, a menu that absorbs
 * what is left — comes from [Ff4ScreenLayout] in the core, so it is the same
 * structure the Android app lays out in XML. Only the content differs per screen.
 */
class ScreenRenderer(
    private val scale: Ff4Scale,
    private val metrics: AwtTextMetrics,
) {
    private val pad = scale.contentPaddingPx.toFloat()

    fun mainMenu(
        width: Int,
        height: Int,
        items: List<Ff4MenuItem>,
        selected: Int,
        status: List<String>,
        hint: String,
    ): Ff4DrawList {
        val list = Ff4DrawList()
        val boxes = Ff4ScreenLayout.boxes(width.toFloat(), height.toFloat(), scale, metrics)

        for (box in boxes.all) Ff4WindowChrome.of(box, scale, dimmed = false)?.emitTo(list)

        var statusBaseline = boxes.status.top + pad + metrics.ascentPx(scale.textSmallPx)
        for (line in status) {
            list.text(line, boxes.status.left + pad, statusBaseline, scale.textSmallPx, Ff4Palette.WHITE)
            statusBaseline += metrics.lineHeightPx(scale.textSmallPx)
        }

        list.text(
            hint,
            boxes.hint.left + pad,
            boxes.hint.top + pad + metrics.ascentPx(scale.textSmallPx),
            scale.textSmallPx,
            Ff4Palette.WHITE,
        )

        val inner = boxes.menu.inset(pad)
        Ff4MenuLayout.emit(
            items = items,
            scale = scale,
            metrics = metrics,
            widthPx = inner.width,
            selected = selected,
            topPx = inner.top,
            leftPx = inner.left,
            list = list,
        )
        return list
    }

    private fun centreBox(width: Int, height: Int, boxWidth: Float, boxHeight: Float): Ff4Rect =
        Ff4Rect(
            (width - boxWidth) / 2f,
            (height - boxHeight) / 2f,
            (width + boxWidth) / 2f,
            (height + boxHeight) / 2f,
        )

    /** A paused-game menu: the same list widget, in a window over the picture. */
    fun pauseMenu(
        width: Int,
        height: Int,
        items: List<Ff4MenuItem>,
        selected: Int,
        heading: String,
    ): Ff4DrawList {
        val list = Ff4DrawList()
        val line = metrics.lineHeightPx(scale.textSmallPx).toFloat()
        val boxWidth = width * 0.6f
        val boxHeight = Ff4MenuLayout.contentHeightPx(items, scale, metrics) +
            pad * 2 + line + scale.unit * 2
        val box = centreBox(width, height, boxWidth, boxHeight)
        Ff4WindowChrome.of(box, scale, dimmed = false)?.emitTo(list)

        list.text(
            heading,
            box.left + pad,
            box.top + pad + metrics.ascentPx(scale.textSmallPx),
            scale.textSmallPx,
            Ff4Palette.WHITE,
        )
        val inner = Ff4Rect(
            box.left + pad,
            box.top + pad + line + scale.unit * 2,
            box.right - pad,
            box.bottom - pad,
        )
        Ff4MenuLayout.emit(
            items, scale, metrics, inner.width, selected,
            topPx = inner.top, leftPx = inner.left, list = list,
        )
        return list
    }

    /** The tracker, over the game: what the engine says has been checked. */
    fun tracker(width: Int, height: Int, wram: ByteArray): Ff4DrawList {
        val list = Ff4DrawList()
        val line = metrics.lineHeightPx(scale.textSmallPx).toFloat()

        val heading = "KEY ITEM CHECKS  ${Ff4KeyItems.foundCount(wram)} / ${Ff4KeyItems.count}"
        val itemLines = Ff4KeyItems.entries.map { entry ->
            "${if (Ff4KeyItems.isFound(wram, entry.index)) "*" else "-"} ${entry.label}"
        }
        val footer = "treasure switches  ${Ff4KeyItems.switchesSet(wram)} / ${Ff4KeyItems.SWITCH_BITS}"
        val lines = listOf(heading) + itemLines + listOf("", footer)

        // Widest line wins: a fraction of the screen clipped the longest item names
        // when the screen was small, and a fractional width would clip them again on
        // a narrow display.
        val widest = lines.maxOf { metrics.widthPx(it, scale.textSmallPx) }
        val boxWidth = minOf(
            (widest + pad * 2).toFloat(),
            width - Ff4ScreenLayout.defaultEdgeInsetPx(scale) * 2,
        )
        val boxHeight = pad * 2 + line * (lines.size + 1)
        val box = centreBox(width, height, boxWidth, boxHeight)
        Ff4WindowChrome.of(box, scale, dimmed = false)?.emitTo(list)

        var y = box.top + pad + metrics.ascentPx(scale.textSmallPx)
        for ((index, text) in lines.withIndex()) {
            if (text.isNotEmpty()) {
                list.text(
                    text, box.left + pad, y, scale.textSmallPx,
                    when (index) {
                        0 -> Ff4Palette.YELLOW
                        lines.size - 1 -> Ff4Palette.PEACH
                        else -> if (itemLines.getOrNull(index - 1)?.startsWith("*") == true) {
                            Ff4Palette.WHITE
                        } else {
                            Ff4Palette.GREY
                        }
                    },
                )
            }
            y += line
        }
        return list
    }

    /** The modal window shown while a seed is generated or an engine is updated. */
    fun progressOverlay(
        width: Int,
        height: Int,
        heading: String,
        progress: Int,
        stage: String,
        elapsed: String,
    ): Ff4DrawList {
        val list = Ff4DrawList()
        val margin = scale.unit * 10f
        val barHeight = scale.unit * Ff4ProgressBarChrome.WANTED_HEIGHT_UNITS

        val line = metrics.lineHeightPx(scale.textSmallPx).toFloat()
        val ascent = metrics.ascentPx(scale.textSmallPx).toFloat()
        val gapBeforeBar = scale.unit * 3f
        val gapAfterBar = scale.unit * 2f

        // The box height is derived from exactly the terms that place the content:
        // heading, gap, the bar, gap, detail line, elapsed line. Computing it with
        // different arithmetic is how the elapsed line first ended up outside the
        // window, drawn over its bottom border.
        val contentHeight = line + gapBeforeBar + barHeight + gapAfterBar + line + line
        val boxHeight = contentHeight + pad * 2
        val top = (height - boxHeight) / 2f
        val box = Ff4Rect(margin, top, width - margin, top + boxHeight)
        Ff4WindowChrome.of(box, scale, dimmed = false)?.emitTo(list)

        val textX = box.left + pad
        val textWidth = box.width - pad * 2

        var y = box.top + pad
        list.text(heading, textX, y + ascent, scale.textSmallPx, Ff4Palette.WHITE)
        y += line + gapBeforeBar

        Ff4ProgressBarChrome.of(textWidth, barHeight, scale, progress)
            ?.translated(textX, y)
            ?.emitTo(list)
        y += barHeight + gapAfterBar

        list.text("$stage  \u00b7  $progress%", textX, y + ascent, scale.textSmallPx, Ff4Palette.YELLOW)
        y += line
        list.text("Elapsed $elapsed", textX, y + ascent, scale.textSmallPx, Ff4Palette.GREY)
        return list
    }
}

/**
 * Where the game picture goes: the whole screen, aspect ratio preserved, centred,
 * leaving black bars rather than stretching SNES pixels.
 *
 * Top level because it is pure arithmetic that depends on nothing in the renderer.
 */
fun gameRect(screenWidth: Int, screenHeight: Int, gameWidth: Int, gameHeight: Int): Ff4Rect {
    val scale = minOf(
        screenWidth.toFloat() / gameWidth,
        screenHeight.toFloat() / gameHeight,
    )
    val w = gameWidth * scale
    val h = gameHeight * scale
    return Ff4Rect(
        (screenWidth - w) / 2f,
        (screenHeight - h) / 2f,
        (screenWidth + w) / 2f,
        (screenHeight + h) / 2f,
    )
}
