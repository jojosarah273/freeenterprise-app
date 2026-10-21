package com.freeenterprise.desktop

import com.freeenterprise.ff4.Ff4Checks
import com.freeenterprise.ff4.Ff4DrawList
import com.freeenterprise.ff4.Ff4KeyItems
import com.freeenterprise.ff4.Ff4MenuItem
import com.freeenterprise.ff4.Ff4MenuLayout
import com.freeenterprise.ff4.Ff4Palette
import com.freeenterprise.ff4.Ff4ProgressBarChrome
import com.freeenterprise.ff4.Ff4Rect
import com.freeenterprise.ff4.Ff4Scale
import com.freeenterprise.ff4.Ff4Spoiler
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
/** About 80% black: the menus behind stay readable but obviously inactive. */
private const val SCRIM_COLOR = 0xCC000000.toInt()

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
        status: List<com.freeenterprise.ff4.Ff4StatusLine>,
        hint: String,
    ): Ff4DrawList {
        val list = Ff4DrawList()
        val boxes = Ff4ScreenLayout.boxes(width.toFloat(), height.toFloat(), scale, metrics)

        for (box in boxes.all) Ff4WindowChrome.of(box, scale, dimmed = false)?.emitTo(list)

        var statusBaseline = boxes.status.top + pad + metrics.ascentPx(scale.textSmallPx)
        val statusRoom = boxes.status.width - pad * 2
        for (line in status) {
            // A long flagset must not run out of its window: the app's status
            // TextViews ellipsize, and so does this.
            list.text(
                Ff4MenuLayout.ellipsize(line.text, statusRoom, scale.textSmallPx, metrics),
                boxes.status.left + pad, statusBaseline, scale.textSmallPx, line.color,
            )
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
        Ff4MenuLayout.emitScrolled(
            items = items,
            scale = scale,
            metrics = metrics,
            widthPx = inner.width,
            selected = selected,
            topPx = inner.top,
            leftPx = inner.left,
            maxHeightPx = inner.height,
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

    /**
     * The controls, in a window along the bottom.
     *
     * The app has always had this box; the desktop build went without it, which left
     * the in-game shortcuts undiscoverable - you had to read the README to know that
     * Tab opens the tracker. Same height as the app's hint box, from the same core
     * layout, so the two interfaces agree.
     */
    fun hintBar(width: Int, height: Int, text: String): Ff4DrawList = hintBox(width, height, listOf(text))

    /**
     * The hint box, holding one to three lines - the app's editor uses three (the
     * control's title and flag, its description, and the keys), and the box grows to
     * fit rather than letting the text run out of it.
     */
    fun hintBox(width: Int, height: Int, lines: List<String>): Ff4DrawList {
        val list = Ff4DrawList()
        val inset = Ff4ScreenLayout.defaultEdgeInsetPx(scale)
        val lineHeight = metrics.lineHeightPx(scale.textSmallPx)
        val shown = lines.filter { it.isNotBlank() }.take(3)
        val boxHeight = (lineHeight * maxOf(1, shown.size) + scale.contentPaddingPx * 2).toFloat()
        val box = Ff4Rect(inset, height - inset - boxHeight, width - inset, height - inset)
        Ff4WindowChrome.of(box, scale, dimmed = false)?.emitTo(list)

        // Trim to what fits, so nothing runs past the window.
        val available = box.width - pad * 2
        var y = box.top + pad + metrics.ascentPx(scale.textSmallPx)
        for (line in shown) {
            list.text(fit(line, available), box.left + pad, y, scale.textSmallPx, Ff4Palette.WHITE)
            y += lineHeight
        }
        return list
    }

    /** Truncate [text] so its rendered width fits [available]. */
    private fun fit(text: String, available: Float): String {
        if (metrics.widthPx(text, scale.textSmallPx) <= available) return text
        var end = text.length
        while (end > 1 && metrics.widthPx(text.substring(0, end) + "…", scale.textSmallPx) > available) end--
        return text.substring(0, end) + "..."
    }

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

    /**
     * The dimming layer behind a modal window: translucent black, so whatever is
     * underneath stays visible but clearly out of play - the same idea as the overlay
     * the app dims its menus with.
     */
    fun scrim(width: Int, height: Int): Ff4DrawList {
        val list = Ff4DrawList()
        list.fill(Ff4Rect(0f, 0f, width.toFloat(), height.toFloat()), 0xCC000000.toInt())
        return list
    }

    /**
     * The tracker, over the game: two pages, as the app has.
     *
     * Page one lists the seed's key-item *locations* with their check state - never
     * which item is there, so the page cannot spoil the seed. Page two counts the
     * treasure checks by world and area. The locations come from the spoiler beside
     * the ROM; without one, the page falls back to listing the engine's items.
     */
    fun tracker(
        width: Int,
        height: Int,
        page: Int,
        wram: ByteArray,
        locations: List<Pair<String, String>>,
        checks: List<Ff4Checks.Check>,
        seen: Set<String>,
    ): Ff4DrawList {
        val list = Ff4DrawList()
        val line = metrics.lineHeightPx(scale.textSmallPx).toFloat()
        val lines = if (page == 0) keyItemLines(wram, locations, seen) else checkLines(wram, checks)

        val widest = lines.maxOf { metrics.widthPx(it.text, scale.textSmallPx) }
        val boxWidth = minOf(
            (widest + pad * 2).toFloat(),
            width - Ff4ScreenLayout.defaultEdgeInsetPx(scale) * 2,
        )
        val boxHeight = pad * 2 + line * (lines.size + 1)
        val box = centreBox(width, height, boxWidth, boxHeight)
        Ff4WindowChrome.of(box, scale, dimmed = false)?.emitTo(list)

        var y = box.top + pad + metrics.ascentPx(scale.textSmallPx)
        for (row in lines) {
            list.text(row.text, box.left + pad, y, scale.textSmallPx, row.color)
            y += line
        }
        return list
    }

    private data class TrackerLine(val text: String, val color: Int)

    private fun keyItemLines(
        wram: ByteArray,
        locations: List<Pair<String, String>>,
        seen: Set<String>,
    ): List<TrackerLine> {
        val out = mutableListOf<TrackerLine>()
        out += TrackerLine("PAGE 1 / 2   KEY ITEM CHECKS", Ff4Palette.WHITE)
        out += TrackerLine("", Ff4Palette.WHITE)

        if (locations.isEmpty()) {
            out += TrackerLine("This seed has no key-item data saved.", Ff4Palette.YELLOW)
            out += TrackerLine("(Regenerate the seed, or drop a", Ff4Palette.YELLOW)
            out += TrackerLine(" .spoiler.txt beside the ROM.)", Ff4Palette.YELLOW)
            return out
        }

        val held = Ff4KeyItems.entries.filter { Ff4KeyItems.isFound(wram, it.index) }
            .map { it.name }.toSet()
        var checked = 0
        for ((item, place) in locations) {
            val entry = Ff4KeyItems.entryFor(item)
            // The engine's "have I been there" flag decides it. The others only cover
            // what it does not track: the free starting item, and an item already held
            // or seen (the bag drops traded items).
            val done = (entry != null && Ff4KeyItems.isFound(wram, entry.index)) ||
                Ff4Spoiler.isStartingItem(place) ||
                Ff4KeyItems.normalise(item) in seen ||
                Ff4KeyItems.normalise(item) in held
            if (done) checked++
            out += TrackerLine(
                "${if (done) "*" else "-"} ${place.take(38)}",
                if (done) Ff4Palette.WHITE else Ff4Palette.GREY,
            )
        }
        out += TrackerLine("", Ff4Palette.WHITE)
        out += TrackerLine(" $checked / ${locations.size} checked", Ff4Palette.WHITE)
        out += TrackerLine(" * = check done.", Ff4Palette.GREY)
        out += TrackerLine(" Where each item is stays in the spoiler.", Ff4Palette.GREY)
        return out
    }

    private fun checkLines(wram: ByteArray, checks: List<Ff4Checks.Check>): List<TrackerLine> {
        val out = mutableListOf<TrackerLine>()
        out += TrackerLine("PAGE 2 / 2   CHECKS", Ff4Palette.WHITE)
        out += TrackerLine("", Ff4Palette.WHITE)
        if (checks.isEmpty()) {
            out += TrackerLine("(no checks database found)", Ff4Palette.GREY)
            out += TrackerLine("switches: ${Ff4KeyItems.switchesSet(wram)} / ${Ff4KeyItems.SWITCH_BITS}", Ff4Palette.PEACH)
            return out
        }
        val progress = Ff4Checks.progress(checks, wram, Ff4KeyItems.SWITCHES_OFFSET)
        out += TrackerLine("TOTAL   ${progress.opened} / ${progress.total}", Ff4Palette.WHITE)
        for (world in listOf("Overworld", "Underworld", "Moon")) {
            val (opened, total) = progress.byWorld[world] ?: continue
            out += TrackerLine("  %-11s %3d / %3d".format(world, opened, total), Ff4Palette.WHITE)
        }
        out += TrackerLine("", Ff4Palette.WHITE)
        out += TrackerLine("BY AREA  (most remaining first)", Ff4Palette.PEACH)
        for ((area, opened, total) in progress.byArea.take(14)) {
            out += TrackerLine("  %-22s %3d / %3d".format(area.take(22), opened, total), Ff4Palette.GREY)
        }
        return out
    }

    /**
     * A modal text field: the prompt, the value being typed, and a caret. The app
     * asks for names this way, so the desktop does too rather than inventing them.
     */
    fun textInput(width: Int, height: Int, title: String, prompt: String, value: String): Ff4DrawList {
        val list = Ff4DrawList()
        val line = metrics.lineHeightPx(scale.textSmallPx).toFloat()
        val ascent = metrics.ascentPx(scale.textSmallPx).toFloat()
        val boxWidth = minOf((width * 0.7f), width - Ff4ScreenLayout.defaultEdgeInsetPx(scale) * 2)
        // Four lines: title, prompt, the value, and the keys.
        val boxHeight = pad * 2 + line * 4 + scale.unit * 2
        val box = centreBox(width, height, boxWidth, boxHeight)
        Ff4WindowChrome.of(box, scale, dimmed = false)?.emitTo(list)

        var y = box.top + pad
        list.text(title, box.left + pad, y + ascent, scale.textSmallPx, Ff4Palette.YELLOW)
        y += line
        list.text(prompt, box.left + pad, y + ascent, scale.textSmallPx, Ff4Palette.WHITE)
        y += line + scale.unit
        list.text(value + "_", box.left + pad, y + ascent, scale.textSmallPx, Ff4Palette.YELLOW)
        y += line
        list.text("Enter  Accept      Esc  Cancel", box.left + pad, y + ascent, scale.textSmallPx, Ff4Palette.GREY)
        return list
    }

    /**
     * A question with a couple of answers, in an FF4 window - used for leaving the
     * app, resetting the core and exiting to sessions, as the app does.
     */
    fun confirmDialog(
        width: Int,
        height: Int,
        title: String,
        message: String,
        options: List<String>,
        selected: Int,
    ): Ff4DrawList {
        val list = Ff4DrawList()
        val line = metrics.lineHeightPx(scale.textSmallPx).toFloat()
        val ascent = metrics.ascentPx(scale.textSmallPx).toFloat()
        val messageLines = message.split("\n")
        val rows = options.map { Ff4MenuItem(it) }

        val widest = maxOf(
            metrics.widthPx(title, scale.textSmallPx),
            messageLines.maxOf { metrics.widthPx(it, scale.textSmallPx) },
            options.maxOf { metrics.widthPx(it, scale.textSmallPx) },
        )
        val boxWidth = minOf(
            (widest + pad * 2).toFloat(),
            width - Ff4ScreenLayout.defaultEdgeInsetPx(scale) * 2,
        )
        val menuHeight = Ff4MenuLayout.contentHeightPx(rows, scale, metrics)
        // Derived from the terms used to place the content below.
        val boxHeight = pad + ascent + line * (messageLines.size + 1) + menuHeight + pad
        val box = centreBox(width, height, boxWidth, boxHeight)
        Ff4WindowChrome.of(box, scale, dimmed = false)?.emitTo(list)

        var y = box.top + pad
        list.text(title, box.left + pad, y + ascent, scale.textSmallPx, Ff4Palette.YELLOW)
        y += line
        for (part in messageLines) {
            list.text(part, box.left + pad, y + ascent, scale.textSmallPx, Ff4Palette.WHITE)
            y += line
        }
        Ff4MenuLayout.emit(
            rows, scale, metrics, box.width - pad * 2, selected,
            topPx = y, leftPx = box.left + pad, list = list,
        )
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
