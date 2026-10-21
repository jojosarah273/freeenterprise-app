package com.freeenterprise.desktop

import com.freeenterprise.desktop.GamepadMapper.Retro

/**
 * Decides what a controller press does.
 *
 * Two kinds of press arrive here: the game's own buttons, which are forwarded to the
 * emulator, and the handful of shortcuts we take for ourselves. They are the same
 * ones the Android build uses, so a pad behaves the same on both:
 *
 *   Start      our menu (pause)
 *   L2         the tracker
 *   R2         fast-forward while held
 *
 * Buttons are forwarded as differences, not as a state, because the core wants
 * press and release events. While one of our menus is open the game's buttons are
 * released and suppressed, so nothing leaks through to the game behind it.
 */
class PadRouter(
    private val sendButton: (retroId: Int, pressed: Boolean) -> Unit,
    private val onShortcut: (PadShortcut) -> Unit,
    private val onMenuInput: (MenuInput) -> Unit = {},
) {
    enum class PadShortcut { TOGGLE_MENU, TOGGLE_TRACKER, FAST_FORWARD_ON, FAST_FORWARD_OFF }

    /** What the pad means while one of our menus is open. */
    enum class MenuInput { UP, DOWN, CONFIRM, CANCEL }

    /** Buttons the game itself receives. */
    private val gameButtons = intArrayOf(
        Retro.A, Retro.B, Retro.X, Retro.Y, Retro.SELECT, Retro.START,
        Retro.UP, Retro.DOWN, Retro.LEFT, Retro.RIGHT,
        Retro.L, Retro.R, Retro.L3, Retro.R3,
    )

    /** Buttons we consume; they are never forwarded. */
    private val ourButtons = intArrayOf(Retro.START, Retro.L2, Retro.R2)

    var menuOpen: Boolean = false
        set(value) {
            if (value && !field) releaseGameButtons()
            field = value
        }

    private var previous: Int = 0
    private var fastForward = false

    fun update(mask: Int) {
        val pressed = GamepadMapper.newlyPressed(mask, previous)

        if (GamepadMapper.held(pressed, Retro.START)) onShortcut(PadShortcut.TOGGLE_MENU)
        if (GamepadMapper.held(pressed, Retro.L2)) onShortcut(PadShortcut.TOGGLE_TRACKER)

        val wantFast = GamepadMapper.held(mask, Retro.R2)
        if (wantFast != fastForward) {
            fastForward = wantFast
            onShortcut(if (wantFast) PadShortcut.FAST_FORWARD_ON else PadShortcut.FAST_FORWARD_OFF)
        }

        if (menuOpen) {
            if (GamepadMapper.held(pressed, Retro.UP)) onMenuInput(MenuInput.UP)
            if (GamepadMapper.held(pressed, Retro.DOWN)) onMenuInput(MenuInput.DOWN)
            if (GamepadMapper.held(pressed, Retro.A)) onMenuInput(MenuInput.CONFIRM)
            if (GamepadMapper.held(pressed, Retro.B)) onMenuInput(MenuInput.CANCEL)
        } else {
            for (button in gameButtons) {
                if (button in ourButtons) continue
                val now = GamepadMapper.held(mask, button)
                val was = GamepadMapper.held(previous, button)
                if (now != was) sendButton(button, now)
            }
        }

        previous = mask
    }

    private fun releaseGameButtons() {
        for (button in gameButtons) {
            if (GamepadMapper.held(previous, button) && button !in ourButtons) sendButton(button, false)
        }
    }
}
