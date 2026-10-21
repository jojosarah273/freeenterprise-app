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

    private companion object {
        /** Frames per displayed frame while fast-forward is held. */
        const val FAST_FORWARD_FRAMES = 4
    }

    private val savePath: String

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

        savePath = File(workDir, File(romPath).nameWithoutExtension + ".srm").absolutePath
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
     * Run more frames than we display. The audio device still paces us, so holding
     * this runs the game faster rather than dropping frames.
     */
    var fastForward: Boolean = false

    /** Steps one frame (or [frames] while fast-forwarding), then publishes the picture. */
    fun step() {
        val started = System.nanoTime()
        val count = if (fastForward) FAST_FORWARD_FRAMES else 1
        repeat(count) { EmulatorBridge.nativeRun() }
        refreshFrame()
        pumpAudio()
        if (line == null) {
            val elapsed = System.nanoTime() - started
            val remaining = frameNanos - elapsed
            if (remaining > 0) Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
        }
    }

    fun setButton(id: Int, pressed: Boolean) = EmulatorBridge.nativeSetButton(id, pressed)

    /** The picture at native resolution, RGB. */
    fun image(): BufferedImage = frame

    /** Reads the live game state, as the tracker does on Android. */
    fun readWram(): ByteArray {
        val out = ByteArray(0x800)
        EmulatorBridge.nativeReadMemory(1, 0x1000, out)
        return out
    }

    override fun close() {
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

    private fun pumpAudio() {
        val sink = line ?: return
        val frames = EmulatorBridge.nativeAudioFrames()
        if (frames <= 0) return
        val n = minOf(frames, audioShorts.size / 2)
        EmulatorBridge.nativeCopyAudio(audioShorts, n)
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
