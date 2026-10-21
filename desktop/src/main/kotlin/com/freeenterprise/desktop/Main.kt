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
    /** Drives a scripted pad instead of SDL, so the pad path can be checked here. */
    val fakePad: Boolean,
    /** Start in a window rather than fullscreen. */
    val windowed: Boolean,
    /** Quit after this many seconds; a smoke test for the shutdown path. */
    val quitAfter: Int,
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
            var fakePad = false
            var windowed = false
            var quitAfter = 0
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
                    "--fake-pad" -> fakePad = true
                    "--windowed" -> windowed = true
                    "--quit-after" -> quitAfter = args.getOrNull(++i)?.toIntOrNull() ?: 0
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
                fakePad = fakePad,
                windowed = windowed,
                quitAfter = quitAfter,
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
    /** Polled on the UI thread, read by the emulator loop. */
    @Volatile var padMask: Int = 0
    @Volatile var padStatus: String = "pad: none (keyboard)"
}

/**
 * The controller, wired to the emulator and to our menus.
 *
 * SDL wants its event calls on the main thread, so [poll] happens on the UI thread
 * into a volatile mask and [tick] applies the differences on the emulator thread,
 * where the core lives.
 */
private class PadWiring(
    private val mask: () -> Int,
    private val sendButton: (Int, Boolean) -> Unit,
    private val onShortcut: (PadRouter.PadShortcut) -> Unit,
    private val onMenuInput: (PadRouter.MenuInput) -> Unit,
) {
    private val router = PadRouter(sendButton, onShortcut, onMenuInput)

    var menuOpen: Boolean
        get() = router.menuOpen
        set(value) { router.menuOpen = value }

    fun tick() = router.update(mask())
}

/** A scripted pad, for checking the input path on a machine with no controller. */
private fun fakePadMask(elapsedMillis: Long): Int {
    fun mask(vararg ids: Int) = ids.fold(0) { acc, id -> acc or (1 shl id) }
    return when {
        elapsedMillis in 1500..2000 -> mask(GamepadMapper.Retro.START)   // open our menu
        elapsedMillis in 2500..3000 -> mask(GamepadMapper.Retro.DOWN)    // move down
        elapsedMillis in 3500..4200 -> mask(GamepadMapper.Retro.A)       // confirm: the tracker
        else -> 0
    }
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

    // The controller, if the machine has one. SDL wants its event calls on the main
    // thread, so polling happens here on the UI thread and the emulator loop applies
    // the differences.
    val gamepad = if (config.fakePad) null else Gamepad(findNativeDir(config))
    ui.padStatus = when {
        config.fakePad -> "pad: scripted (--fake-pad)"
        gamepad?.connected == true -> "pad: ${gamepad.name}"
        gamepad?.available == true -> "pad: none attached (keyboard)"
        else -> "pad: SDL2 not found (keyboard)"
    }
    val padStartedAt = System.currentTimeMillis()
    val padTimer = javax.swing.Timer(8) {
        ui.padMask = if (config.fakePad) {
            fakePadMask(System.currentTimeMillis() - padStartedAt)
        } else {
            gamepad?.poll() ?: 0
        }
    }.apply { start() }

    // The emulator loop needs the pad, and the pad needs the screen functions, which
    // cannot be referenced before they are declared. Forward references break that
    // cycle; they are assigned once everything exists.
    lateinit var activateLibraryRow: () -> Unit
    lateinit var onPadShortcut: (PadRouter.PadShortcut) -> Unit
    lateinit var onPadMenuInput: (PadRouter.MenuInput) -> Unit
    lateinit var quit: (Int) -> Unit
    var quitting = false
    val pad = PadWiring(
        mask = { ui.padMask },
        sendButton = { id, down -> session?.setButton(id, down) },
        onShortcut = { onPadShortcut(it) },
        onMenuInput = { onPadMenuInput(it) },
    )

    val panel = gamePanel(canvas)

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
                        ui.message.ifBlank { "${library.seeds().size} seed(s)  ·  ${ui.padStatus}" },
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
        val loop = gameLoop[0]
        if (loop != null) {
            loop.interrupt()
            // Let it leave the native call before we touch the core: closing while
            // retro_run is executing is a race, and a needless one.
            try {
                loop.join(1500)
            } catch (_: InterruptedException) {
            }
            gameLoop[0] = null
        }
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
                    // A menu being up means the game must not see the pad.
                    pad.menuOpen = ui.screen != Screen.PLAYING || ui.overlay != Overlay.NONE
                    pad.tick()
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

    /** Runs the row the library cursor is on. Shared by keyboard and pad. */
    activateLibraryRow = {
        val index = libraryModel.activationTarget()
        if (index != null) {
            val seeds = library.seeds()
            when {
                index < seeds.size -> play(seeds[index].rom)
                index == seeds.size -> startGeneration("SEED%04d".format(System.currentTimeMillis() % 10000))
                else -> quit(0)
            }
        }
    }

    onPadShortcut = { shortcut ->
        if (ui.screen == Screen.PLAYING) {
            when (shortcut) {
                PadRouter.PadShortcut.TOGGLE_MENU ->
                    ui.overlay = if (ui.overlay == Overlay.PAUSE) Overlay.NONE else Overlay.PAUSE
                PadRouter.PadShortcut.TOGGLE_TRACKER ->
                    ui.overlay = if (ui.overlay == Overlay.TRACKER) Overlay.NONE else Overlay.TRACKER
                PadRouter.PadShortcut.FAST_FORWARD_ON -> session?.fastForward = true
                PadRouter.PadShortcut.FAST_FORWARD_OFF -> session?.fastForward = false
            }
        }
    }

    onPadMenuInput = { input ->
        if (ui.screen == Screen.LIBRARY) {
            when (input) {
                PadRouter.MenuInput.UP -> if (libraryModel.moveSelection(-1)) compose()
                PadRouter.MenuInput.DOWN -> if (libraryModel.moveSelection(1)) compose()
                PadRouter.MenuInput.CONFIRM -> activateLibraryRow()
                PadRouter.MenuInput.CANCEL -> quit(0)
            }
        } else {
            when (input) {
                PadRouter.MenuInput.UP -> if (pauseModel.moveSelection(-1)) compose()
                PadRouter.MenuInput.DOWN -> if (pauseModel.moveSelection(1)) compose()
                PadRouter.MenuInput.CONFIRM -> when (pauseModel.activationTarget()) {
                    0 -> ui.overlay = Overlay.NONE
                    1 -> ui.overlay = Overlay.TRACKER
                    3 -> openLibrary()
                }
                PadRouter.MenuInput.CANCEL -> ui.overlay = Overlay.NONE
            }
        }
    }

    val frame = gameFrame("Free Enterprise - dev build", panel)
    val display = Display(frame, screen, startFullscreen = !config.windowed)

    quit = { code ->
        if (!quitting) {
            quitting = true
            padTimer.stop()
            stopGame()
            gamepad?.close()
            closeCleanly(frame)
            exitOffEventThread(code)
        }
    }

    frame.addWindowListener(object : java.awt.event.WindowAdapter() {
        override fun windowClosing(e: java.awt.event.WindowEvent) = quit(0)
    })

    display.apply()
    panel.requestFocusInWindow()

    Runtime.getRuntime().addShutdownHook(Thread {
        // No Swing work here: see closeCleanly.
        stopGame()
    })

    if (config.quitAfter > 0) {
        javax.swing.Timer(config.quitAfter * 1000) { quit(0) }.apply { isRepeats = false }.start()
    }

    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            when (ui.screen) {
                Screen.LIBRARY -> when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_W -> if (libraryModel.moveSelection(-1)) compose()
                    KeyEvent.VK_DOWN, KeyEvent.VK_S -> if (libraryModel.moveSelection(1)) compose()
                    KeyEvent.VK_ENTER -> activateLibraryRow()
                    KeyEvent.VK_F11 -> { display.toggle(); compose() }
                    KeyEvent.VK_ESCAPE, KeyEvent.VK_Q -> quit(0)
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
                        KeyEvent.VK_F11 -> { display.toggle(); compose() }
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

/**
 * Quitting, correctly.
 *
 * The freeze was a deadlock of our own making: `System.exit` was called from the AWT
 * event thread, so the JVM ran the shutdown hooks on another thread - and the hook
 * called `frame.dispose()`, which is Swing work needing the event thread, which was
 * blocked inside `System.exit` waiting for the hooks. The window stayed on screen
 * and the process never left.
 *
 * So: no Swing work in a hook, and the exit happens off the event thread, after the
 * window has been disposed on it.
 */
private fun closeCleanly(frame: JFrame) {
    val dispose = { frame.isVisible = false; frame.dispose() }
    if (javax.swing.SwingUtilities.isEventDispatchThread()) dispose() else javax.swing.SwingUtilities.invokeLater(dispose)
}

private fun exitOffEventThread(code: Int) {
    Thread {
        Thread.sleep(150)   // let the event thread finish disposing
        exitProcess(code)
    }.start()
}

/** The picture surface: the canvas, scaled to whatever size the window is. */
private fun gamePanel(canvas: SoftwareCanvas) = object : JPanel() {
    override fun paintComponent(g: Graphics) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
        g2.drawImage(canvas.image, 0, 0, width, height, null)
    }
}.apply { isFocusable = true }

/** A decorated window, so windowed mode has a title bar and a working close button. */
private fun gameFrame(title: String, panel: JPanel): JFrame = JFrame(title).apply {
    defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
    contentPane.add(panel)
}

/** Fullscreen or a window, and the key that swaps between them. */
private class Display(
    private val frame: JFrame,
    private val screen: java.awt.Dimension,
    startFullscreen: Boolean,
) {
    var fullscreen: Boolean = startFullscreen
        private set

    fun apply() {
        val device = frame.graphicsConfiguration.device
        if (fullscreen) {
            frame.isVisible = true
            device.setFullScreenWindow(frame)
        } else {
            device.setFullScreenWindow(null)
            frame.isVisible = true
            frame.setSize(minOf(1280, screen.width - 80), minOf(720, screen.height - 80))
            frame.setLocationRelativeTo(null)
        }
    }

    fun toggle(): Boolean {
        fullscreen = !fullscreen
        apply()
        return fullscreen
    }
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
    val gamepad = if (config.fakePad) null else Gamepad(findNativeDir(config))
    val padStartedAt = System.currentTimeMillis()
    val padTimer = javax.swing.Timer(8) {
        ui.padMask = if (config.fakePad) {
            fakePadMask(System.currentTimeMillis() - padStartedAt)
        } else {
            gamepad?.poll() ?: 0
        }
    }.apply { start() }

    val panel = gamePanel(canvas)
    val frame = gameFrame("Free Enterprise - dev build", panel)
    val display = Display(frame, screen, startFullscreen = !config.windowed)

    var quitting = false
    fun quit(code: Int = 0) {
        if (quitting) return
        quitting = true
        padTimer?.stop()
        session.close()
        gamepad?.close()
        closeCleanly(frame)
        exitOffEventThread(code)
    }

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

    val pad = PadWiring(
        mask = { ui.padMask },
        sendButton = { id, down -> session.setButton(id, down) },
        onShortcut = { shortcut ->
            when (shortcut) {
                PadRouter.PadShortcut.TOGGLE_MENU ->
                    ui.overlay = if (ui.overlay == Overlay.PAUSE) Overlay.NONE else Overlay.PAUSE
                PadRouter.PadShortcut.TOGGLE_TRACKER ->
                    ui.overlay = if (ui.overlay == Overlay.TRACKER) Overlay.NONE else Overlay.TRACKER
                PadRouter.PadShortcut.FAST_FORWARD_ON -> session.fastForward = true
                PadRouter.PadShortcut.FAST_FORWARD_OFF -> session.fastForward = false
            }
        },
        onMenuInput = { input ->
            when (input) {
                PadRouter.MenuInput.UP -> if (pause.moveSelection(-1)) compose()
                PadRouter.MenuInput.DOWN -> if (pause.moveSelection(1)) compose()
                PadRouter.MenuInput.CONFIRM -> when (pause.activationTarget()) {
                    0 -> ui.overlay = Overlay.NONE
                    1 -> ui.overlay = Overlay.TRACKER
                    else -> quit()
                }
                PadRouter.MenuInput.CANCEL -> ui.overlay = Overlay.NONE
            }
        },
    )

    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (ui.overlay != Overlay.NONE) {
                when (e.keyCode) {
                    KeyEvent.VK_UP -> if (pause.moveSelection(-1)) compose()
                    KeyEvent.VK_DOWN -> if (pause.moveSelection(1)) compose()
                    KeyEvent.VK_ENTER -> when (pause.activationTarget()) {
                        0 -> ui.overlay = Overlay.NONE
                        1 -> ui.overlay = Overlay.TRACKER
                        else -> quit()
                    }
                    KeyEvent.VK_ESCAPE, KeyEvent.VK_TAB -> ui.overlay = Overlay.NONE
                }
                return
            }
            when (e.keyCode) {
                KeyEvent.VK_ESCAPE -> ui.overlay = Overlay.PAUSE
                KeyEvent.VK_TAB -> ui.overlay = Overlay.TRACKER
                KeyEvent.VK_F11 -> { display.toggle(); compose() }
                else -> keyToButton(e.keyCode)?.let { session.setButton(it, true) }
            }
        }

        override fun keyReleased(e: KeyEvent) {
            keyToButton(e.keyCode)?.let { session.setButton(it, false) }
        }
    })

    frame.addWindowListener(object : java.awt.event.WindowAdapter() {
        override fun windowClosing(e: java.awt.event.WindowEvent) = quit()
    })

    display.apply()
    panel.requestFocusInWindow()

    Runtime.getRuntime().addShutdownHook(Thread {
        // No Swing work here: see exitOffEventThread.
        session.close()
    })

    if (config.quitAfter > 0) {
        javax.swing.Timer(config.quitAfter * 1000) { quit() }.apply { isRepeats = false }.start()
    }

    val loop = Thread {
        try {
            while (!Thread.currentThread().isInterrupted) {
                session.step()
                if (ui.overlay == Overlay.TRACKER) ui.wram = session.readWram()
                pad.menuOpen = ui.overlay != Overlay.NONE
                pad.tick()
                compose()
            }
        } catch (t: Throwable) {
            System.err.println("emulator loop stopped: ${t.message}")
        }
    }
    loop.isDaemon = true
    loop.start()
}