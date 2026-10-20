package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The menu row rules. These decide where every line of text sits, so they are
 * pinned rather than eyeballed: the desktop renderer draws what this returns, and
 * the Android views will eventually be replaced by it.
 */
class Ff4MenuLayoutTest {

    private val scale = Ff4Scale(shortEdgePx = 1080, density = 2.625f)
    private val metrics = Ff4TextMetrics.Empty   // line height = size, ascent = 0.75 * size
    private val epsilon = 1e-3f

    private val items = listOf(
        Ff4MenuItem("Play Free Enterprise", "3 seed(s)"),
        Ff4MenuItem("Generate Seed"),
        Ff4MenuItem("Check for updates", "v0.1.25"),
    )

    @Test
    fun `a row is padded above and below its content`() {
        val pad = Ff4MenuLayout.edgePaddingPx(scale)
        val cursor = Ff4MenuLayout.cursorHeightPx(scale)
        val text = metrics.lineHeightPx(scale.textPx).toFloat()
        val expected = pad * 2 + maxOf(cursor, text)

        assertEquals(expected, Ff4MenuLayout.rowHeightPx(items[0], scale, metrics), epsilon)
    }

    @Test
    fun `a subtitle makes a row taller`() {
        val plain = Ff4MenuItem("a")
        val withSub = Ff4MenuItem("a", subtitle = "second line")

        assertTrue(
            Ff4MenuLayout.rowHeightPx(withSub, scale, metrics) >
                Ff4MenuLayout.rowHeightPx(plain, scale, metrics),
        )
    }

    @Test
    fun `rows stack contiguously and match the content height`() {
        val rows = Ff4MenuLayout.rows(items, scale, metrics, 800f, selected = 0)

        assertEquals(0f, rows.first().bounds.top, epsilon)
        for (i in 1 until rows.size) {
            assertEquals("row $i starts where ${i - 1} ends", rows[i - 1].bounds.bottom, rows[i].bounds.top, epsilon)
        }
        assertEquals(
            Ff4MenuLayout.contentHeightPx(items, scale, metrics),
            rows.last().bounds.bottom,
            epsilon,
        )
    }

    @Test
    fun `the label clears the cursor by one gap`() {
        val rows = Ff4MenuLayout.rows(items, scale, metrics, 800f, selected = 0)
        val row = rows[0]
        val cursor = row.cursor!!
        val gap = Ff4MenuLayout.cursorGapPx(scale)

        assertEquals(cursor.right + gap, row.label.x, epsilon)
    }

    @Test
    fun `the cursor sits on the selected row only and inside its bounds`() {
        val rows = Ff4MenuLayout.rows(items, scale, metrics, 800f, selected = 1)

        assertNull(rows[0].cursor)
        assertNull(rows[2].cursor)
        val cursor = rows[1].cursor!!
        assertTrue(cursor.top >= rows[1].bounds.top)
        assertTrue(cursor.bottom <= rows[1].bounds.bottom)
    }

    @Test
    fun `values are right-aligned inside the row`() {
        val rows = Ff4MenuLayout.rows(items, scale, metrics, 800f, selected = 0)
        val value = rows[0].value!!
        val pad = Ff4MenuLayout.edgePaddingPx(scale)

        assertEquals(Ff4TextAlign.RIGHT, value.align)
        assertEquals(800f - pad, value.x, epsilon)
        assertNull(rows[1].value)
    }

    @Test
    fun `an indent pushes the cursor and label right`() {
        val indented = Ff4MenuLayout.rows(listOf(Ff4MenuItem("nested", indent = 2)), scale, metrics, 800f, 0)
        val flat = Ff4MenuLayout.rows(listOf(Ff4MenuItem("nested")), scale, metrics, 800f, 0)

        // The padding is truncated after multiplying, so compare against the two
        // paddings themselves rather than assuming a per-unit multiple.
        val shift = Ff4MenuLayout.startPaddingPx(scale, 2) - Ff4MenuLayout.startPaddingPx(scale, 0)
        assertTrue("an indent must move the content right", shift > 0f)
        assertEquals(shift, indented[0].cursor!!.left - flat[0].cursor!!.left, epsilon)
        assertEquals(shift, indented[0].label.x - flat[0].label.x, epsilon)
    }

    @Test
    fun `a disabled row greys its label and value`() {
        val rows = Ff4MenuLayout.rows(
            listOf(Ff4MenuItem("disabled", "n/a", enabled = false)),
            scale, metrics, 800f, selected = 0,
        )

        assertEquals(Ff4Palette.GREY, rows[0].label.color)
        assertEquals(Ff4Palette.GREY, rows[0].value!!.color)
    }

    @Test
    fun `emitting a menu highlights the selected row and draws the cursor`() {
        val list = Ff4DrawList()
        Ff4MenuLayout.emit(items, scale, metrics, 800f, selected = 2, list = list)

        val fills = list.ops().filterIsInstance<Ff4Op.Fill>()
        assertEquals("only the selected row is highlighted", 1, fills.size)
        assertEquals(Ff4Palette.BLUE_SELECTED, fills[0].color)
        assertEquals(rows(2).bounds, fills[0].rect)

        assertEquals(1, list.ops().filterIsInstance<Ff4Op.Sprite>().size)
        // three labels plus two values
        assertEquals(5, list.ops().filterIsInstance<Ff4Op.Text>().size)
    }

    private fun rows(selected: Int) =
        Ff4MenuLayout.rows(items, scale, metrics, 800f, selected = selected)[selected]
}
