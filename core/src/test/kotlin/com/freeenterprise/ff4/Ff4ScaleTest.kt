package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the conversion from screen size to layout unit. These numbers decide every
 * border and every box in the interface, so a change here is a change to the pixels
 * on every platform at once.
 */
class Ff4ScaleTest {

    private val phone = Ff4Scale(shortEdgePx = 1080, density = 2.625f)

    @Test
    fun `one unit is the short edge divided by 224`() =
        assertEquals(1080f / 224f, phone.unit, 1e-5f)

    @Test
    fun `unit never falls below two pixels`() =
        assertEquals(2f, Ff4Scale(shortEdgePx = 300, density = 1f).unit, 0f)

    @Test
    fun `text is eight and six units tall`() {
        assertEquals(phone.unit * 8f, phone.textPx, 1e-5f)
        assertEquals(phone.unit * 6f, phone.textSmallPx, 1e-5f)
    }

    @Test
    fun `content padding truncates, as it always has`() =
        // 1080 / 224 = 4.8214286, times 3 = 14.46
        assertEquals(14, phone.contentPaddingPx)

    @Test
    fun `the selection cursor is sixteen by thirteen units`() {
        assertEquals(phone.unit * 16f, phone.cursorWidthPx, 1e-4f)
        assertEquals(phone.unit * 13f, phone.cursorHeightPx, 1e-4f)
    }

    @Test
    fun `dp truncates the way Android's TypedValue does`() =
        // 6 * 2.625 = 15.75, truncated to 15
        assertEquals(15, Ff4Scale(shortEdgePx = 1080, density = 2.625f).dp(6))

    @Test
    fun `content padding matches the old theme formula on every screen`() {
        for (shortEdge in intArrayOf(240, 480, 720, 1080, 1440, 2160)) {
            val legacyUnit = maxOf(2f, shortEdge / 224f)
            assertEquals(
                "short edge $shortEdge",
                (legacyUnit * 3f).toInt(),
                Ff4Scale(shortEdge, 2f).contentPaddingPx,
            )
        }
    }
}
