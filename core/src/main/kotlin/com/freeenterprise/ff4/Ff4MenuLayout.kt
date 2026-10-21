package com.freeenterprise.ff4

/**
 * Row geometry for the FF4 list menu.
 *
 * These are the rules the Android views apply, lifted out unchanged: a row is
 * padded by two units top and bottom, the selection cursor is the game's 16x13
 * pointer with a one-unit gap after it, the label column absorbs the slack and the
 * value hugs the right edge. Row height comes from the cursor box or the text,
 * whichever is taller, which is why exact text metrics matter for the layout.
 */
data class Ff4MenuRow(
    val index: Int,
    /** Full row bounds, including the selected-row highlight. */
    val bounds: Ff4Rect,
    val selected: Boolean,
    /** Where the selection cursor goes, or null when this row is not selected. */
    val cursor: Ff4Rect?,
    val label: Ff4TextRun,
    /** Right-aligned value, when the row has one. */
    val value: Ff4TextRun?,
    val subtitle: Ff4TextRun?,
)

object Ff4MenuLayout {

    /** Horizontal padding before a row's content, allowing for its indent. */
    fun startPaddingPx(scale: Ff4Scale, indent: Int): Float =
        (scale.unit * (2f + indent * 3f)).toInt().toFloat()

    /** Padding on the other three sides of every row. */
    fun edgePaddingPx(scale: Ff4Scale): Float = (scale.unit * 2f).toInt().toFloat()

    fun cursorWidthPx(scale: Ff4Scale): Float = (scale.unit * Ff4Scale.CURSOR_WIDTH_UNITS).toInt().toFloat()

    fun cursorHeightPx(scale: Ff4Scale): Float = (scale.unit * Ff4Scale.CURSOR_HEIGHT_UNITS).toInt().toFloat()

    /** Gap between the cursor and the label. */
    fun cursorGapPx(scale: Ff4Scale): Float = scale.unit.toInt().toFloat()

    private fun textBlockHeightPx(item: Ff4MenuItem, scale: Ff4Scale, metrics: Ff4TextMetrics): Float {
        var height = metrics.lineHeightPx(scale.textPx).toFloat()
        if (!item.subtitle.isNullOrBlank()) height += metrics.lineHeightPx(scale.textSmallPx)
        return height
    }

    /** Height of one row, in px. */
    fun rowHeightPx(item: Ff4MenuItem, scale: Ff4Scale, metrics: Ff4TextMetrics): Float {
        val pad = edgePaddingPx(scale)
        val content = maxOf(cursorHeightPx(scale), textBlockHeightPx(item, scale, metrics))
        return pad + content + pad
    }

    /** Total height of a menu's rows, in px. */
    fun contentHeightPx(
        items: List<Ff4MenuItem>,
        scale: Ff4Scale,
        metrics: Ff4TextMetrics,
    ): Float = items.fold(0f) { acc, item -> acc + rowHeightPx(item, scale, metrics) }

    /**
     * Lay out every row. [widthPx] is the width available to the menu, [selected]
     * is the current row, and [topPx] shifts the whole list (for scrolling).
     */
    fun rows(
        items: List<Ff4MenuItem>,
        scale: Ff4Scale,
        metrics: Ff4TextMetrics,
        widthPx: Float,
        selected: Int,
        topPx: Float = 0f,
        leftPx: Float = 0f,
    ): List<Ff4MenuRow> {
        val out = ArrayList<Ff4MenuRow>(items.size)
        val pad = edgePaddingPx(scale)
        val cursorGap = cursorGapPx(scale)
        val cursorW = cursorWidthPx(scale)
        val cursorH = cursorHeightPx(scale)
        var y = topPx

        for ((index, item) in items.withIndex()) {
            val height = rowHeightPx(item, scale, metrics)
            val bounds = Ff4Rect(leftPx, y, leftPx + widthPx, y + height)

            val textBlock = textBlockHeightPx(item, scale, metrics)
            val content = maxOf(cursorH, textBlock)
            val blockTop = y + pad + (content - textBlock) / 2f

            val labelSize = scale.textPx
            val labelX = leftPx + startPaddingPx(scale, item.indent) + cursorW + cursorGap
            val isSelected = index == selected
            val labelColor = if (item.enabled) Ff4Palette.WHITE else Ff4Palette.GREY

            // Trim the label so it cannot run into the right-aligned value. The app's
            // TextView ellipsises inside its weighted column; this is the same thing,
            // measured with the same metrics.
            val valueWidth = item.value?.takeIf { it.isNotEmpty() }
                ?.let { metrics.widthPx(it, labelSize).toFloat() } ?: 0f
            val labelRoom = if (valueWidth > 0f) {
                leftPx + widthPx - pad - valueWidth - cursorGap - labelX
            } else {
                leftPx + widthPx - pad - labelX
            }
            val label = Ff4TextRun(
                text = ellipsize(item.label.toString(), labelRoom, labelSize, metrics),
                x = labelX,
                baselineY = blockTop + metrics.ascentPx(labelSize),
                sizePx = labelSize,
                color = labelColor,
            )

            val subtitle = item.subtitle?.takeIf { it.isNotBlank() }?.let {
                Ff4TextRun(
                    text = it.toString(),
                    x = labelX,
                    baselineY = blockTop + metrics.lineHeightPx(labelSize) +
                        metrics.ascentPx(scale.textSmallPx),
                    sizePx = scale.textSmallPx,
                    color = Ff4Palette.GREY,
                )
            }

            val value = item.value?.takeIf { it.isNotEmpty() }?.let {
                Ff4TextRun(
                    text = it.toString(),
                    x = leftPx + widthPx - pad,
                    baselineY = blockTop + metrics.ascentPx(labelSize),
                    sizePx = labelSize,
                    color = if (item.enabled) Ff4Palette.YELLOW else Ff4Palette.GREY,
                    align = Ff4TextAlign.RIGHT,
                )
            }

            val cursor = if (isSelected) {
                Ff4Rect(
                    left = leftPx + startPaddingPx(scale, item.indent),
                    top = y + pad + (content - cursorH) / 2f,
                    right = leftPx + startPaddingPx(scale, item.indent) + cursorW,
                    bottom = y + pad + (content - cursorH) / 2f + cursorH,
                )
            } else {
                null
            }

            out.add(Ff4MenuRow(index, bounds, isSelected, cursor, label, value, subtitle))
            y += height
        }
        return out
    }

    /**
     * Emit a menu inside a fixed height, scrolling so the selected row is visible.
     *
     * The Android build puts its menus in a ScrollView, which scrolls the selection
     * into view. The desktop draws rows directly, so without this a long list - the
     * flag editor's twelve sections, say - ran past the bottom of its window and over
     * the hint bar. Only rows that fall inside the window are drawn, so nothing
     * escapes the box, and the scroll follows the cursor.
     *
     * Returns the height actually used.
     */
    fun emitScrolled(
        items: List<Ff4MenuItem>,
        scale: Ff4Scale,
        metrics: Ff4TextMetrics,
        widthPx: Float,
        selected: Int,
        topPx: Float,
        leftPx: Float,
        maxHeightPx: Float,
        list: Ff4DrawList,
    ): Float {
        if (items.isEmpty() || maxHeightPx <= 0f) return 0f

        val layout = rows(items, scale, metrics, widthPx, selected, topPx = 0f, leftPx = leftPx)
        val total = layout.lastOrNull()?.bounds?.bottom ?: 0f
        val index = selected.coerceIn(0, layout.lastIndex)

        // Scroll the minimum needed to keep the selected row inside the window. The
        // whole list is then drawn shifted and clipped, so a row that is only partly
        // visible is cut off at the edge - like the ScrollView the app uses - instead
        // of being skipped, which used to leave gaps and could hide the selection.
        val selectedTop = layout[index].bounds.top
        val selectedBottom = layout[index].bounds.bottom
        val maxOffset = (total - maxHeightPx).coerceAtLeast(0f)
        val offset = (selectedBottom - maxHeightPx).coerceIn(0f, maxOffset)

        list.clip(Ff4Rect(leftPx - 1f, topPx, leftPx + widthPx + 1f, topPx + maxHeightPx))
        for (row in layout) {
            val top = row.bounds.top - offset
            if (top + (row.bounds.bottom - row.bounds.top) < 0f) continue
            if (top > maxHeightPx) break
            emitRow(row, topPx + top, list)
        }
        list.resetClip()
        return minOf(total, maxHeightPx)
    }

    /** Truncate [text] with an ellipsis so it fits [roomPx]. */
    fun ellipsize(text: String, roomPx: Float, sizePx: Float, metrics: Ff4TextMetrics): String {
        if (roomPx <= 0f) return ""
        if (metrics.widthPx(text, sizePx) <= roomPx) return text
        var end = text.length
        while (end > 1 && metrics.widthPx(text.substring(0, end) + "...", sizePx) > roomPx) end--
        return text.substring(0, end) + "..."
    }

    /** Draw one laid-out row at [y], keeping the layout's own positions otherwise. */
    private fun emitRow(row: Ff4MenuRow, y: Float, list: Ff4DrawList) {
        val dy = y - row.bounds.top
        if (row.selected) list.fill(row.bounds.translated(0f, dy), Ff4Palette.BLUE_SELECTED)
        row.cursor?.let { list.sprite(Ff4Sprite.CURSOR, it.translated(0f, dy)) }
        list.text(row.label.text, row.label.x, row.label.baselineY + dy, row.label.sizePx, row.label.color)
        row.subtitle?.let { list.text(it.text, it.x, it.baselineY + dy, it.sizePx, it.color) }
        row.value?.let { list.text(it.text, it.x, it.baselineY + dy, it.sizePx, it.color, it.align) }
    }

    /** Emit a laid-out menu into [list]: row highlights, the cursor, then the text. */
    fun emit(
        items: List<Ff4MenuItem>,
        scale: Ff4Scale,
        metrics: Ff4TextMetrics,
        widthPx: Float,
        selected: Int,
        topPx: Float = 0f,
        leftPx: Float = 0f,
        list: Ff4DrawList,
    ): Float {
        val layout = rows(items, scale, metrics, widthPx, selected, topPx, leftPx)

        // Highlights and the cursor first, so text always sits on top.
        for (row in layout) {
            if (row.selected) list.fill(row.bounds, Ff4Palette.BLUE_SELECTED)
        }
        for (row in layout) {
            row.cursor?.let { list.sprite(Ff4Sprite.CURSOR, it) }
        }
        for (row in layout) {
            list.text(row.label.text, row.label.x, row.label.baselineY, row.label.sizePx, row.label.color)
            row.subtitle?.let {
                list.text(it.text, it.x, it.baselineY, it.sizePx, it.color)
            }
            row.value?.let {
                list.text(it.text, it.x, it.baselineY, it.sizePx, it.color, it.align)
            }
        }
        return contentHeightPx(items, scale, metrics)
    }
}
