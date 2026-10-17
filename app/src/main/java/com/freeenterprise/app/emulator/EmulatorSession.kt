package com.freeenterprise.app.emulator

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Owns the emulator thread: one frame per loop (run → blit → queue audio),
 * paced to 60.0988 Hz. Started when the player screen appears, stopped when it
 * leaves. All native calls happen on this one thread.
 */
private const val TAG = "FF4Emu"

private fun LOGE(msg: String) = Log.e(TAG, msg)

class EmulatorSession(
    private val context: Context,
    private val corePath: String,
    private val systemDir: String,
    private val romPath: String,
    private val savePath: String,
    private val surface: Surface,
    private val onFirstFrame: () -> Unit,
    private val onError: (String) -> Unit,
) {

    /** NTSC SNES frame rate. */
    private val frameNanos = (1_000_000_000.0 / 60.0988).toLong()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Commands the UI thread can hand to the emulator thread (core is not thread safe). */
    private val commands = java.util.concurrent.LinkedBlockingQueue<() -> Unit>()
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var fastForward = false

    /** Bitmask mirror so setUpOrDown can avoid redundant calls. */
    private var digitalState = 0

    private lateinit var videoBuffer: java.nio.ByteBuffer
    private var width = 256
    private var height = 224

    private val srcRect = android.graphics.Rect()
    private val dstRect = android.graphics.Rect()

    /** SNES pixels are 8:7, which gives the classic ~4:3 picture on screen. */
    private val pixelAspect = 8f / 7f
    private val screenPaint = android.graphics.Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    private var audio: AudioTrack? = null
    private var audioShorts = ShortArray(4096 * 2)

    private var firstFrameSent = false
    private var frameCounter = 0L
    private var frameBitmap: android.graphics.Bitmap? = null

    fun start() {
        val t = HandlerThread("ff4emu").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        handler!!.post { loop() }
    }

    fun pause() {
        paused = true
        flushSave()
    }

    fun resume() {
        paused = false
    }

    /** Stops the loop, writes the battery save, releases audio. */
    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        flushSave()
        try {
            audio?.pause()
            audio?.flush()
            audio?.release()
        } catch (_: Exception) {
        }
        audio = null
        EmulatorBridge.nativeUnload()
    }

    /** Save/load state run on the emulator thread; results come back on the UI thread. */
    fun saveState(path: String, onDone: (Boolean) -> Unit) {
        commands.add {
            val ok = EmulatorBridge.nativeSaveState(path)
            mainHandler.post { onDone(ok) }
        }
    }

    /** Live memory read on the emulator thread. region 1 = WRAM (game state). */
    fun readMemory(region: Int, offset: Int, length: Int, onResult: (ByteArray?) -> Unit) {
        commands.add {
            val buf = ByteArray(length)
            val n = EmulatorBridge.nativeReadMemory(region, offset, buf)
            mainHandler.post { onResult(if (n > 0) buf.copyOf(n) else null) }
        }
    }

    /** Live SRAM snapshot, read on the emulator thread (tracker data source). */
    fun readSaveRam(onResult: (ByteArray?) -> Unit) {
        commands.add {
            val size = EmulatorBridge.nativeSaveRamSize()
            val bytes = if (size > 0) {
                val buf = ByteArray(size)
                val n = EmulatorBridge.nativeCopySaveRam(buf)
                if (n > 0) buf.copyOf(n) else null
            } else null
            mainHandler.post { onResult(bytes) }
        }
    }

    fun loadState(path: String, onDone: (Boolean) -> Unit) {
        commands.add {
            val ok = EmulatorBridge.nativeLoadState(path)
            mainHandler.post { onDone(ok) }
        }
    }

    fun setUp(id: Int) = EmulatorBridge.nativeSetButton(id, true)
    fun setDown(id: Int) = EmulatorBridge.nativeSetButton(id, false)

    /** Hold-to-fast-forward (runs extra frames per tick, audio at 1x). */
    fun setFastForward(on: Boolean) {
        fastForward = on
    }

    /** For analogue sticks / on-screen controls: only emits on a change. */
    fun setUpOrDown(id: Int, pressed: Boolean) {
        val current = ((digitalState shr id) and 1) == 1
        if (current == pressed) return
        if (pressed) digitalState = digitalState or (1 shl id) else digitalState = digitalState and (1 shl id).inv()
        EmulatorBridge.nativeSetButton(id, pressed)
    }

    fun flushSave() {
        try {
            if (EmulatorBridge.nativeSaveRamSize() > 0) {
                EmulatorBridge.nativeFlushSaveRam(savePath)
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ loop

    private fun loop() {
        if (!EmulatorBridge.nativeInit(corePath, systemDir, savePath)) {
            val msg = EmulatorBridge.nativeLastError() ?: "unknown error"
            LOGE("init failed: $msg")
            onError("Core failed to load.\n$msg")
            return
        }

        // Allocate a video buffer big enough for 512x478 RGB565 and hand it to
        // the core once (the core writes frames straight into it).
        videoBuffer = java.nio.ByteBuffer.allocateDirect(512 * 478 * 4)
        EmulatorBridge.nativeSetVideoBuffer(videoBuffer)

        if (!EmulatorBridge.nativeLoadRom(romPath, savePath)) {
            val msg = EmulatorBridge.nativeLastError() ?: "unknown error"
            LOGE("load failed: $msg")
            onError("The core could not load this ROM.\n$msg")
            return
        }

        if (EmulatorBridge.nativeUsesRgb565()) {
            width = EmulatorBridge.nativeVideoWidth().takeIf { it > 0 } ?: 256
            height = EmulatorBridge.nativeVideoHeight().takeIf { it > 0 } ?: 224
        }

        setupAudio(32000)

        running = true
        var next = System.nanoTime()

        while (running) {
            // Drain any queued UI commands (save/load state) before this frame.
            while (true) {
                val cmd = commands.poll() ?: break
                try {
                    cmd()
                } catch (e: Exception) {
                    LOGE("command failed: ${e.message}")
                }
            }

            if (paused) {
                Thread.sleep(16)
                next = System.nanoTime()
                continue
            }

            val extra = if (fastForward) 2 else 0     // 3x while held
            for (f in 0..extra) {
                EmulatorBridge.nativeRun()

                val w = EmulatorBridge.nativeVideoWidth()
                val h = EmulatorBridge.nativeVideoHeight()
                if (w > 0 && h > 0) {
                    width = w
                    height = h
                }

                val last = (f == extra)
                if (EmulatorBridge.nativeFrameReady() && last) {
                    srcRect.set(0, 0, width, height)
                    blit()
                    if (!firstFrameSent) {
                        firstFrameSent = true
                        onFirstFrame()
                        try {
                            audio?.play()
                        } catch (_: Exception) {
                        }
                    }
                }
                if (last) queueAudio() else EmulatorBridge.nativeClearAudio()
            }

            // Periodic battery-save flush (every ~5 s) so progress survives a crash.
            if (++frameCounter % 300L == 0L) flushSave()

            // pace
            next += frameNanos
            val sleep = next - System.nanoTime()
            if (sleep > 0) {
                val ms = sleep / 1_000_000
                val ns = (sleep % 1_000_000).toInt()
                try {
                    Thread.sleep(ms, ns)
                } catch (_: InterruptedException) {
                    break
                }
            } else {
                next = System.nanoTime()
            }
        }
    }

    private fun blit() {
        try {
            val canvas = surface.lockCanvas(null) ?: return
            try {
                val cw = canvas.width
                val ch = canvas.height

                var bmp = frameBitmap
                if (bmp == null || bmp.width != width || bmp.height != height) {
                    bmp = android.graphics.Bitmap.createBitmap(
                        width, height, android.graphics.Bitmap.Config.RGB_565
                    )
                    frameBitmap = bmp
                }
                videoBuffer.rewind()
                bmp.copyPixelsFromBuffer(videoBuffer)

                // Fit the picture to the screen (largest size that fits),
                // then centre it; letterboxing stays black.
                val displayW = width * pixelAspect
                val scale = minOf(cw / displayW, ch / height.toFloat())
                val dw = (displayW * scale).toInt().coerceAtMost(cw)
                val dh = (height * scale).toInt().coerceAtMost(ch)
                val left = (cw - dw) / 2
                val top = (ch - dh) / 2
                dstRect.set(left, top, left + dw, top + dh)

                canvas.drawColor(android.graphics.Color.BLACK)
                canvas.drawBitmap(bmp, null, dstRect, screenPaint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (_: Exception) {
        }
    }

    // ----------------------------------------------------------------- audio

    private fun setupAudio(sampleRate: Int) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audio = track
    }

    private fun queueAudio() {
        val frames = EmulatorBridge.nativeAudioFrames()
        if (frames <= 0) return
        val needed = frames * 2
        if (audioShorts.size < needed) audioShorts = ShortArray(needed)
        EmulatorBridge.nativeCopyAudio(audioShorts, frames)
        EmulatorBridge.nativeClearAudio()
        try {
            audio?.write(audioShorts, 0, needed)
        } catch (_: Exception) {
        }
    }
}