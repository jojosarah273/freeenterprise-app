package com.freeenterprise.desktop

import com.freeenterprise.desktop.GamepadMapper.Retro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller mapping. Written against the layout the Android build uses, so a
 * pad behaves the same on both: A confirms, B cancels, the stick drives the menus,
 * and the triggers are the two shortcuts we take for ourselves.
 */
class GamepadMapperTest {

    private val noAxes = FloatArray(6)

    private fun maskOf(vararg bits: Int): Int = bits.fold(0) { acc, bit -> acc or (1 shl bit) }

    @Test
    fun `face buttons land on the libretro ids the app already uses`() {
        val mask = GamepadMapper.toRetroMask(
            maskOf(GamepadMapper.BIT_A, GamepadMapper.BIT_B, GamepadMapper.BIT_X, GamepadMapper.BIT_Y),
            noAxes,
        )

        assertTrue(GamepadMapper.held(mask, Retro.A))
        assertTrue(GamepadMapper.held(mask, Retro.B))
        assertTrue(GamepadMapper.held(mask, Retro.X))
        assertTrue(GamepadMapper.held(mask, Retro.Y))
    }

    @Test
    fun `shoulders, back, start and stick clicks map across`() {
        val mask = GamepadMapper.toRetroMask(
            maskOf(
                GamepadMapper.BIT_LSHOULDER, GamepadMapper.BIT_RSHOULDER,
                GamepadMapper.BIT_BACK, GamepadMapper.BIT_START,
                GamepadMapper.BIT_LSTICK, GamepadMapper.BIT_RSTICK,
            ),
            noAxes,
        )

        assertTrue(GamepadMapper.held(mask, Retro.L))
        assertTrue(GamepadMapper.held(mask, Retro.R))
        assertTrue(GamepadMapper.held(mask, Retro.SELECT))
        assertTrue(GamepadMapper.held(mask, Retro.START))
        assertTrue(GamepadMapper.held(mask, Retro.L3))
        assertTrue(GamepadMapper.held(mask, Retro.R3))
    }

    @Test
    fun `the d-pad maps across and the guide button is ignored`() {
        val mask = GamepadMapper.toRetroMask(
            maskOf(
                GamepadMapper.BIT_DPAD_UP, GamepadMapper.BIT_DPAD_DOWN,
                GamepadMapper.BIT_DPAD_LEFT, GamepadMapper.BIT_DPAD_RIGHT,
                GamepadMapper.BIT_GUIDE,
            ),
            noAxes,
        )

        assertTrue(GamepadMapper.held(mask, Retro.UP))
        assertTrue(GamepadMapper.held(mask, Retro.DOWN))
        assertTrue(GamepadMapper.held(mask, Retro.LEFT))
        assertTrue(GamepadMapper.held(mask, Retro.RIGHT))
        assertEquals("guide is not a game button", 4, Integer.bitCount(mask))
    }

    @Test
    fun `the left stick drives the d-pad`() {
        val up = GamepadMapper.toRetroMask(0, floatArrayOf(0f, -0.9f, 0f, 0f, 0f, 0f))
        assertTrue(GamepadMapper.held(up, Retro.UP))

        val right = GamepadMapper.toRetroMask(0, floatArrayOf(0.9f, 0f, 0f, 0f, 0f, 0f))
        assertTrue(GamepadMapper.held(right, Retro.RIGHT))

        val resting = GamepadMapper.toRetroMask(0, floatArrayOf(0.1f, -0.2f, 0f, 0f, 0f, 0f))
        assertEquals("a stick at rest presses nothing", 0, resting)
    }

    @Test
    fun `triggers map to L2 and R2, as bits or as axes`() {
        val asBits = GamepadMapper.toRetroMask(
            maskOf(GamepadMapper.BIT_LTRIGGER, GamepadMapper.BIT_RTRIGGER), noAxes,
        )
        assertTrue(GamepadMapper.held(asBits, Retro.L2))
        assertTrue(GamepadMapper.held(asBits, Retro.R2))

        val asAxes = GamepadMapper.toRetroMask(0, floatArrayOf(0f, 0f, 0f, 0f, 0.9f, 0.8f))
        assertTrue(GamepadMapper.held(asAxes, Retro.L2))
        assertTrue(GamepadMapper.held(asAxes, Retro.R2))
    }

    @Test
    fun `edges are reported once, so buttons are pressed and released once`() {
        val none = 0
        val a = 1 shl Retro.A

        assertEquals(a, GamepadMapper.newlyPressed(a, none))
        assertEquals(0, GamepadMapper.newlyPressed(a, a))
        assertEquals(a, GamepadMapper.released(none, a))
        assertEquals(0, GamepadMapper.released(a, a))
    }

    @Test
    fun `a disconnected pad presses nothing`() {
        assertEquals(0, GamepadMapper.toRetroMask(0, noAxes))
        assertFalse(GamepadMapper.held(0, Retro.START))
    }
}
