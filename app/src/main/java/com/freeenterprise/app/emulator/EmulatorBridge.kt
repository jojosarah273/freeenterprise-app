package com.freeenterprise.app.emulator

import java.nio.ByteBuffer

/**
 * Thin wrapper over libff4emu.so, which hosts the bundled Snes9x 1.63 libretro
 * core. Nothing here knows about RetroArch or any other emulator: the ROM and
 * its .srm are handed straight in from our FF4_FE library.
 *
 * Joypad ids match the libretro constants (RETRO_DEVICE_ID_JOYPAD_*).
 */
object EmulatorBridge {

    // libretro joypad ids
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

    init {
        System.loadLibrary("ff4emu")
        // Preload the core by name so the bridge can find it even when native
        // libs are mapped straight from the APK (extractNativeLibs=false).
        try {
            System.loadLibrary("retro")
        } catch (_: Throwable) {
        }
    }

    /** Loads the core from [corePath]; sets the core's system/save dirs. */
    external fun nativeInit(corePath: String, systemDir: String, saveDir: String): Boolean

    /** Pixel buffer the core writes into (RGB565 = width*height*2 bytes). */
    external fun nativeSetVideoBuffer(buffer: ByteBuffer)

    external fun nativeLoadRom(romPath: String, savePath: String): Boolean

    /** Runs exactly one frame. */
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

    /** region: 0 = SRAM, 1 = WRAM (live game state). Returns bytes copied. */
    external fun nativeReadMemory(region: Int, offset: Int, target: ByteArray): Int

    /** Live battery-SRAM snapshot (for the tracker). Returns bytes copied. */
    external fun nativeCopySaveRam(target: ByteArray): Int

    external fun nativeSaveState(path: String): Boolean
    external fun nativeLoadState(path: String): Boolean

    external fun nativeSaveRamSize(): Int
    external fun nativeFlushSaveRam(savePath: String): Boolean

    external fun nativeUnload()
}