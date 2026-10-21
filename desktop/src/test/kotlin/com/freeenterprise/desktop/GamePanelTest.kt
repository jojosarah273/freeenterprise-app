package com.freeenterprise.desktop

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression guard for a real bug: Tab did nothing in game, because Swing treats it
 * as a focus-traversal key and consumes it before any KeyListener sees it. The panel
 * has to say otherwise, and this fails if that line is ever removed.
 */
class GamePanelTest {

    @Test
    fun `the game panel does not swallow Tab for focus traversal`() {
        val canvas = SoftwareCanvas(320, 240, AwtTextMetrics(), shadowOffsetPx = 0f)
        assertFalse(
            "Tab must reach the key listener, or the tracker shortcut stops working",
            gamePanel(canvas).focusTraversalKeysEnabled,
        )
    }
}
