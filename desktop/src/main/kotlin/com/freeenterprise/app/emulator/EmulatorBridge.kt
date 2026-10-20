package com.freeenterprise.app.emulator

import java.io.File
import java.nio.ByteBuffer

/**
 * Desktop side of the native emulator bridge.
 *
 * The package and class name deliberately match the Android one, because the C++
 * bridge in `app/src/main/cpp/ff4emu.cpp` exports its JNI symbols under that name.
 * The consequence is that the *same* bridge file serves both platforms: there is no
 * second copy to drift, and the only thing the Linux build needed was a stand-in for
 * `<android/log.h>` (see `desktop/native/shim`).
 *
 * The core itself is dlopen'd by the bridge, so this build points it at the
 * `libretro.so` we compile for Linux.
 */
object EmulatorBridge {

    private var loaded = false

    /** Loads the bridge library from [nativeDir]; the core is loaded separately. */
    fun load(nativeDir: File) {
        if (loaded) return
        val lib = File(nativeDir, "libff4emu.so")
        require(lib.isFile) { "bridge library not found: $lib (run: make -C desktop/native)" }
        System.load(lib.absolutePath)
        loaded = true
    }

    external fun nativeInit(corePath: String, systemDir: String, saveDir: String): Boolean
    external fun nativeSetVideoBuffer(buffer: ByteBuffer)
    external fun nativeLoadRom(romPath: String, savePath: String): Boolean
    external fun nativeRun()
    external fun nativeLastError(): String?
    external fun nativeFrameReady(): Boolean
    external fun nativeVideoWidth(): Int
    external fun nativeVideoHeight(): Int
    external fun nativeUsesRgb565(): Boolean
    external fun nativeSetButton(id: Int, pressed: Boolean)
    external fun nativeAudioFrames(): Int
    external fun nativeCopyAudio(target: ShortArray, frames: Int)
    external fun nativeClearAudio()
    external fun nativeReadMemory(region: Int, offset: Int, target: ByteArray): Int
    external fun nativeCopySaveRam(target: ByteArray): Int
    external fun nativeSaveState(path: String): Boolean
    external fun nativeLoadState(path: String): Boolean
    external fun nativeSaveRamSize(): Int
    external fun nativeFlushSaveRam(savePath: String): Boolean
    external fun nativeUnload()

    /** RetroDevices ids, as libretro defines them. */
    object Button {
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
    }
}
