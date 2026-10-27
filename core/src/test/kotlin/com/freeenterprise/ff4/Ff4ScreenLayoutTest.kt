package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The box stack every screen is built from. */
class Ff4ScreenLayoutTest {

    private val metrics = Ff4TextMetrics.Empty

    @Test
    fun `a taller hint box pushes the menu up instead of covering it`() {
        // The flag editor's detail box is up to three lines. While the layout always
        // reserved one, the box was drawn over the bottom of the menu window and hid
        // its last rows - the player could not read the rows behind it.
        val scale = Ff4Scale(720, 1f)
        for (lines in 1..3) {
            val boxes = Ff4ScreenLayout.boxes(1280f, 720f, scale, metrics, hintLines = lines)
            val gap = Ff4ScreenLayout.gapPx(scale)
            assertTrue(
                "hintLines=$lines: menu bottom ${boxes.menu.bottom} overlaps hint top ${boxes.hint.top}",
                boxes.menu.bottom <= boxes.hint.top - gap,
            )
        }
    }

    @Test
    fun `the hint box grows by exactly one line height per line`() {
        val scale = Ff4Scale(720, 1f)
        val one = Ff4ScreenLayout.hintHeightPx(scale, metrics, 1)
        val two = Ff4ScreenLayout.hintHeightPx(scale, metrics, 2)
        assertEquals(metrics.lineHeightPx(scale.textSmallPx).toFloat(), two - one, 0.01f)
    }

    @Test
    fun `more hint lines mean a shorter menu, never a shorter status box`() {
        val scale = Ff4Scale(720, 1f)
        val one = Ff4ScreenLayout.boxes(1280f, 720f, scale, metrics, hintLines = 1)
        val three = Ff4ScreenLayout.boxes(1280f, 720f, scale, metrics, hintLines = 3)
        assertEquals(one.status, three.status)
        assertTrue(three.menu.height < one.menu.height)
    }
}
