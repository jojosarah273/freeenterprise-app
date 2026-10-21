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
 *   ./gradlew :desktop:run                                   the seed library
 *   ./gradlew :desktop:run --args="--export DIR"             PNGs of the screens
 *   ./gradlew :desktop:run --args="--rom game.smc"           play a ROM directly
 *   ./gradlew :desktop:run --args="--generate NAME"          generate, then play it
 *   ./gradlew :desktop:run --args="--generate NAME --frames 600 --export DIR"
 *                                                            headless, for checking
 *
 * The headless forms exist because they are the only honest way to check this
 * without a screen: the picture and the interface are composed by the same canvas
 * the window uses.
 */
fun main(args: Array<String>) {
    val metrics = AwtTextMetrics()
    val config = Config.from(args)
    if (config.help) {
        println(
            """
            usage: [--library DIR] [--base-rom ROM] [--engine DIR] [--work DIR]
                   [--rom ROM] [--generate SEED] [--flags FLAGS]
                   [--frames N] [--export DIR]
            """.trimIndent(),
        )
        return
    }

    when {
        config.generate != null && config.export != null ->
            runGenerateHeadless(config, metrics)
        config.rom != null && config.export != null ->
            runRomHeadless(config, metrics)
        config.rom != null -> runGame(config.rom, config, metrics)
        config.export != null -> exportScreens(config.export, metrics)
        else -> runApp(config, metrics)
    }
}

/** Where everything lives, resolved once from the command line and the repo layout. */
data class Config(
    val libraryDir: File,
    val workDir: File,
    val engineDir: File?,
    val bridgeFile: File?,
    val baseRom: File?,
    val rom: String?,
    val generate: String?,
    val flags: String,
    val frames: Int,
    val export: File?,
    val nativeDir: File?,
    val help: Boolean,
) {
    companion object {
        fun from(args: Array<String>): Config {
            var library: String? = null
            var work: String? = null
            var engine: String? = null
            var bridge: String? = null
            var baseRom: String? = null
            var rom: String? = null
            var generate: String? = null
            var flags = "Kmain"
            var frames = 0
            var export: String? = null
            var native: String? = null
            var help = false

            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--library" -> library = args.getOrNull(++i)
                    "--work" -> work = args.getOrNull(++i)
                    "--engine" -> engine = args.getOrNull(++i)
                    "--bridge" -> bridge = args.getOrNull(++i)
                    "--base-rom" -> baseRom = args.getOrNull(++i)
                    "--rom" -> rom = args.getOrNull(++i)
                    "--generate" -> generate = args.getOrNull(++i)
                    "--flags" -> flags = args.getOrNull(++i).orEmpty()
                    "--frames" -> frames = args.getOrNull(++i)?.toIntOrNull() ?: 0
                    "--export" -> export = args.getOrNull(++i)
                    "--native" -> native = args.getOrNull(++i)
                    "--help", "-h" -> help = true
                    else -> System.err.println("unknown argument: ${args[i]}")
                }
                i++
            }

            val workDir = File(work ?: (System.getProperty("user.home") + "/.freeenterprise"))
            val libraryDir = File(library ?: (workDir.path + "/library"))
            val engineDir = engine?.let { File(it) } ?: firstExisting(
                "app/src/main/assets/python_ext",
                "../app/src/main/assets/python_ext",
            )
            val bridgeFile = bridge?.let { File(it) } ?: firstExisting(
                "app/src/main/python/fe_bridge.py",
                "../app/src/main/python/fe_bridge.py",
            )
            val baseRomFile = baseRom?.let { File(it) }
                ?: listOf(File(workDir, "base_rom.smc"), File(workDir, "ff2.smc"))
                    .firstOrNull { it.isFile }

            return Config(
                libraryDir = libraryDir,
                workDir = workDir,
                engineDir = engineDir,
                bridgeFile = bridgeFile,
                baseRom = baseRomFile,
                rom = rom,
                generate = generate,
                flags = flags,
                frames = frames,
                // Null unless asked for: a default value here made the export branch
                // always win, so the application itself could never start.
                export = export?.let { File(it) },
                nativeDir = native?.let { File(it) },
                help = help,
            )
        }

        private fun firstExisting(vararg paths: String): File? =
            paths.map { File(it) }.firstOrNull { it.exists() }
    }
}

private fun findNativeDir(config: Config): File {
    val candidates = listOfNotNull(
        config.nativeDir,
        File("build/native"),
        File("desktop/native/build"),
        File("../native/build"),
        File("native/build"),
    )
    for (dir in candidates) {
        if (File(dir, "libff4emu.so").isFile && File(dir, "libretro.so").isFile) return dir
    }
    error("native libraries not found; run: make -C desktop/native, or pass --native DIR")
}

// ------------------------------------------------------------------ the app loop

private enum class Screen { LIBRARY, GENERATING, PLAYING }
private enum class Overlay { NONE, PAUSE, TRACKER }

/** Intent published by the UI thread and consumed by whichever worker is running. */
private class UiState {
    /** Which screen is up. Written by the UI thread, read by the emulator loop. */
    @Volatile var screen: Screen = Screen.LIBRARY
    @Volatile var overlay: Overlay = Overlay.NONE
    @Volatile var wram: ByteArray = ByteArray(0x800)
    @Volatile var progress: EngineProcess.Progress = EngineProcess.Progress(0, "Starting engine", "")
    @Volatile var message: String = ""
}

private fun runApp(config: Config, metrics: AwtTextMetrics) {
    val screen = Toolkit.getDefaultToolkit().screenSize
    val width = screen.width
    val height = screen.height
    val scale = Ff4Scale(minOf(width, height), 1f)
    val renderer = ScreenRenderer(scale, metrics)
    val canvas = SoftwareCanvas(width, height, metrics, shadowOffsetPx = scale.unit * 0.5f)
    val library = SeedLibrary(config.libraryDir).also { it.ensureExists() }

    val ui = UiState()
    var session: GameSession? = null
    val libraryModel = Ff4MenuModel(emptyList())
    val pauseModel = Ff4MenuModel(pauseItems())
    val gameLoop = arrayOfNulls<Thread>(1)

    val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            g.drawImage(canvas.image, 0, 0, null)
        }
    }
    panel.isFocusable = true

    fun libraryItems(): List<Ff4MenuItem> {
        val seeds = library.seeds()
        val rows = seeds.map { Ff4MenuItem(it.name, it.detail) }.toMutableList()
        rows.add(Ff4MenuItem("Generate seed", if (config.baseRom == null) "no base ROM" else "20 seconds or so"))
        rows.add(Ff4MenuItem("Quit"))
        return rows
    }

    fun compose() {
        canvas.clear(Ff4Palette.BLACK)
        when (ui.screen) {
            Screen.LIBRARY -> canvas.draw(
                renderer.mainMenu(
                    width, height, libraryModel.items, libraryModel.selected,
                    status = listOf(
                        "Free Enterprise  ${config.baseRom?.name ?: "no base ROM"}",
                        ui.message.ifBlank { "${library.seeds().size} seed(s) in ${config.libraryDir.path}" },
                    ),
                    hint = "Up/Down  Move      Enter  Select      Esc  Quit",
                ),
            )
            Screen.GENERATING -> canvas.draw(
                renderer.progressOverlay(
                    width, height, "GENERATING SEED",
                    ui.progress.percent, ui.progress.stage, ui.progress.detail.ifBlank { "…" },
                ),
            )
            Screen.PLAYING -> {
                session?.let {
                    canvas.drawGameFrame(it.image(), gameRect(width, height, it.width, it.height))
                }
                when (ui.overlay) {
                    Overlay.PAUSE -> canvas.draw(
                        renderer.pauseMenu(width, height, pauseModel.items, pauseModel.selected, "PAUSED"),
                    )
                    Overlay.TRACKER -> canvas.draw(renderer.tracker(width, height, ui.wram))
                    Overlay.NONE -> Unit
                }
            }
        }
        panel.repaint()
    }

    fun stopGame() {
        gameLoop[0]?.interrupt()
        session?.close()
        session = null
        ui.overlay = Overlay.NONE
    }

    fun openLibrary() {
        stopGame()
        libraryModel.reset(libraryItems())
        ui.screen = Screen.LIBRARY
        compose()
    }

    fun play(rom: File) {
        stopGame()
        try {
            session = GameSession(rom.absolutePath, findNativeDir(config), config.workDir)
        } catch (t: Throwable) {
            ui.message = "could not start ${rom.name}: ${t.message}"
            ui.screen = Screen.LIBRARY
            compose()
            return
        }
        ui.screen = Screen.PLAYING
        ui.message = "playing ${rom.name}"
        val loop = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    session?.step()
                    if (ui.overlay == Overlay.TRACKER) ui.wram = session?.readWram() ?: ByteArray(0x800)
                    compose()
                }
            } catch (t: Throwable) {
                System.err.println("emulator loop stopped: ${t.message}")
            }
        }
        loop.isDaemon = true
        gameLoop[0] = loop
        loop.start()
    }

    fun startGeneration(seedName: String) {
        val engine = config.engineDir
        val bridge = config.bridgeFile
        val baseRom = config.baseRom
        if (engine == null || bridge == null) {
            ui.message = "engine not found (expected app/src/main/assets/python_ext and app/src/main/python/fe_bridge.py)"
            compose()
            return
        }
        if (baseRom == null) {
            ui.message = "no base ROM: pass --base-rom /path/to/ff2.smc (your own FF2 US v1.0/v1.1)"
            compose()
            return
        }
        ui.screen = Screen.GENERATING
        ui.progress = EngineProcess.Progress(0, "Starting engine", "")
        compose()

        val worker = Thread {
            try {
                val result = EngineProcess(engine, bridge, baseRom, File(config.workDir, "cache"))
                    .generate(seedName, config.flags, config.libraryDir, File(config.workDir, "progress.txt")) {
                        ui.progress = it
                        compose()
                    }
                ui.message = "generated ${result.seed} in %.1fs  (${result.verification})".format(result.seconds)
            } catch (t: Throwable) {
                ui.message = "generation failed: ${t.message}"
            }
            openLibrary()
        }
        worker.isDaemon = true
        worker.start()
    }

    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            when (ui.screen) {
                Screen.LIBRARY -> {
                    when (e.keyCode) {
                        KeyEvent.VK_UP, KeyEvent.VK_W -> if (libraryModel.moveSelection(-1)) compose()
                        KeyEvent.VK_DOWN, KeyEvent.VK_S -> if (libraryModel.moveSelection(1)) compose()
                        KeyEvent.VK_ENTER -> {
                            val index = libraryModel.activationTarget() ?: return
                            val seeds = library.seeds()
                            when {
                                index < seeds.size -> play(seeds[index].rom)
                                index == seeds.size -> {
                                    val name = "SEED%04d".format(System.currentTimeMillis() % 10000)
                                    startGeneration(name)
                                }
                                else -> exitProcess(0)
                            }
                        }
                        KeyEvent.VK_ESCAPE, KeyEvent.VK_Q -> exitProcess(0)
                    }
                }
                Screen.PLAYING -> {
                    if (ui.overlay != Overlay.NONE) {
                        when (e.keyCode) {
                            KeyEvent.VK_UP -> if (pauseModel.moveSelection(-1)) compose()
                            KeyEvent.VK_DOWN -> if (pauseModel.moveSelection(1)) compose()
                            KeyEvent.VK_ENTER -> when (pauseModel.activationTarget()) {
                                0 -> ui.overlay = Overlay.NONE
                                1 -> ui.overlay = Overlay.TRACKER
                                3 -> openLibrary()
                            }
                            KeyEvent.VK_ESCAPE, KeyEvent.VK_TAB -> ui.overlay = Overlay.NONE
                        }
                        return
                    }
                    when (e.keyCode) {
                        KeyEvent.VK_ESCAPE -> ui.overlay = Overlay.PAUSE
                        KeyEvent.VK_TAB -> ui.overlay = Overlay.TRACKER
                        else -> keyToButton(e.keyCode)?.let { session?.setButton(it, true) }
                    }
                }
                Screen.GENERATING -> Unit
            }
        }

        override fun keyReleased(e: KeyEvent) {
            keyToButton(e.keyCode)?.let { session?.setButton(it, false) }
        }
    })

    val frame = JFrame("Free Enterprise - dev build")
    frame.isUndecorated = true
    frame.contentPane.add(panel)
    frame.isVisible = true
    frame.graphicsConfiguration.device.setFullScreenWindow(frame)
    panel.requestFocusInWindow()

    Runtime.getRuntime().addShutdownHook(Thread {
        stopGame()
        frame.dispose()
    })

    openLibrary()
}

// ------------------------------------------------------------------ headless runs

/** Generates a seed and writes what the screen would show, without a window. */
private fun runGenerateHeadless(config: Config, metrics: AwtTextMetrics) {
    val engine = config.engineDir ?: error("engine not found (--engine)")
    val bridge = config.bridgeFile ?: error("bridge not found (--bridge)")
    val baseRom = config.baseRom ?: error("no base ROM (--base-rom)")

    println("generating ${config.generate} with flags '${config.flags}'")
    val result = EngineProcess(engine, bridge, baseRom, File(config.workDir, "cache"))
        .generate(config.generate!!, config.flags, config.libraryDir, File(config.workDir, "progress.txt")) {
            print("\r  ${it.percent}% ${it.stage} ${it.detail}          ")
        }
    println()
    println("  ${result.rom.name}  in %.1fs  verification: ${result.verification}".format(result.seconds))

    val frames = if (config.frames > 0) config.frames else 600
    runRomHeadless(config.copy(rom = result.rom.absolutePath), metrics)
}

/** Runs a ROM headlessly and writes the game, pause and tracker screens. */
private fun runRomHeadless(config: Config, metrics: AwtTextMetrics) {
    val rom = config.rom ?: error("no ROM")
    val session = GameSession(rom, findNativeDir(config), config.workDir)
    try {
        repeat(maxOf(config.frames, 1)) { session.step() }

        // A realistic screen, with the game scaled into it: that is what the window
        // shows, and rendering at the game's own 256x224 makes windows overflow.
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
            canvas.writePng(File(config.export ?: File("build/ff4shots"), "$variant.png"))
        }
        println("ran ${config.frames} frame(s) of ${File(rom).name}; wrote PNGs to ${config.export}")
    } finally {
        session.close()
    }
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

private fun menuItems() = listOf(
    Ff4MenuItem("Play Free Enterprise", "3 seed(s)"),
    Ff4MenuItem("Generate Seed"),
    Ff4MenuItem("Check for updates", "v0.1.25-dev"),
    Ff4MenuItem("Settings"),
    Ff4MenuItem("Exit"),
)

private fun pauseItems() = listOf(
    Ff4MenuItem("Resume"),
    Ff4MenuItem("Tracker", "Tab"),
    Ff4MenuItem("Save state", "F5"),
    Ff4MenuItem("Exit to library", "Esc"),
)

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

/** Plays one ROM fullscreen, with no library around it. */
private fun runGame(rom: String, config: Config, metrics: AwtTextMetrics) {
    val screen = Toolkit.getDefaultToolkit().screenSize
    val width = screen.width
    val height = screen.height
    val scale = Ff4Scale(minOf(width, height), 1f)
    val renderer = ScreenRenderer(scale, metrics)
    val canvas = SoftwareCanvas(width, height, metrics, shadowOffsetPx = scale.unit * 0.5f)

    println("loading $rom")
    val session = GameSession(rom, findNativeDir(config), config.workDir)
    val pause = Ff4MenuModel(pauseItems())
    val ui = UiState()

    val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            g.drawImage(canvas.image, 0, 0, null)
        }
    }
    panel.isFocusable = true

    fun compose() {
        canvas.clear(Ff4Palette.BLACK)
        canvas.drawGameFrame(session.image(), gameRect(width, height, session.width, session.height))
        when (ui.overlay) {
            Overlay.PAUSE -> canvas.draw(renderer.pauseMenu(width, height, pause.items, pause.selected, "PAUSED"))
            Overlay.TRACKER -> canvas.draw(renderer.tracker(width, height, ui.wram))
            Overlay.NONE -> Unit
        }
        panel.repaint()
    }

    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (ui.overlay != Overlay.NONE) {
                when (e.keyCode) {
                    KeyEvent.VK_UP -> if (pause.moveSelection(-1)) compose()
                    KeyEvent.VK_DOWN -> if (pause.moveSelection(1)) compose()
                    KeyEvent.VK_ENTER -> when (pause.activationTarget()) {
                        0 -> ui.overlay = Overlay.NONE
                        1 -> ui.overlay = Overlay.TRACKER
                        else -> exitProcess(0)
                    }
                    KeyEvent.VK_ESCAPE, KeyEvent.VK_TAB -> ui.overlay = Overlay.NONE
                }
                return
            }
            when (e.keyCode) {
                KeyEvent.VK_ESCAPE -> ui.overlay = Overlay.PAUSE
                KeyEvent.VK_TAB -> ui.overlay = Overlay.TRACKER
                else -> keyToButton(e.keyCode)?.let { session.setButton(it, true) }
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
