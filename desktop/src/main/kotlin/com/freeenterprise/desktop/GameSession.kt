package com.freeenterprise.desktop

import com.freeenterprise.app.emulator.EmulatorBridge
import com.freeenterprise.app.emulator.EmulatorBridge.Button
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Runs one ROM: load, step a frame, hand the picture to the UI, feed the sound card.
 *
 * The loop is paced by the audio device rather than by a timer: writing samples
 * blocks once the device's buffer is full, which is the correct clock for an
 * emulator and avoids both drift and buffer underruns. If no sound device exists
 * (a headless run), it falls back to sleeping out the frame.
 *
 * Everything here happens on the caller's thread; the native core is not thread safe.
 */
class GameSession(
    romPath: String,
    private val nativeDir: File,
    workDir: File,
    /** True for --diag; FE_DIAG=1 does the same from the environment. */
    diagnose: Boolean = false,
) : AutoCloseable {

    val width: Int
    val height: Int
    private val rgb565: Boolean

    private val video: ByteBuffer
    private val frame: BufferedImage
    private val pixels: IntArray

    private val line: SourceDataLine?
    private val audioShorts = ShortArray(8192 * 2)
    private val audioBytes = ByteArray(8192 * 2 * 2)
    private val frameNanos = (1_000_000_000.0 / 60.0988).toLong()

    companion object {
        /** Frames per displayed frame while fast-forward is held. */
        private const val FAST_FORWARD_FRAMES = 4

        /** ...and how much faster than real time that is meant to run. */
        private const val FAST_FORWARD_SPEED = 4

        /** Where this seed's state lives: beside the ROM, named as the app names it. */
        fun stateFile(romPath: String): File =
            File(File(romPath).parentFile, File(romPath).nameWithoutExtension + ".state")
    }

    private val savePath: String
    private var closed = false

    init {
        EmulatorBridge.load(nativeDir)
        workDir.mkdirs()

        val corePath = File(nativeDir, "libretro.so").absolutePath
        if (!EmulatorBridge.nativeInit(corePath, workDir.absolutePath, workDir.absolutePath)) {
            throw IllegalStateException("the core would not load: ${EmulatorBridge.nativeLastError()}")
        }

        // The core writes frames straight into this buffer: room for 512x478 RGB565.
        video = ByteBuffer.allocateDirect(512 * 478 * 4).order(ByteOrder.LITTLE_ENDIAN)
        EmulatorBridge.nativeSetVideoBuffer(video)

        // Beside the ROM, like the app: <name>.srm in the seed's own folder.
        val romFile = File(romPath).absoluteFile
        savePath = File(romFile.parentFile, romFile.nameWithoutExtension + ".srm").absolutePath
        if (!EmulatorBridge.nativeLoadRom(romPath, savePath)) {
            throw IllegalStateException("the core refused the ROM: ${EmulatorBridge.nativeLastError()}")
        }

        width = EmulatorBridge.nativeVideoWidth().takeIf { it > 0 } ?: 256
        height = EmulatorBridge.nativeVideoHeight().takeIf { it > 0 } ?: 224
        rgb565 = EmulatorBridge.nativeUsesRgb565()
        frame = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        pixels = IntArray(width * height)
        line = openAudio(32000)
    }

    /**
     * Run several frames per step. Audio is dropped while forwarding: writing it would
     * pace the loop to real time and cancel the point of forwarding (and sound at 4x is
     * noise, which is why RetroArch mutes it too).
     */
    var fastForward: Boolean = false

    /**
     * Set FE_DIAG=1 to print a line every two seconds: how long each part of a frame
     * takes and how much audio is waiting. A pad-less, audio-less machine still prints,
     * which is what makes it usable as a bug report.
     */
    private val diagnosing = diagnose || System.getenv("FE_DIAG") != null
    private var diagFrames = 0
    private var diagStartNanos = 0L
    private var diagRunNanos = 0L
    private var diagRefreshNanos = 0L
    private var diagAudioNanos = 0L
    private var diagAudioMax = 0

    /** Steps one frame (or [frames] while fast-forwarding), then publishes the picture. */
    fun step() {
        val started = System.nanoTime()
        val count = if (fastForward) FAST_FORWARD_FRAMES else 1
        repeat(count) { EmulatorBridge.nativeRun() }
        val afterRun = System.nanoTime()
        refreshFrame()
        val afterRefresh = System.nanoTime()
        if (fastForward) EmulatorBridge.nativeClearAudio() else pumpAudio()
        if (diagnosing) {
            val afterAudio = System.nanoTime()
            diagRunNanos += afterRun - started
            diagRefreshNanos += afterRefresh - afterRun
            diagAudioNanos += afterAudio - afterRefresh
            diagAudioMax = maxOf(diagAudioMax, pendingAudioFrames())
            diagFrames += count
            if (diagStartNanos == 0L) diagStartNanos = started
            val elapsed = afterAudio - diagStartNanos
            if (elapsed >= 2_000_000_000L) {
                val fps = diagFrames * 1e9 / elapsed
                System.err.println(
                    "FE_DIAG fps=%.1f run=%.2fms refresh=%.2fms audio=%.2fms pendingAudioMax=%d device=%s".format(
                        fps,
                        diagRunNanos / 1e6 / diagFrames,
                        diagRefreshNanos / 1e6 / diagFrames,
                        diagAudioNanos / 1e6 / diagFrames,
                        diagAudioMax,
                        if (line == null) "none" else "open",
                    ),
                )
                diagFrames = 0; diagStartNanos = 0L
                diagRunNanos = 0L; diagRefreshNanos = 0L; diagAudioNanos = 0L
                diagAudioMax = 0
            }
        }
        // A real audio device paces us through the blocking write; otherwise - and
        // while forwarding, when audio is dropped - pace by the clock, allowing [count]
        // frames' worth of time.
        // Playing: one frame per frame-time. Forwarding: several frames in the time of
        // them, so it actually runs faster instead of just showing fewer pictures.
        if (line == null || fastForward) {
            val budget = if (fastForward) frameNanos * count / FAST_FORWARD_SPEED else frameNanos * count
            val remaining = budget - (System.nanoTime() - started)
            if (remaining > 0) Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
        }
    }

    fun setButton(id: Int, pressed: Boolean) = EmulatorBridge.nativeSetButton(id, pressed)

    /** Save the emulator state to [file]; returns false and says why on failure. */
    fun saveState(file: File): Boolean {
        file.parentFile?.mkdirs()
        return EmulatorBridge.nativeSaveState(file.absolutePath)
    }

    /** Load an emulator state; the core refuses a state from another core version. */
    fun loadState(file: File): Boolean =
        file.isFile && EmulatorBridge.nativeLoadState(file.absolutePath)



    /** The picture at native resolution, RGB. */
    fun image(): BufferedImage = frame

    /** Reads the live game state, as the tracker does on Android. */
    fun readWram(): ByteArray {
        val out = ByteArray(0x800)
        EmulatorBridge.nativeReadMemory(1, 0x1000, out)
        return out
    }

    /** Idempotent: the emulator loop and the quit path can both ask for this. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            EmulatorBridge.nativeFlushSaveRam(savePath)
            EmulatorBridge.nativeUnload()
        } finally {
            line?.stop()
            line?.close()
        }
    }

    private fun refreshFrame() {
        if (!EmulatorBridge.nativeFrameReady()) return
        video.position(0)
        val shorts = video.asShortBuffer()
        val count = minOf(width * height, shorts.capacity())
        if (rgb565) {
            for (i in 0 until count) {
                val v = shorts.get(i).toInt() and 0xFFFF
                val r = (v shr 11) and 0x1F
                val g = (v shr 5) and 0x3F
                val b = v and 0x1F
                pixels[i] = ((r shl 3 or (r shr 2)) shl 16) or ((g shl 2 or (g shr 4)) shl 8) or (b shl 3 or (b shr 2))
            }
        } else {
            for (i in 0 until count) {
                val v = shorts.get(i).toInt()
                pixels[i] = v
            }
        }
        frame.setRGB(0, 0, width, height, pixels, 0, width)
    }

    /** Frames of audio waiting in the core. Should stay near one frame's worth (~534). */
    fun pendingAudioFrames(): Int = EmulatorBridge.nativeAudioFrames()

    private fun pumpAudio() {
        val frames = pendingAudioFrames()
        if (frames <= 0) return
        val sink = line
        if (sink == null) {
            // No device: discard, so the core's buffer cannot grow for the session.
            EmulatorBridge.nativeClearAudio()
            return
        }
        val n = minOf(frames, audioShorts.size / 2)
        EmulatorBridge.nativeCopyAudio(audioShorts, n)
        EmulatorBridge.nativeClearAudio()
        var b = 0
        for (i in 0 until n * 2) {
            val s = audioShorts[i].toInt()
            audioBytes[b++] = (s and 0xFF).toByte()
            audioBytes[b++] = ((s shr 8) and 0xFF).toByte()
        }
        sink.write(audioBytes, 0, b)
    }

    private fun openAudio(sampleRate: Int): SourceDataLine? = try {
        val format = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
        val opened = AudioSystem.getSourceDataLine(format)
        opened.open(format, sampleRate / 4 * 4)
        opened.start()
        opened
    } catch (e: Exception) {
        println("no audio device (${e.message}); running silently")
        null
    }
}
