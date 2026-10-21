package com.freeenterprise.desktop

import com.freeenterprise.desktop.GamepadMapper.Retro
import com.freeenterprise.desktop.PadRouter.PadShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a controller press does. There is no pad attached to the machine this was
 * written on, so the routing is pinned here instead: game buttons reach the
 * emulator once per press, our shortcuts are consumed, and opening a menu releases
 * whatever was held so nothing sticks down.
 */
class PadRouterTest {

    private val buttons = mutableListOf<Pair<Int, Boolean>>()
    private val shortcuts = mutableListOf<PadShortcut>()
    private val router = PadRouter(
        sendButton = { id, pressed -> buttons.add(id to pressed) },
        onShortcut = { shortcuts.add(it) },
    )

    private fun maskOf(vararg retro: Int) = retro.fold(0) { acc, id -> acc or (1 shl id) }

    @Test
    fun `a game button is pressed once and released once`() {
        router.update(maskOf(Retro.A))
        router.update(maskOf(Retro.A))
        router.update(0)

        assertEquals(listOf(Retro.A to true, Retro.A to false), buttons)
    }

    @Test
    fun `start is ours and never reaches the game`() {
        router.update(maskOf(Retro.START))

        assertEquals(listOf(PadShortcut.TOGGLE_MENU), shortcuts)
        assertTrue("start must not be forwarded", buttons.isEmpty())
    }

    @Test
    fun `L2 opens the tracker and R2 fast-forwards while held`() {
        router.update(maskOf(Retro.L2))
        assertEquals(listOf(PadShortcut.TOGGLE_TRACKER), shortcuts)

        router.update(maskOf(Retro.R2))
        assertEquals(listOf(PadShortcut.TOGGLE_TRACKER, PadShortcut.FAST_FORWARD_ON), shortcuts)

        router.update(0)
        assertEquals(
            listOf(PadShortcut.TOGGLE_TRACKER, PadShortcut.FAST_FORWARD_ON, PadShortcut.FAST_FORWARD_OFF),
            shortcuts,
        )
    }

    @Test
    fun `with a menu open the game buttons are suppressed`() {
        router.update(maskOf(Retro.A))
        router.menuOpen = true
        buttons.clear()   // opening the menu releases what was held; see the next test

        router.update(maskOf(Retro.A, Retro.DOWN))

        assertTrue("nothing may reach the game behind a menu", buttons.isEmpty())
    }

    @Test
    fun `opening a menu releases a button that was held`() {
        router.update(maskOf(Retro.RIGHT))
        buttons.clear()

        router.menuOpen = true

        assertEquals(listOf(Retro.RIGHT to false), buttons)
    }

    @Test
    fun `holding a button does not repeat it`() {
        repeat(30) { router.update(maskOf(Retro.LEFT)) }

        assertEquals("one press, not thirty", listOf(Retro.LEFT to true), buttons)
    }
}
