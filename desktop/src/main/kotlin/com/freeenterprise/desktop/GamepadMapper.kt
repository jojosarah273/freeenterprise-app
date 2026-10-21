package com.freeenterprise.desktop

/**
 * Turns controller state into the button vocabulary the emulator already speaks.
 *
 * The bits coming from the native layer are an order of our own (see the `Bit` enum
 * in `desktop/native/ff4input.cpp`), and the target is libretro's button numbering -
 * the same ids the Android build feeds the core. Keeping this mapping in Kotlin, and
 * pure, means it can be tested without a controller or SDL.
 *
 * The left stick doubles as the d-pad, because that is how the Android build behaves
 * and how players expect a menu to feel.
 */
object GamepadMapper {

    // Our bit order, matching ff4input.cpp.
    const val BIT_A = 0
    const val BIT_B = 1
    const val BIT_X = 2
    const val BIT_Y = 3
    const val BIT_BACK = 4
    const val BIT_GUIDE = 5
    const val BIT_START = 6
    const val BIT_LSTICK = 7
    const val BIT_RSTICK = 8
    const val BIT_LSHOULDER = 9
    const val BIT_RSHOULDER = 10
    const val BIT_DPAD_UP = 11
    const val BIT_DPAD_DOWN = 12
    const val BIT_DPAD_LEFT = 13
    const val BIT_DPAD_RIGHT = 14
    const val BIT_LTRIGGER = 15
    const val BIT_RTRIGGER = 16

    /** libretro's button ids, as the core and the Android build use them. */
    object Retro {
        const val B = 0
        const val Y = 1
        const val SELECT = 2
        const val START = 3
        const val UP = 4
        const val DOWN = 5
        const val LEFT = 6
        const val RIGHT = 7
        const val A = 8
        const val X = 9
        const val L = 10
        const val R = 11
        const val L2 = 12
        const val R2 = 13
        const val L3 = 14
        const val R3 = 15
    }

    /** How far the stick must move before it counts as a d-pad press. */
    const val STICK_THRESHOLD = 0.5f

    /**
     * [bits] from the native layer and [axes] (left x/y, right x/y, triggers) to a
     * libretro button mask.
     */
    fun toRetroMask(
        bits: Int,
        axes: FloatArray,
        stickThreshold: Float = STICK_THRESHOLD,
    ): Int {
        var mask = 0

        fun pressed(bit: Int) = (bits shr bit) and 1 == 1
        fun map(bit: Int, retro: Int) {
            if (pressed(bit)) mask = mask or (1 shl retro)
        }

        map(BIT_A, Retro.A)
        map(BIT_B, Retro.B)
        map(BIT_X, Retro.X)
        map(BIT_Y, Retro.Y)
        map(BIT_BACK, Retro.SELECT)
        map(BIT_START, Retro.START)
        map(BIT_LSTICK, Retro.L3)
        map(BIT_RSTICK, Retro.R3)
        map(BIT_LSHOULDER, Retro.L)
        map(BIT_RSHOULDER, Retro.R)
        map(BIT_DPAD_UP, Retro.UP)
        map(BIT_DPAD_DOWN, Retro.DOWN)
        map(BIT_DPAD_LEFT, Retro.LEFT)
        map(BIT_DPAD_RIGHT, Retro.RIGHT)
        map(BIT_LTRIGGER, Retro.L2)
        map(BIT_RTRIGGER, Retro.R2)

        // The stick stands in for the d-pad. SDL reports y negative as up.
        if (axes.size >= 2) {
            val x = axes[0]
            val y = axes[1]
            if (x <= -stickThreshold) mask = mask or (1 shl Retro.LEFT)
            if (x >= stickThreshold) mask = mask or (1 shl Retro.RIGHT)
            if (y <= -stickThreshold) mask = mask or (1 shl Retro.UP)
            if (y >= stickThreshold) mask = mask or (1 shl Retro.DOWN)
        }

        // Triggers, for pads that report them only as axes.
        if (axes.size >= 6) {
            if (axes[4] >= stickThreshold) mask = mask or (1 shl Retro.L2)
            if (axes[5] >= stickThreshold) mask = mask or (1 shl Retro.R2)
        }

        return mask
    }

    /** The button ids in [mask] that are present in [previous] but not in [mask]. */
    fun released(mask: Int, previous: Int): Int = previous and mask.inv()

    /** The button ids in [mask] that were not in [previous]. */
    fun newlyPressed(mask: Int, previous: Int): Int = mask and previous.inv()

    /** Whether button [retro] is held in [mask]. */
    fun held(mask: Int, retro: Int): Boolean = (mask shr retro) and 1 == 1
}
