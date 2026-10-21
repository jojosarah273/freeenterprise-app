package com.freeenterprise.desktop

import java.io.File

/**
 * The controller, if there is one.
 *
 * SDL2 is dlopen'd by the native layer, so this reports "not available" rather than
 * failing when the library is missing, and the application carries on with the
 * keyboard. SDL's event functions want the main thread, so polling happens from the
 * UI thread into a volatile mask; the emulator loop reads that mask and applies the
 * differences, because the core may only be touched from its own thread.
 */
class Gamepad(private val nativeDir: File) : AutoCloseable {

    val available: Boolean
    val connected: Boolean
    val name: String

    init {
        var loaded = false
        try {
            System.load(File(nativeDir, "libff4input.so").absolutePath)
            loaded = true
        } catch (t: Throwable) {
            System.err.println("controller support unavailable (${t.message}); keyboard only")
        }
        available = loaded && nativeAvailable()
        connected = available && nativeStart()
        name = if (connected) nativeName().orEmpty().ifBlank { "controller" } else ""
    }

    /** One poll: the buttons held right now, in libretro's numbering. */
    fun poll(): Int {
        if (!connected) return 0
        val axes = axesBuffer
        nativeAxes(axes)
        return GamepadMapper.toRetroMask(nativeButtons(), axes)
    }

    override fun close() {
        if (connected) nativeStop()
    }

    private val axesBuffer = FloatArray(6)

    private external fun nativeAvailable(): Boolean
    private external fun nativeStart(): Boolean
    private external fun nativeName(): String?
    private external fun nativeButtons(): Int
    private external fun nativeAxes(target: FloatArray): Int
    private external fun nativeStop()
}
