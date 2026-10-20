package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The progress bar geometry.
 *
 * Like the window chrome, this is switched on for Android in the same commit, so
 * one test re-derives the arithmetic the view performed inline and the rest pin
 * the properties the look depends on.
 */
class Ff4ProgressBarChromeTest {

    private val scale = Ff4Scale(shortEdgePx = 1080, density = 2.625f)
    private val epsilon = 1e-4f

    @Test
    fun `a bar with no area has no chrome`() {
        assertNull(Ff4ProgressBarChrome.of(0f, 40f, scale, 50))
        assertNull(Ff4ProgressBarChrome.of(400f, 0f, scale, 50))
    }

    @Test
    fun `nothing is filled at zero percent`() {
        val chrome = Ff4ProgressBarChrome.of(400f, 40f, scale, 0)!!
        assertNull(chrome.fill)
    }

    @Test
    fun `the whole track is filled at one hundred percent`() {
        val chrome = Ff4ProgressBarChrome.of(400f, 40f, scale, 100)!!
        val track = chrome.trough.inset(scale.borderWidthPx)
        val fill = chrome.fill!!

        assertEquals(track.left, fill.left, epsilon)
        assertEquals(track.right, fill.right, epsilon)
    }

    @Test
    fun `half is half the track`() {
        val chrome = Ff4ProgressBarChrome.of(400f, 40f, scale, 50)!!
        val track = chrome.trough.inset(scale.borderWidthPx)
        val fill = chrome.fill!!

        assertEquals(track.left, fill.left, epsilon)
        assertEquals(track.left + track.width / 2f, fill.right, epsilon)
    }

    @Test
    fun `percentages outside zero to one hundred are clamped`() {
        val over = Ff4ProgressBarChrome.of(400f, 40f, scale, 250)!!
        val under = Ff4ProgressBarChrome.of(400f, 40f, scale, -80)!!
        val full = Ff4ProgressBarChrome.of(400f, 40f, scale, 100)!!

        assertEquals(full.fill!!.right, over.fill!!.right, epsilon)
        assertNull(under.fill)
    }

    @Test
    fun `the border reaches the bar's edge and the trough starts where it ends`() {
        val chrome = Ff4ProgressBarChrome.of(400f, 40f, scale, 50)!!
        val band = chrome.border.rect.stroked(chrome.border.widthPx)
        val border = chrome.border.widthPx

        assertEquals(0f, band.left, epsilon)
        assertEquals(0f, band.top, epsilon)
        assertEquals(400f, band.right, epsilon)
        assertEquals(40f, band.bottom, epsilon)
        assertEquals(chrome.trough.left, band.left + border, epsilon)
    }

    @Test
    fun `geometry matches the arithmetic FF4ProgressBar used to do inline`() {
        val scales = listOf(Ff4Scale(240, 1f), Ff4Scale(1080, 2.625f), Ff4Scale(2160, 4f))
        val sizes = listOf(200f to 20f, 400f to 40f, 1024f to 51.5f)
        val progresses = listOf(0, 1, 33, 50, 99, 100)

        for (scale in scales) {
            for ((width, height) in sizes) {
                for (progress in progresses) {
                    val border = scale.unit

                    // What the view computed before this module existed.
                    val legacyTrough = Ff4Rect(border, border, width - border, height - border)
                    val left = border * 2
                    val top = border * 2
                    val right = width - border * 2
                    val bottom = height - border * 2
                    val innerWidth = right - left
                    val filled = innerWidth * (progress.coerceIn(0, 100) / 100f)
                    val legacyFill = if (innerWidth > 0f && filled > 0f) {
                        Ff4Rect(left, top, left + filled, bottom)
                    } else {
                        null
                    }

                    val chrome = Ff4ProgressBarChrome.of(width, height, scale, progress)!!
                    val where = "unit ${scale.unit}, ${width}x$height at $progress%"

                    assertEquals(where, legacyTrough, chrome.trough)
                    if (legacyFill == null) {
                        assertNull(where, chrome.fill)
                    } else {
                        assertNotNull(where, chrome.fill)
                        assertEquals(where, legacyFill, chrome.fill)
                    }
                }
            }
        }
    }
}
