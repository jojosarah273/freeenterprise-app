package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The window chrome contract.
 *
 * The first grid test re-derives the arithmetic that `FF4Window` performed inline
 * before this module existed, so the refactor cannot have moved a single
 * coordinate. The rest pin the properties the look depends on: the border reaches
 * the very edge of the window, the body starts exactly where the border ends, and
 * the bevel sits immediately inside it.
 */
class Ff4WindowChromeTest {

    private val scale = Ff4Scale(shortEdgePx = 1080, density = 2.625f)
    private val epsilon = 1e-4f

    @Test
    fun `a window with no area has no chrome`() {
        assertNull(Ff4WindowChrome.of(0f, 400f, scale, dimmed = false))
        assertNull(Ff4WindowChrome.of(800f, 0f, scale, dimmed = false))
        assertNull(Ff4WindowChrome.of(-10f, -10f, scale, dimmed = false))
    }

    @Test
    fun `a focused window is blue with a white border and a black bevel`() {
        val chrome = Ff4WindowChrome.of(800f, 400f, scale, dimmed = false)!!
        assertEquals(Ff4Palette.BLUE, chrome.bodyColor)
        assertEquals(Ff4Palette.WHITE, chrome.border.color)
        assertEquals(Ff4Palette.BLACK, chrome.bevel.color)
    }

    @Test
    fun `a dimmed window uses the grey pair`() {
        val chrome = Ff4WindowChrome.of(800f, 400f, scale, dimmed = true)!!
        assertEquals(Ff4Palette.GREY_DARK, chrome.bodyColor)
        assertEquals(Ff4Palette.GREY, chrome.border.color)
    }

    @Test
    fun `the border band reaches the window edge and stops at the body`() {
        val chrome = Ff4WindowChrome.of(800f, 400f, scale, dimmed = false)!!
        val band = chrome.border.rect.stroked(chrome.border.widthPx)
        val border = chrome.border.widthPx

        // Stroking is centred on the path, so the band spans the whole window ...
        assertEquals(0f, band.left, epsilon)
        assertEquals(0f, band.top, epsilon)
        assertEquals(800f, band.right, epsilon)
        assertEquals(400f, band.bottom, epsilon)

        // ... and is exactly one border thick, so its inner edge is the body's edge.
        assertEquals(chrome.body.left, band.left + border, epsilon)
        assertEquals(chrome.body.top, band.top + border, epsilon)
        assertEquals(chrome.body.right, band.right - border, epsilon)
        assertEquals(chrome.body.bottom, band.bottom - border, epsilon)
    }

    @Test
    fun `the bevel sits immediately inside the body edge`() {
        val chrome = Ff4WindowChrome.of(800f, 400f, scale, dimmed = false)!!
        val covered = chrome.bevel.rect.stroked(chrome.bevel.widthPx)

        assertEquals(chrome.body.left, covered.left, epsilon)
        assertEquals(chrome.body.top, covered.top, epsilon)
    }

    @Test
    fun `the body is inset by the border on every side`() {
        val chrome = Ff4WindowChrome.of(800f, 400f, scale, dimmed = false)!!
        val border = scale.borderWidthPx

        assertEquals(border, chrome.body.left, epsilon)
        assertEquals(border, chrome.body.top, epsilon)
        assertEquals(800f - border, chrome.body.right, epsilon)
        assertEquals(400f - border, chrome.body.bottom, epsilon)
    }

    @Test
    fun `chrome matches the arithmetic FF4Window used to do inline`() {
        val sizes = listOf(1f to 1f, 320f to 180f, 800f to 400f, 1920f to 1080f, 37.5f to 913.25f)
        val scales = listOf(
            Ff4Scale(240, 1f),
            Ff4Scale(720, 2f),
            Ff4Scale(1080, 2.625f),
            Ff4Scale(1440, 3.5f),
            Ff4Scale(2160, 4f),
        )

        for (scale in scales) {
            for ((width, height) in sizes) {
                val border = scale.borderWidthPx
                val inner = scale.innerWidthPx
                val bevelOffset = border + inner / 2f

                // Exactly what the old drawChrome() computed, independently here.
                val legacyBody = Ff4Rect(border, border, width - border, height - border)
                val legacyBorder = Ff4Stroke(
                    Ff4Rect(border / 2f, border / 2f, width - border / 2f, height - border / 2f),
                    border,
                    Ff4Palette.WHITE,
                )
                val legacyBevel = Ff4Stroke(
                    Ff4Rect(
                        bevelOffset,
                        bevelOffset,
                        width - bevelOffset,
                        height - bevelOffset,
                    ),
                    inner,
                    Ff4Palette.BLACK,
                )

                val chrome = Ff4WindowChrome.of(width, height, scale, dimmed = false)!!
                val where = "unit ${scale.unit}, ${width}x$height"

                assertEquals(where, legacyBody, chrome.body)
                assertEquals(where, legacyBorder, chrome.border)
                assertEquals(where, legacyBevel, chrome.bevel)
            }
        }
    }
}
