package com.freeenterprise.desktop

import com.freeenterprise.app.emulator.EmulatorBridge.Button
import com.freeenterprise.ff4.Ff4MenuItem
import com.freeenterprise.ff4.Ff4MenuModel
import com.freeenterprise.ff4.Ff4Palette
import com.freeenterprise.ff4.Ff4Scale
import java.awt.Graphics
import java.awt.Toolkit
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.system.exitProcess

/**
 * The Linux dev build.
 *
 *   ./gradlew :desktop:run                              the interface on its own
 *   ./gradlew :desktop:run --args="--export DIR"        PNGs of the screens
 *   ./gradlew :desktop:run --args="--rom game.smc"      play a seed, fullscreen
 *   ./gradlew :desktop:run --args="--rom game.smc --frames 600 --export DIR"
 *                                                       run headlessly and write a PNG
 *
 * The last form is the one that makes this verifiable without a screen: the game
 * picture and the interface are composed by the same canvas the window uses, so the
 * PNG is what the window shows.
 */
fun main(args: Array<String>) {
    val metrics = AwtTextMetrics()

    var rom: String? = null
    var export: String? = null
    var work: String? = null
    var frames = 0
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--rom" -> rom = args.getOrNull(++i)
            "--export" -> export = args.getOrNull(++i)
            "--work" -> work = args.getOrNull(++i)
            "--frames" -> frames = args.getOrNull(++i)?.toIntOrNull() ?: 0
            "--help", "-h" -> {
                println("usage: --rom ROM [--frames N] [--export DIR] [--work DIR]")
                return
            }
            else -> {
                System.err.println("unknown argument: ${args[i]}")
                exitProcess(2)
            }
        }
        i++
    }

    val workDir = File(work ?: (System.getProperty("user.home") + "/.freeenterprise"))

    when {
        rom != null && export != null -> runHeadless(rom, frames, File(export), workDir, metrics)
        rom != null -> runGame(rom, workDir, metrics)
        export != null -> exportScreens(File(export), metrics)
        else -> runInterfaceWindow(metrics)
    }
}

private fun menuItems() = listOf(
    Ff4MenuItem("Play Free Enterprise", "3 seed(s)"),
    Ff4MenuItem("Generate Seed"),
    Ff4MenuItem("Check for updates", "v0.1.25-dev"),
    Ff4MenuItem("Settings"),
    Ff4MenuItem("Exit"),
)

private fun pauseItems() = listOf(
    Ff4MenuItem("Resume"),
    Ff4MenuItem("Tracker", "L2 / Tab"),
    Ff4MenuItem("Save state", "F5"),
    Ff4MenuItem("Exit to menu", "Esc"),
)

/** The core libraries, built into the repo by `make -C desktop/native`. */
private fun findNativeDir(): File {
    val candidates = listOf(
        "build/native",              // the copy made by the Gradle build
        "desktop/native/build",      // from the repo root
        "../native/build",           // from the desktop module
        "native/build",
    )
    for (path in candidates) {
        val dir = File(path)
        if (File(dir, "libff4emu.so").isFile && File(dir, "libretro.so").isFile) return dir
    }
    error("native libraries not found; run: make -C desktop/native")
}

private fun exportScreens(out: File, metrics: AwtTextMetrics) {
    val width = 1280
    val height = 720
    val scale = Ff4Scale(minOf(width, height), 1f)
    val renderer = ScreenRenderer(scale, metrics)
    val canvas = SoftwareCanvas(width, height, metrics, shadowOffsetPx = scale.unit * 0.5f)

    for (selected in listOf(0, 2, 4)) {
        canvas.clear(Ff4Palette.BLACK)
        canvas.draw(
            renderer.mainMenu(
                width, height, menuItems(), selected,
                status = listOf("Free Enterprise 4.7.0.Gale", "3 seed(s) ready"),
                hint = "A  Select      B  Exit",
            ),
        )
        canvas.writePng(File(out, "main-menu-$selected.png"))
    }
    for (progress in listOf(0, 45, 100)) {
        canvas.clear(Ff4Palette.BLACK)
        canvas.draw(
            renderer.progressOverlay(
                width, height, "GENERATING SEED", progress, "Shuffling treasures", "0:%02d".format(progress / 2),
            ),
        )
        canvas.writePng(File(out, "generating-$progress.png"))
    }
    println("wrote screen images to ${out.absolutePath}")
}

/** Runs the emulator without a window and writes what the screen would show. */
private fun runHeadless(rom: String, frames: Int, out: File, workDir: File, metrics: AwtTextMetrics) {
    val session = GameSession(rom, findNativeDir(), workDir)
    try {
        repeat(maxOf(frames, 1)) { session.step() }

        // Render at a realistic screen size with the game scaled into it, because
        // that is what the window shows: rendering at the game's own 256x224 makes
        // the interface's windows overflow, which is a property of the test, not of
        // the real thing.
        val width = 1280
        val height = 720
        val scale = Ff4Scale(minOf(width, height), 1f)
        val renderer = ScreenRenderer(scale, metrics)
        val canvas = SoftwareCanvas(width, height, metrics, shadowOffsetPx = scale.unit * 0.5f)
        val picture = gameRect(width, height, session.width, session.height)
        val wram = session.readWram()

        for (variant in listOf("game", "game-pause", "game-tracker")) {
            canvas.clear(Ff4Palette.BLACK)
            canvas.drawGameFrame(session.image(), picture)
            when (variant) {
                "game-pause" -> canvas.draw(renderer.pauseMenu(width, height, pauseItems(), 0, "PAUSED"))
                "game-tracker" -> canvas.draw(renderer.tracker(width, height, wram))
            }
            canvas.writePng(File(out, "$variant.png"))
        }
        println("ran $frames frame(s) of $rom; wrote game, game-pause and game-tracker PNGs to ${out.absolutePath}")
    } finally {
        session.close()
    }
}

private fun runGame(rom: String, workDir: File, metrics: AwtTextMetrics) {
    val screen = Toolkit.getDefaultToolkit().screenSize
    val width = screen.width
    val height = screen.height
    val scale = Ff4Scale(minOf(width, height), 1f)
    val renderer = ScreenRenderer(scale, metrics)
    val canvas = SoftwareCanvas(width, height, metrics, shadowOffsetPx = scale.unit * 0.5f)

    println("loading $rom")
    val session = GameSession(rom, findNativeDir(), workDir)
    val pause = Ff4MenuModel(pauseItems())

    // Shared between the key listener and the emulator loop. The emulator loop owns
    // every native call, including reading WRAM: the core is not thread safe, so the
    // UI only ever publishes intent and reads finished pictures.
    val ui = UiState()

    val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            g.drawImage(canvas.image, 0, 0, null)
        }
    }
    panel.isFocusable = true

    /** Composed on the emulator thread, so the picture and the interface never race. */
    fun compose() {
        canvas.clear(Ff4Palette.BLACK)
        canvas.drawGameFrame(
            session.image(),
            gameRect(width, height, session.width, session.height),
        )
        when (ui.overlay) {
            Overlay.PAUSE -> canvas.draw(
                renderer.pauseMenu(width, height, pause.items, pause.selected, "PAUSED"),
            )
            Overlay.TRACKER -> canvas.draw(renderer.tracker(width, height, ui.wram))
            Overlay.NONE -> Unit
        }
        panel.repaint()
    }

    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (ui.overlay != Overlay.NONE) {
                when (e.keyCode) {
                    KeyEvent.VK_UP -> if (pause.moveSelection(-1)) panel.repaint()
                    KeyEvent.VK_DOWN -> if (pause.moveSelection(1)) panel.repaint()
                    KeyEvent.VK_ENTER -> when (pause.activationTarget()) {
                        0 -> ui.overlay = Overlay.NONE
                        1 -> ui.overlay = Overlay.TRACKER
                        2 -> session.setButton(Button.START, true).also { session.setButton(Button.START, false) }
                        else -> exitProcess(0)
                    }
                    KeyEvent.VK_ESCAPE, KeyEvent.VK_TAB -> ui.overlay = Overlay.NONE
                }
                return
            }
            when (e.keyCode) {
                KeyEvent.VK_ESCAPE -> ui.overlay = Overlay.PAUSE
                KeyEvent.VK_TAB -> ui.overlay = Overlay.TRACKER
                else -> keyToButton(e.keyCode)?.let { id ->
                    if (ui.overlay == Overlay.NONE) session.setButton(id, true)
                }
            }
        }

        override fun keyReleased(e: KeyEvent) {
            keyToButton(e.keyCode)?.let { session.setButton(it, false) }
        }
    })

    val frame = JFrame("Free Enterprise - dev build")
    frame.isUndecorated = true
    frame.contentPane.add(panel)
    frame.isVisible = true
    frame.graphicsConfiguration.device.setFullScreenWindow(frame)
    panel.requestFocusInWindow()

    val loop = Thread {
        try {
            while (!Thread.currentThread().isInterrupted) {
                session.step()
                if (ui.overlay == Overlay.TRACKER) ui.wram = session.readWram()
                compose()
            }
        } catch (t: Throwable) {
            System.err.println("emulator loop stopped: ${t.message}")
        } finally {
            session.close()
        }
    }
    loop.isDaemon = true
    loop.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        loop.interrupt()
        frame.dispose()
    })
}

/** Intent published by the UI thread and consumed by the emulator thread. */
private class UiState {
    @Volatile var overlay: Overlay = Overlay.NONE
    @Volatile var wram: ByteArray = ByteArray(0x800)
}

private enum class Overlay { NONE, PAUSE, TRACKER }

private fun keyToButton(code: Int): Int? = when (code) {
    KeyEvent.VK_UP -> Button.UP
    KeyEvent.VK_DOWN -> Button.DOWN
    KeyEvent.VK_LEFT -> Button.LEFT
    KeyEvent.VK_RIGHT -> Button.RIGHT
    KeyEvent.VK_X -> Button.A
    KeyEvent.VK_Z -> Button.B
    KeyEvent.VK_S -> Button.Y
    KeyEvent.VK_D -> Button.X
    KeyEvent.VK_ENTER -> Button.START
    KeyEvent.VK_SHIFT -> Button.SELECT
    KeyEvent.VK_Q -> Button.L
    KeyEvent.VK_W -> Button.R
    else -> null
}

/** Draws the interface on its own, with working navigation. */
private fun runInterfaceWindow(metrics: AwtTextMetrics) {
    val screen = Toolkit.getDefaultToolkit().screenSize
    val width = screen.width
    val height = screen.height
    val scale = Ff4Scale(minOf(width, height), 1f)
    val renderer = ScreenRenderer(scale, metrics)
    val canvas = SoftwareCanvas(width, height, metrics, shadowOffsetPx = scale.unit * 0.5f)
    val model = Ff4MenuModel(menuItems())

    val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            g.drawImage(canvas.image, 0, 0, null)
        }
    }
    panel.isFocusable = true

    fun redraw() {
        canvas.clear(Ff4Palette.BLACK)
        canvas.draw(
            renderer.mainMenu(
                width, height, model.items, model.selected,
                status = listOf("Free Enterprise 4.7.0.Gale", "${model.size} entries"),
                hint = "Up/Down  Move      Enter  Select      Esc  Quit",
            ),
        )
        panel.repaint()
    }

    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            when (e.keyCode) {
                KeyEvent.VK_UP, KeyEvent.VK_W -> if (model.moveSelection(-1)) redraw()
                KeyEvent.VK_DOWN, KeyEvent.VK_S -> if (model.moveSelection(1)) redraw()
                KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> println(
                    "selected row ${model.activationTarget()}: ${model.items[model.selected].label}",
                )
                KeyEvent.VK_ESCAPE, KeyEvent.VK_Q -> exitProcess(0)
            }
        }
    })

    val frame = JFrame("Free Enterprise - dev build")
    frame.isUndecorated = true
    frame.contentPane.add(panel)
    frame.isVisible = true
    frame.graphicsConfiguration.device.setFullScreenWindow(frame)
    panel.requestFocusInWindow()
    redraw()
}
