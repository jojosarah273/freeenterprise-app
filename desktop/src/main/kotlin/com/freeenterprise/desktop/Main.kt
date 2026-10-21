package com.freeenterprise.desktop

import com.freeenterprise.app.emulator.EmulatorBridge.Button
import com.freeenterprise.ff4.Ff4Checks
import com.freeenterprise.ff4.Ff4KeyItems
import com.freeenterprise.ff4.Ff4MenuItem
import com.freeenterprise.ff4.Ff4MenuModel
import com.freeenterprise.ff4.Ff4Palette
import com.freeenterprise.ff4.Ff4Scale
import com.freeenterprise.ff4.Ff4Spoiler
import com.freeenterprise.ff4.Ff4StatusLine
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
        config.rom != null -> runApp(config, metrics)
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
    val checksFile: File?,
    val uiSpecFile: File?,
    val presetsFile: File?,
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
            var checks: String? = null
            var uiSpec: String? = null
            var presetsFile: String? = null
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
                    "--checks" -> checks = args.getOrNull(++i)
                    "--ui-spec" -> uiSpec = args.getOrNull(++i)
                    "--presets" -> presetsFile = args.getOrNull(++i)
                    "--fake-pad" -> fakePad = true
                    "--windowed" -> windowed = true
                    "--quit-after" -> quitAfter = args.getOrNull(++i)?.toIntOrNull() ?: 0
                    "--help", "-h" -> help = true
                    else -> System.err.println("unknown argument: ${args[i]}")
                }
                i++
            }

            // The library folder the app uses, at the installation root: FF4_FE holds
            // the seeds, their metadata, battery saves, states and the spoilers.
            val libraryDir = File(library ?: firstExisting("FF4_FE", "../FF4_FE")?.path ?: "FF4_FE")
                .also { it.mkdirs() }
            val workDir = File(work ?: File(libraryDir, ".work").path).also { it.mkdirs() }
            val engineDir = engine?.let { File(it) } ?: firstExisting(
                "app/src/main/assets/python_ext",
                "../app/src/main/assets/python_ext",
            )
            val bridgeFile = bridge?.let { File(it) } ?: firstExisting(
                "app/src/main/python/fe_bridge.py",
                "../app/src/main/python/fe_bridge.py",
            )
            val baseRomFile = baseRom?.let { File(it) }
                ?: listOf(
                    File(libraryDir, "base_rom.smc"), File(libraryDir, "ff2.smc"),
                    File(workDir, "base_rom.smc"), File(workDir, "ff2.smc"),
                ).firstOrNull { it.isFile }

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
                uiSpecFile = uiSpec?.let { File(it) } ?: firstExisting(
                    "app/src/main/assets/spec/ui_spec.json",
                    "../app/src/main/assets/spec/ui_spec.json",
                    "engine/assets/ui_spec.json",
                    "assets/ui_spec.json",
                ),
                presetsFile = presetsFile?.let { File(it) } ?: firstExisting(
                    "app/src/main/assets/presets/presets.json",
                    "../app/src/main/assets/presets/presets.json",
                    "engine/assets/presets.json",
                    "assets/presets.json",
                ),
                checksFile = checks?.let { File(it) } ?: firstExisting(
                    "app/src/main/assets/spec/checks.json",
                    "../app/src/main/assets/spec/checks.json",
                    "engine/assets/checks.json",
                    "assets/checks.json",
                ),
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

private enum class Screen { LIBRARY, SEEDS, ACTIONS, MANAGE_SAVE, DONE, FLAGS, EDITOR, PRESETS, GENERATING, PLAYING }
private enum class Overlay { NONE, PAUSE, TRACKER }

/** Intent published by the UI thread and consumed by whichever worker is running. */
private class UiState {
    /** Which screen is up. Written by the UI thread, read by the emulator loop. */
    @Volatile var screen: Screen = Screen.LIBRARY
    @Volatile var overlay: Overlay = Overlay.NONE
    @Volatile var wram: ByteArray = ByteArray(0x800)
    @Volatile var progress: EngineProcess.Progress = EngineProcess.Progress(0, "Starting engine", "")
    @Volatile var message: String = ""
    /** Which tracker page is showing; the app has two. */
    @Volatile var trackerPage: Int = 0
    /** Asking "leave Free Enterprise?", as the app does before it exits. */
    @Volatile var confirmingQuit: Boolean = false
    @Volatile var confirmChoice: Int = 1   // default to Cancel
    /** Polled on the UI thread, read by the emulator loop. */
    @Volatile var padMask: Int = 0
    @Volatile var padStatus: String = "pad: none (keyboard)"
    /** A modal question (Reset core, Exit to sessions), and what yes does. */
    @Volatile var confirmTitle: String? = null
    @Volatile var confirmMessage: String = ""
    @Volatile var confirmYes: String = "Yes"
    var confirmAction: (() -> Unit)? = null
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

/**
 * Rename a seed and everything stored beside it, as the app's Rename Seed does:
 * the ROM, its metadata, battery save, state and spoiler all take the new name.
 */
private fun renameSeed(rom: File, newName: String) {
    if (newName.isBlank() || newName == rom.nameWithoutExtension) return
    val clean = newName.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim()
    if (clean.isBlank()) return
    val target = File(rom.parentFile, clean + ".smc")
    if (target.exists()) return
    for (ext in listOf(".smc", ".json", ".srm", ".state", ".spoiler.txt")) {
        val from = File(rom.parentFile, rom.nameWithoutExtension + ext)
        if (from.isFile) from.renameTo(File(rom.parentFile, clean + ext))
    }
    // The metadata's own name follows the file, so the session list shows it.
    val meta = File(rom.parentFile, clean + ".json")
    runCatching {
        val json = org.json.JSONObject(meta.readText())
        json.put("name", clean)
        meta.writeText(json.toString(2))
    }
}

/**
 * Remember the key items the engine says are found, so the tracker keeps them ticked
 * after the game takes them away (the Package, the traded Rat Tail and Pan) - the
 * same memory the app keeps in the seed's metadata.
 */
private fun rememberSeenItems(rom: File, wram: ByteArray) {
    val found = Ff4KeyItems.entries.filter { Ff4KeyItems.isFound(wram, it.index) }.map { it.name }
    if (found.isNotEmpty()) SeedMeta.addSeenItems(rom, found)
}

/** A native file chooser, for exporting and importing a .srm. */
private fun chooseFile(save: Boolean, suggested: String): File? {
    val dialog = java.awt.FileDialog(
        java.awt.Frame(),
        if (save) "Export save" else "Import save",
        if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD,
    )
    dialog.file = suggested
    dialog.isVisible = true
    val directory = dialog.directory
    val file = dialog.file
    return if (directory != null && file != null) File(directory, file) else null
}

/** Keep a row index inside a list that may have shrunk after a page change. */
private fun List<Any>.coerceSelection(index: Int): Int =
    if (isEmpty()) 0 else index.coerceIn(0, size - 1)

/** The check list, from the generated database. Empty if it cannot be read. */
private fun loadChecks(file: File?): List<Ff4Checks.Check> {
    if (file == null || !file.isFile) return emptyList()
    return try {
        val array = org.json.JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Ff4Checks.Check(o.getInt("f"), o.getString("w"), o.getString("a"), o.optString("d"))
        }
    } catch (t: Throwable) {
        System.err.println("could not read ${file.path}: ${t.message}")
        emptyList()
    }
}

/**
 * The key-item locations recorded for a seed. The metadata is the normal source, as
 * it is in the app; the spoiler beside the ROM is the fallback for a seed that
 * predates it or was copied in from elsewhere.
 */
private fun spoilerLocationsFor(rom: File): List<Pair<String, String>> {
    SeedMeta.keyItems(rom).let { if (it.isNotEmpty()) return it }
    val spoiler = File(rom.parentFile, rom.nameWithoutExtension + ".spoiler.txt")
    if (!spoiler.isFile) return emptyList()
    return try {
        Ff4Spoiler.usableKeyItemLocations(spoiler.readText())
    } catch (t: Throwable) {
        emptyList()
    }
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
    val library = SeedLibrary(config.libraryDir, config.baseRom).also { it.ensureExists() }
    val checks = loadChecks(config.checksFile)
    val engineVersion = run {
        val engine = config.engineDir
        val bridge = config.bridgeFile
        if (engine != null && bridge != null) {
            EngineProcess(engine, bridge, config.baseRom ?: File("none"), File(config.workDir, "cache")).version()
        } else {
            ""
        }
    }
    val flagUi = FlagUi(
        sections = SpecLoader.uiSpec(config.uiSpecFile),
        presets = SpecLoader.presets(config.presetsFile),
        store = FlagsetStore(config.libraryDir),
    )
    // Start from the command line's flags, so --flags still works for scripts.
    runCatching { flagUi.editor.addAll(config.flags.split(" ").filter { it.isNotBlank() }) }
    var trackerLocations = emptyList<Pair<String, String>>()
    /** Key items seen at any point, so a traded item stays ticked (as the app does). */
    var seenItems = emptySet<String>()

    val ui = UiState()
    var session: GameSession? = null
    var currentRom: File? = null
    var playingSince = 0L
    val libraryModel = Ff4MenuModel(emptyList())
    var flagSelected = 0
    var editorSelected = 0
    var seedName = ""
    var seedsSelected = 0
    var actionsSelected = 0
    var selectedSeed: File? = null
    var saveSelected: Int = 0
    /** The seed the last generation produced, for the completion screen. */
    var generatedSeed: File? = null
    /** A modal text field; while it is up it owns the keyboard. */
    var textInput: TextInput? = null
    val pauseModel = Ff4MenuModel(pauseItems(false, false))
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
    lateinit var flagActivate: () -> Unit
    lateinit var libraryActivate: () -> Unit
    lateinit var presetActivate: () -> Unit
    lateinit var generateActivate: () -> Unit
    lateinit var stateInfoLine: () -> String
    lateinit var manageSaveItems: () -> List<Ff4MenuItem>
    lateinit var doneItems: () -> List<Ff4MenuItem>
    lateinit var saveActivate: () -> Unit
    lateinit var doneActivate: () -> Unit
    lateinit var seedActivate: () -> Unit
    lateinit var actionActivate: () -> Unit
    lateinit var scheduleCheck: () -> Unit
    var pendingCheck = 0
    lateinit var onPadShortcut: (PadRouter.PadShortcut) -> Unit
    lateinit var onPadMenuInput: (PadRouter.MenuInput) -> Unit
    lateinit var quit: (Int) -> Unit
    lateinit var askQuit: () -> Unit
    var quitting = false
    val pad = PadWiring(
        mask = { ui.padMask },
        sendButton = { id, down -> session?.setButton(id, down) },
        onShortcut = { onPadShortcut(it) },
        onMenuInput = { onPadMenuInput(it) },
    )

    val panel = gamePanel(canvas)

    /** The app's Generate screen: preset, flags, seed, generate, back. */
    fun generateMenuItems(): List<Ff4MenuItem> = listOf(
        Ff4MenuItem("Choose Preset", flagUi.appliedName),
        Ff4MenuItem("Choose Flags", if (flagUi.editor.flagsText().isBlank()) "none set" else ""),
        Ff4MenuItem("Seed", seedName.ifBlank { "random" }),
        Ff4MenuItem("Generate Seed", if (!flagUi.valid) "fix errors" else ""),
        Ff4MenuItem("Back"),
    )

    /** The app's main menu, minus the platform-only rows (updates, settings). */
    fun libraryItems(): List<Ff4MenuItem> = listOf(
        Ff4MenuItem("Play Free Enterprise", "${library.seeds().size} seed(s)"),
        Ff4MenuItem("Generate Seed"),
        Ff4MenuItem("Exit"),
    )

    /** The app's session list: one row per seed, with what it was and how far. */
    fun seedItems(): List<Ff4MenuItem> {
        val seeds = library.seeds()
        if (seeds.isEmpty()) return listOf(Ff4MenuItem("(no seeds)", enabled = false), Ff4MenuItem("Back"))
        return seeds.map { seed ->
            val summary = SeedMeta.summary(seed.rom)
            val save = File(seed.rom.parentFile, seed.rom.nameWithoutExtension + ".srm")
            val saveText = if (save.isFile) "save ${save.length() / 1024}K" else "no save"
            val date = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                .format(java.util.Date(if (summary.lastPlayed > 0) summary.lastPlayed else seed.rom.lastModified()))
            Ff4MenuItem(
                label = summary.name,
                value = "${seed.rom.length() / 1024}K",
                subtitle = "seed ${summary.seed.ifBlank { "?" }}  ·  $saveText  ·  last played $date",
                // The app draws the seed's checksum tiles as the game's item icons.
                // Just the tile names: Assets.icon adds the checksum/ prefix.
                icons = summary.verification,
            )
        } + Ff4MenuItem("Back")
    }

    /** The app's per-seed actions. */
    fun actionItems(): List<Ff4MenuItem> {
        val rom = selectedSeed ?: return listOf(Ff4MenuItem("Back"))
        val save = File(rom.parentFile, rom.nameWithoutExtension + ".srm")
        return listOf(
            Ff4MenuItem("Play Seed"),
            Ff4MenuItem("Manage Save", if (save.isFile) "present" else "none"),
            Ff4MenuItem("New seed with these flags", "same ruleset"),
            Ff4MenuItem("Save flags as Flagset"),
            Ff4MenuItem("Rename Seed", rom.nameWithoutExtension),
            Ff4MenuItem("Delete Seed"),
            Ff4MenuItem("Back"),
        )
    }

    fun compose() {
        canvas.clear(Ff4Palette.BLACK)
        when (ui.screen) {
            Screen.LIBRARY -> {
                canvas.draw(
                    renderer.mainMenu(
                        width, height, libraryModel.items, libraryModel.selected,
                        status = listOf(
                            Ff4StatusLine(
                                "Free Enterprise  " + engineVersion.ifBlank { "engine not found" } +
                                    (if (config.baseRom == null) "  ·  no base ROM" else ""),
                            ),
                            Ff4StatusLine(ui.message.ifBlank { "${library.seeds().size} seed(s)  ·  ${ui.padStatus}" }),
                        ),
                        hint = "Up/Down  Move      Enter  Select      Esc  Quit",
                    ),
                )
                if (ui.confirmingQuit) {
                    canvas.draw(renderer.scrim(width, height))
                    canvas.draw(
                        renderer.confirmDialog(
                            width, height,
                            "Quit",
                            "Leave Free Enterprise?\nYour seeds and saves stay in ${config.libraryDir.path}.",
                            listOf("Quit", "Cancel"),
                            ui.confirmChoice,
                        ),
                    )
                }
            }
            Screen.SEEDS -> canvas.draw(
                renderer.mainMenu(
                    width, height, seedItems(), seedsSelected,
                    status = listOf(
                        Ff4StatusLine("FF4_FE  ·  ${library.seeds().size} session(s)"),
                        Ff4StatusLine(ui.message),
                    ),
                    hint = "A  Select      B  Back",
                ),
            )
            Screen.ACTIONS -> {
                val rom = selectedSeed
                val summary = rom?.let { SeedMeta.summary(it) }
                canvas.draw(
                    renderer.mainMenu(
                        width, height, actionItems(), actionsSelected,
                        status = listOf(
                            Ff4StatusLine(summary?.name ?: ""),
                            Ff4StatusLine(summary?.flags?.take(60).orEmpty()),
                        ),
                        hint = "A  Select      B  Back",
                    ),
                )
            }
            Screen.MANAGE_SAVE -> canvas.draw(
                renderer.mainMenu(
                    width, height, manageSaveItems(), saveSelected,
                    status = listOf(
                        Ff4StatusLine(selectedSeed?.nameWithoutExtension.orEmpty()),
                        Ff4StatusLine("Save files stay in FF4_FE"),
                    ),
                    hint = "A  Select      B  Back",
                ),
            )
            Screen.DONE -> canvas.draw(
                renderer.mainMenu(
                    width, height, doneItems(), saveSelected,
                    status = listOf(
                        Ff4StatusLine("Seed generated"),
                        Ff4StatusLine(generatedSeed?.nameWithoutExtension.orEmpty(), Ff4Palette.GREEN),
                    ),
                    hint = "A  Select      B  Back",
                ),
            )
            Screen.FLAGS -> canvas.draw(
                renderer.mainMenu(
                    width, height, generateMenuItems(), flagSelected,
                    status = flagUi.status(),
                    hint = "A  Select      B  Back",
                ),
            )
            Screen.EDITOR -> {
                canvas.draw(
                    renderer.mainMenu(
                        width, height, flagUi.items(), flagUi.selection,
                        status = flagUi.status(),
                        hint = "",
                    ),
                )
                canvas.draw(renderer.hintBox(width, height, flagUi.hintLines()))
            }
            Screen.PRESETS -> canvas.draw(
                renderer.mainMenu(
                    width, height, flagUi.items(), flagUi.selection,
                    status = flagUi.status(),
                    hint = flagUi.hint(),
                ),
            )
            Screen.GENERATING -> {
                // The library stays visible underneath, dimmed, as it does on Android:
                // the modal darkens the screen rather than blacking it out.
                canvas.draw(
                    renderer.mainMenu(
                        width, height, libraryModel.items, libraryModel.selected,
                        status = listOf(
                            Ff4StatusLine(
                                "Free Enterprise  " + engineVersion.ifBlank { "engine not found" } +
                                    (if (config.baseRom == null) "  ·  no base ROM" else ""),
                            ),
                            Ff4StatusLine("generating…"),
                        ),
                        hint = "Please wait",
                    ),
                )
                canvas.draw(renderer.scrim(width, height))
                canvas.draw(
                    renderer.progressOverlay(
                        width, height, "GENERATING SEED",
                        ui.progress.percent, ui.progress.stage, ui.progress.detail.ifBlank { "…" },
                    ),
                )
            }
            Screen.PLAYING -> {
                session?.let {
                    canvas.drawGameFrame(it.image(), gameRect(width, height, it.width, it.height))
                }
                when (ui.overlay) {
                    Overlay.PAUSE -> canvas.draw(
                        renderer.pauseMenu(
                            width, height, pauseModel.items, pauseModel.selected, "PAUSED",
                            stateInfo = stateInfoLine(),
                        ),
                    )
                    Overlay.TRACKER -> canvas.draw(
                        renderer.tracker(width, height, ui.trackerPage, ui.wram, trackerLocations, checks, seenItems),
                    )
                    Overlay.NONE -> Unit
                }
            }
        }
        textInput?.let { input ->
            canvas.draw(renderer.scrim(width, height))
            canvas.draw(
                renderer.textInput(width, height, input.title, input.prompt, input.value),
            )
        }
        ui.confirmTitle?.let { title ->
            canvas.draw(renderer.scrim(width, height))
            canvas.draw(
                renderer.confirmDialog(
                    width, height, title, ui.confirmMessage,
                    listOf(ui.confirmYes, "Cancel"), ui.confirmChoice,
                ),
            )
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
            session = GameSession(rom.absoluteFile.path, findNativeDir(config), config.workDir)
        } catch (t: Throwable) {
            ui.message = "could not start ${rom.name}: ${t.message}"
            ui.screen = Screen.LIBRARY
            compose()
            return
        }
        ui.screen = Screen.PLAYING
        currentRom = rom
        ui.trackerPage = 0
        trackerLocations = spoilerLocationsFor(rom)
        seenItems = SeedMeta.keyItems(rom).map { Ff4KeyItems.normalise(it.first) }.toSet()
        playingSince = System.currentTimeMillis()
        ui.message = "playing ${rom.name}"
        val loop = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    // Our menus pause the game, exactly as the app's do: a modal window
                    // over a running game is not what 0.1.25 does.
                    if (ui.overlay == Overlay.NONE) {
                        session?.step()
                    } else {
                        Thread.sleep(16)
                    }
                    if (ui.overlay == Overlay.TRACKER) {
                        ui.wram = session?.readWram() ?: ByteArray(0x800)
                        currentRom?.let { rememberSeenItems(it, ui.wram) }
                        currentRom?.let { seenItems = SeedMeta.seenItems(it) }
                    }
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
                    .generate(seedName, flagUi.canonicalFlags.ifBlank { flagUi.editor.flagsText() }, config.libraryDir, File(config.workDir, "progress.txt")) {
                        ui.progress = it
                        compose()
                    }
                generatedSeed = result.rom
                ui.message = "generated ${result.seed} in %.1fs  (${result.verification})".format(result.seconds)
                openLibrary()
                saveSelected = 0
                ui.screen = Screen.DONE
                compose()
            } catch (t: Throwable) {
                ui.message = "generation failed: ${t.message}"
                openLibrary()
            }
        }
        worker.isDaemon = true
        worker.start()
    }

    /** Runs the row the library cursor is on. Shared by keyboard and pad. */
    activateLibraryRow = {
        val index = libraryModel.activationTarget()
        if (index != null) {
            val seeds = library.seeds()
            // The rows are: the seeds, then Flags & presets, Generate seed, Quit.
            when {
                index < seeds.size -> play(seeds[index].rom)
                index == seeds.size -> { ui.screen = Screen.FLAGS; compose() }
                else -> askQuit()
            }
        }
    }

    fun answerQuit() {
        if (ui.confirmChoice == 0) {
            quit(0)
        } else {
            ui.confirmingQuit = false
            compose()
        }
    }

    /** "State: NAME.state · 12K · Mar 3, 14:00", or a word when there is none. */
    stateInfoLine = {
        val file = currentRom?.let { GameSession.stateFile(it.absolutePath) }
        if (file == null) {
            ""
        } else if (!file.isFile) {
            "No save state yet."
        } else {
            val stamped = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(file.lastModified()))
            "State: ${file.name}  ·  ${file.length() / 1024}K  ·  $stamped"
        }
    }

    /** Opening the pause menu refreshes its values, as the app does. */
    fun openPause() {
        val state = currentRom?.let { GameSession.stateFile(it.absolutePath) }
        pauseModel.reset(pauseItems(state?.isFile == true, session?.fastForward == true), 0)
        ui.overlay = Overlay.PAUSE
        compose()
    }

    /** The app's pause menu rows and what each does. */
    fun pauseActivate(index: Int) {
        val rom = currentRom
        when (index) {
            0 -> ui.overlay = Overlay.NONE
            1 -> {
                // Save, then return to the game - as the app does.
                if (rom != null && session?.saveState(GameSession.stateFile(rom.absolutePath)) == true) {
                    ui.message = "state saved"
                }
                ui.overlay = Overlay.NONE
            }
            2 -> {
                if (rom != null && session?.loadState(GameSession.stateFile(rom.absolutePath)) == true) {
                    ui.message = "state loaded"
                }
                ui.overlay = Overlay.NONE
            }
            3 -> {
                // Toggle and redraw the menu so the row shows on/off, as the app does.
                session?.let { it.fastForward = !it.fastForward }
                pauseModel.reset(pauseItems(
                    currentRom?.let { GameSession.stateFile(it.absolutePath).isFile } == true,
                    session?.fastForward == true,
                ), 3)
            }
            4 -> { ui.overlay = Overlay.TRACKER }
            5 -> {
                ui.confirmTitle = "Reset core"
                ui.confirmMessage = "Reset the emulator?\nUnsaved progress since the last save state is lost."
                ui.confirmYes = "Reset"
                ui.confirmChoice = 1
                ui.confirmAction = {
                    ui.overlay = Overlay.NONE
                    currentRom?.let { play(it) }
                }
                return
            }
            6 -> {
                ui.confirmTitle = "Exit to sessions"
                ui.confirmMessage = "Leave the game?\nYour battery save is written to FF4_FE first."
                ui.confirmYes = "Exit"
                ui.confirmChoice = 1
                ui.confirmAction = { openLibrary() }
                return
            }
        }
        compose()
    }

    /** Leaving is deliberate, so it is asked about - as leaving a game is. */
    askQuit = {
        ui.confirmingQuit = true
        ui.confirmChoice = 1   // default to Cancel, so a stray press does not quit
        compose()
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
        if (textInput != null) {
            // The field owns the keyboard; the pad must not move the menu under it.
        } else if (ui.screen == Screen.LIBRARY) {
            if (ui.screen == Screen.SEEDS) {
                when (input) {
                    PadRouter.MenuInput.UP -> { seedsSelected = (seedsSelected - 1).coerceAtLeast(0); compose() }
                    PadRouter.MenuInput.DOWN -> { seedsSelected = (seedsSelected + 1).coerceAtMost(seedItems().size - 1); compose() }
                    PadRouter.MenuInput.CONFIRM -> seedActivate()
                    PadRouter.MenuInput.CANCEL -> { ui.screen = Screen.LIBRARY; compose() }
                }
            } else if (ui.screen == Screen.ACTIONS) {
                when (input) {
                    PadRouter.MenuInput.UP -> { actionsSelected = (actionsSelected - 1).coerceAtLeast(0); compose() }
                    PadRouter.MenuInput.DOWN -> { actionsSelected = (actionsSelected + 1).coerceAtMost(actionItems().size - 1); compose() }
                    PadRouter.MenuInput.CONFIRM -> actionActivate()
                    PadRouter.MenuInput.CANCEL -> { ui.screen = Screen.SEEDS; compose() }
                }
            } else if (ui.screen == Screen.EDITOR) {
                when (input) {
                    PadRouter.MenuInput.UP -> { flagUi.moveSelection(-1); compose() }
                    PadRouter.MenuInput.DOWN -> { flagUi.moveSelection(1); compose() }
                    PadRouter.MenuInput.CONFIRM -> flagActivate()
                    PadRouter.MenuInput.CANCEL -> {
                        if (flagUi.back() == FlagUi.Action.CLOSE_EDITOR) ui.screen = Screen.FLAGS
                        compose()
                    }
                }
            } else if (ui.screen == Screen.PRESETS) {
                when (input) {
                    PadRouter.MenuInput.UP -> { flagUi.moveSelection(-1); compose() }
                    PadRouter.MenuInput.DOWN -> { flagUi.moveSelection(1); compose() }
                    PadRouter.MenuInput.CONFIRM -> presetActivate()
                    PadRouter.MenuInput.CANCEL -> {
                        if (flagUi.back() == FlagUi.Action.CLOSE_PRESETS) {
                            flagUi.closeBrowser()
                            ui.screen = Screen.FLAGS
                        }
                        compose()
                    }
                }
            } else if (ui.screen == Screen.MANAGE_SAVE) {
                when (input) {
                    PadRouter.MenuInput.UP -> { saveSelected = (saveSelected - 1).coerceAtLeast(0); compose() }
                    PadRouter.MenuInput.DOWN -> { saveSelected = (saveSelected + 1).coerceAtMost(manageSaveItems().size - 1); compose() }
                    PadRouter.MenuInput.CONFIRM -> saveActivate()
                    PadRouter.MenuInput.CANCEL -> { ui.screen = Screen.ACTIONS; compose() }
                }
            } else if (ui.screen == Screen.DONE) {
                when (input) {
                    PadRouter.MenuInput.UP -> { saveSelected = (saveSelected - 1).coerceAtLeast(0); compose() }
                    PadRouter.MenuInput.DOWN -> { saveSelected = (saveSelected + 1).coerceAtMost(2); compose() }
                    PadRouter.MenuInput.CONFIRM -> doneActivate()
                    PadRouter.MenuInput.CANCEL -> { ui.screen = Screen.LIBRARY; compose() }
                }
            } else if (ui.screen == Screen.FLAGS) {
                when (input) {
                    PadRouter.MenuInput.UP -> { flagSelected = (flagSelected - 1).coerceAtLeast(0); compose() }
                    PadRouter.MenuInput.DOWN -> { flagSelected = (flagSelected + 1).coerceAtMost(generateMenuItems().size - 1); compose() }
                    PadRouter.MenuInput.CONFIRM -> generateActivate()
                    PadRouter.MenuInput.CANCEL -> { ui.screen = Screen.LIBRARY; compose() }
                }
            } else if (ui.confirmingQuit) {
                when (input) {
                    PadRouter.MenuInput.UP, PadRouter.MenuInput.DOWN -> {
                        ui.confirmChoice = 1 - ui.confirmChoice
                        compose()
                    }
                    PadRouter.MenuInput.CONFIRM -> answerQuit()
                    PadRouter.MenuInput.CANCEL -> { ui.confirmingQuit = false; compose() }
                }
            } else {
                when (input) {
                    PadRouter.MenuInput.UP -> if (libraryModel.moveSelection(-1)) compose()
                    PadRouter.MenuInput.DOWN -> if (libraryModel.moveSelection(1)) compose()
                    PadRouter.MenuInput.CONFIRM -> libraryActivate()
                    PadRouter.MenuInput.CANCEL -> askQuit()
                }
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

    fun flagItems(): List<Ff4MenuItem> = flagUi.items()

    /** The main menu: play, generate, exit. */
    libraryActivate = {
        when (libraryModel.activationTarget()) {
            0 -> { seedsSelected = 0; ui.screen = Screen.SEEDS; compose() }
            1 -> { flagSelected = 0; ui.screen = Screen.FLAGS; compose() }
            else -> askQuit()
        }
    }

    seedActivate = {
        val seeds = library.seeds()
        if (seedsSelected >= seeds.size) {
            ui.screen = Screen.LIBRARY
        } else {
            selectedSeed = seeds.getOrNull(seedsSelected)?.rom
            actionsSelected = 0
            ui.screen = Screen.ACTIONS
        }
        compose()
    }

    actionActivate = {
        val rom = selectedSeed
        if (rom != null) when (actionsSelected) {
            0 -> {
                SeedMeta.touchLastPlayed(rom)
                play(rom)
            }
            1 -> { saveSelected = 0; ui.screen = Screen.MANAGE_SAVE }
            2 -> {
                // Same ruleset: load the seed's flags into the editor and go to the
                // generate screen.
                SeedMeta.flags(rom).takeIf { it.isNotBlank() }?.let { flags ->
                    flagUi.editor.clear()
                    flagUi.editor.addAll(flags.split(" ").filter { it.isNotBlank() })
                    flagUi.showCategories()
                    scheduleCheck()
                }
                ui.screen = Screen.FLAGS
                flagSelected = 0
            }
            3 -> {
                val flags = SeedMeta.flags(rom).ifBlank { flagUi.editor.flagsText() }
                if (flags.isNotBlank()) {
                    textInput = TextInput(
                        "Save Flagset",
                        "Name this flagset",
                        rom.nameWithoutExtension,
                    ) { typed ->
                        FlagsetStore(config.libraryDir).save(typed.trim().ifBlank { rom.nameWithoutExtension }, flags)
                    }
                }
            }
            4 -> {
                textInput = TextInput(
                    "Rename Seed",
                    "New name for this seed",
                    rom.nameWithoutExtension,
                ) { typed -> renameSeed(rom, typed.trim()) }
            }
            5 -> {
                // Delete after asking, with the app's wording.
                val name = SeedMeta.summary(rom).name
                ui.confirmTitle = "Delete session"
                ui.confirmMessage = "Delete \"$name\", its ROM and its save from FF4_FE?"
                ui.confirmYes = "Delete"
                ui.confirmChoice = 1
                ui.confirmAction = {
                    val base = rom.nameWithoutExtension
                    listOf(".smc", ".json", ".srm", ".state", ".spoiler.txt").forEach { ext ->
                        File(rom.parentFile, base + ext).delete()
                    }
                    ui.screen = Screen.SEEDS
                    seedsSelected = 0
                }
            }
            else -> ui.screen = Screen.SEEDS
        }
        compose()
    }

    /** Manage Save, as the app's submenu: export, import, delete. */
    manageSaveItems = {
        val rom = selectedSeed
        val save = rom?.let { File(it.parentFile, it.nameWithoutExtension + ".srm") }
        if (rom == null || save == null) listOf(Ff4MenuItem("Back")) else listOf(
            Ff4MenuItem("Export save (.srm)", if (save.isFile) "${save.length() / 1024}K" else "none",
                enabled = save.isFile),
            Ff4MenuItem("Import save (.srm)"),
            Ff4MenuItem("Delete save", if (save.isFile) "present" else "none", enabled = save.isFile),
            Ff4MenuItem("Back"),
        )
    }

    /** The completion screen the app shows after generating. */
    doneItems = {
        listOf(
            Ff4MenuItem("Play Free Enterprise", generatedSeed?.nameWithoutExtension ?: ""),
            Ff4MenuItem("Generate Again"),
            Ff4MenuItem("Back"),
        )
    }

    /** Manage Save: export, import, delete - the app's four rows minus "Play in-app". */
    saveActivate = {
        val rom = selectedSeed
        val save = rom?.let { File(it.parentFile, it.nameWithoutExtension + ".srm") }
        if (rom == null || save == null) {
            ui.screen = Screen.ACTIONS
        } else when (saveSelected) {
            0 -> {
                val target = chooseFile(save = true, suggested = save.name)
                if (target != null) runCatching { save.copyTo(target, overwrite = true) }
            }
            1 -> {
                val source = chooseFile(save = false, suggested = save.name)
                if (source != null) runCatching { source.copyTo(save, overwrite = true) }
            }
            2 -> {
                ui.confirmTitle = "Delete save"
                ui.confirmMessage = "Delete the .srm save for this session?\nThe ROM stays."
                ui.confirmYes = "Delete"
                ui.confirmAction = { save.delete() }
            }
            else -> ui.screen = Screen.ACTIONS
        }
        compose()
    }

    /** The completion screen: play what was just made, make another, or go back. */
    doneActivate = {
        when (saveSelected) {
            0 -> generatedSeed?.let { play(it) }
            1 -> startGeneration(seedName)
            else -> { ui.screen = Screen.LIBRARY; compose() }
        }
    }

    /** The generate screen's rows, mirroring the app's Generate screen. */
    generateActivate = {
        when (flagSelected) {
            0 -> { flagUi.openPresets(); ui.screen = Screen.PRESETS }
            1 -> { flagUi.showCategories(); ui.screen = Screen.EDITOR }
            2 -> {
                textInput = TextInput(
                    "Seed name",
                    "Type a name for this seed",
                    seedName,
                ) { typed -> seedName = typed.trim().ifBlank { seedName } }
                compose()
            }
            // Blank means "random": the engine invents an alphanumeric seed, the same
            // way the app's Generate screen does when no name is typed.
            3 -> startGeneration(seedName)
            else -> { ui.screen = Screen.LIBRARY; libraryModel.reset(libraryItems()) }
        }
        compose()
    }

    /** The flag editor: three levels, exactly as the app's editor. */
    flagActivate = {
        flagUi.activate(flagUi.selection)
        scheduleCheck()
        compose()
    }

    presetActivate = {
        if (flagUi.activate(flagUi.selection) == FlagUi.Action.CLOSE_PRESETS) {
            flagUi.closeBrowser()
            ui.screen = Screen.FLAGS
            scheduleCheck()
        }
        compose()
    }

    /**
     * Canonicalise and validate after a change, off the UI thread: the engine's answer
     * is what the app shows in its status box ("Flags OK" or the number of logical
     * errors), and it is also what gets generated.
     */
    scheduleCheck = {
        val engine = config.engineDir
        val bridge = config.bridgeFile
        if (engine != null && bridge != null) {
        pendingCheck += 1
        val token = pendingCheck
        Thread {
            Thread.sleep(250)
            if (token != pendingCheck) return@Thread      // superseded by a newer edit
            try {
                val result = EngineProcess(engine, bridge, config.baseRom ?: File("none"), File(config.workDir, "cache"))
                    .checkFlags(flagUi.editor.flagsText())
                flagUi.applyEngine(result.canonical, result.valid, result.errors)
            } catch (t: Throwable) {
                flagUi.applyEngine(flagUi.editor.flagsText(), false, listOf(t.message ?: "check failed"))
            }
            compose()
        }.apply { isDaemon = true }.start()
        }
    }

    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            // Fullscreen is a window concern, not a screen concern: it must work on
            // every screen, including the generator and the editor.
            if (e.keyCode == KeyEvent.VK_F11) {
                display.toggle()
                compose()
                return
            }
            // A question owns the keyboard while it is up.
            if (ui.confirmTitle != null) {
                when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_W, KeyEvent.VK_DOWN, KeyEvent.VK_S -> {
                        ui.confirmChoice = 1 - ui.confirmChoice
                        compose()
                    }
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> {
                        val action = ui.confirmAction
                        val yes = ui.confirmChoice == 0
                        ui.confirmTitle = null
                        ui.confirmAction = null
                        if (yes) action?.invoke()
                        compose()
                    }
                    KeyEvent.VK_ESCAPE -> {
                        ui.confirmTitle = null
                        ui.confirmAction = null
                        compose()
                    }
                }
                return
            }
            // A text field owns the keyboard while it is up: typing must not also
            // move the menu underneath.
            textInput?.let { input ->
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        textInput = null
                        input.onAccept(input.value)
                    }
                    KeyEvent.VK_ESCAPE -> textInput = null
                    KeyEvent.VK_BACK_SPACE -> input.value = input.value.dropLast(1)
                    else -> {
                        val ch = e.keyChar
                        if (!ch.isISOControl() && (ch.isLetterOrDigit() || ch in " _-.")) {
                            input.value += ch
                        }
                    }
                }
                compose()
                return
            }
            when (ui.screen) {
                Screen.SEEDS -> when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_W -> { seedsSelected = (seedsSelected - 1).coerceAtLeast(0); compose() }
                    KeyEvent.VK_DOWN, KeyEvent.VK_S -> { seedsSelected = (seedsSelected + 1).coerceAtMost(seedItems().size - 1); compose() }
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> seedActivate()
                    KeyEvent.VK_ESCAPE -> { ui.screen = Screen.LIBRARY; compose() }
                }
                Screen.MANAGE_SAVE -> when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_W -> { saveSelected = (saveSelected - 1).coerceAtLeast(0); compose() }
                    KeyEvent.VK_DOWN, KeyEvent.VK_S -> { saveSelected = (saveSelected + 1).coerceAtMost(manageSaveItems().size - 1); compose() }
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> saveActivate()
                    KeyEvent.VK_ESCAPE -> { ui.screen = Screen.ACTIONS; compose() }
                }
                Screen.DONE -> when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_W -> { saveSelected = (saveSelected - 1).coerceAtLeast(0); compose() }
                    KeyEvent.VK_DOWN, KeyEvent.VK_S -> { saveSelected = (saveSelected + 1).coerceAtMost(3 - 1); compose() }
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> doneActivate()
                    KeyEvent.VK_ESCAPE -> { ui.screen = Screen.LIBRARY; compose() }
                }
                Screen.ACTIONS -> when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_W -> { actionsSelected = (actionsSelected - 1).coerceAtLeast(0); compose() }
                    KeyEvent.VK_DOWN, KeyEvent.VK_S -> { actionsSelected = (actionsSelected + 1).coerceAtMost(actionItems().size - 1); compose() }
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> actionActivate()
                    KeyEvent.VK_ESCAPE -> { ui.screen = Screen.SEEDS; compose() }
                }
                Screen.LIBRARY -> {
                    // The question owns the input while it is up. A duplicate UP/DOWN
                    // branch further down was unreachable - Kotlin takes the first
                    // match - which is why the cursor kept moving the menu underneath.
                    if (ui.confirmingQuit) {
                        when (e.keyCode) {
                            KeyEvent.VK_UP, KeyEvent.VK_W, KeyEvent.VK_DOWN, KeyEvent.VK_S -> {
                                ui.confirmChoice = 1 - ui.confirmChoice
                                compose()
                            }
                            KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> answerQuit()
                            KeyEvent.VK_ESCAPE, KeyEvent.VK_Q -> { ui.confirmingQuit = false; compose() }
                        }
                        return
                    }
                    when (e.keyCode) {
                        KeyEvent.VK_UP, KeyEvent.VK_W -> if (libraryModel.moveSelection(-1)) compose()
                        KeyEvent.VK_DOWN, KeyEvent.VK_S -> if (libraryModel.moveSelection(1)) compose()
                        KeyEvent.VK_ENTER -> libraryActivate()
                        KeyEvent.VK_F11 -> { display.toggle(); compose() }
                        KeyEvent.VK_ESCAPE, KeyEvent.VK_Q -> askQuit()
                    }
                }
                Screen.PLAYING -> {
                    if (ui.overlay != Overlay.NONE) {
                        when (e.keyCode) {
                            KeyEvent.VK_UP -> if (pauseModel.moveSelection(-1)) compose()
                            KeyEvent.VK_DOWN -> if (pauseModel.moveSelection(1)) compose()
                            KeyEvent.VK_ENTER -> pauseModel.activationTarget()?.let { pauseActivate(it) }
                            KeyEvent.VK_ESCAPE, KeyEvent.VK_TAB -> ui.overlay = Overlay.NONE
                        }
                        return
                    }
                    when (e.keyCode) {
                        KeyEvent.VK_ESCAPE -> openPause()
                        KeyEvent.VK_TAB -> ui.overlay = Overlay.TRACKER
                        KeyEvent.VK_F11 -> { display.toggle(); compose() }
                        KeyEvent.VK_F -> session?.let { it.fastForward = !it.fastForward }
                KeyEvent.VK_F5 -> currentRom?.let { open ->
                    val file = GameSession.stateFile(open.absolutePath)
                    ui.message = if (session?.saveState(file) == true) "state saved" else "could not save the state"
                }
                KeyEvent.VK_F9 -> currentRom?.let { open ->
                    val file = GameSession.stateFile(open.absolutePath)
                    ui.message = if (session?.loadState(file) == true) "state loaded" else "no state saved yet"
                }
                        else -> keyToButton(e.keyCode)?.let { session?.setButton(it, true) }
                    }
                }
                Screen.FLAGS -> when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_W -> { flagSelected = (flagSelected - 1).coerceAtLeast(0); compose() }
                    KeyEvent.VK_DOWN, KeyEvent.VK_S -> { flagSelected = (flagSelected + 1).coerceAtMost(generateMenuItems().size - 1); compose() }
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> generateActivate()
                    KeyEvent.VK_ESCAPE -> { ui.screen = Screen.LIBRARY; libraryModel.reset(libraryItems()); compose() }
                }
                Screen.EDITOR -> when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_W -> { flagUi.moveSelection(-1); compose() }
                    KeyEvent.VK_DOWN, KeyEvent.VK_S -> { flagUi.moveSelection(1); compose() }
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> flagActivate()
                    KeyEvent.VK_ESCAPE -> {
                        if (flagUi.back() == FlagUi.Action.CLOSE_EDITOR) ui.screen = Screen.FLAGS
                        compose()
                    }
                }
                Screen.PRESETS -> when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_W -> { flagUi.moveSelection(-1); compose() }
                    KeyEvent.VK_DOWN, KeyEvent.VK_S -> { flagUi.moveSelection(1); compose() }
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> presetActivate()
                    KeyEvent.VK_ESCAPE -> {
                        if (flagUi.back() == FlagUi.Action.CLOSE_PRESETS) {
                            flagUi.closeBrowser()
                            ui.screen = Screen.FLAGS
                        }
                        compose()
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
    // A seed named on the command line is played straight away, but through the same
    // screen and pause logic as one chosen from the list - the app has one way to play.
    config.rom?.let { play(resolveRom(config, it)) }
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
    val session = GameSession(resolveRom(config, rom).absoluteFile.path, findNativeDir(config), config.workDir)
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

        for (variant in listOf("game", "game-pause", "game-tracker", "game-checks")) {
            canvas.clear(Ff4Palette.BLACK)
            canvas.drawGameFrame(session.image(), picture)
            when (variant) {
                "game-pause" -> canvas.draw(renderer.pauseMenu(width, height, pauseItems(false, false), 0, "PAUSED"))
                "game-tracker" -> canvas.draw(
                    renderer.tracker(width, height, 0, wram, spoilerLocationsFor(File(rom)), loadChecks(config.checksFile), emptySet()),
                )
                "game-checks" -> canvas.draw(
                    renderer.tracker(width, height, 1, wram, emptyList(), loadChecks(config.checksFile), emptySet()),
                )
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
                status = listOf(
                    Ff4StatusLine("Free Enterprise 4.7.0.Gale"),
                    Ff4StatusLine("3 seed(s) ready"),
                ),
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
    // The quit confirmation, so its geometry can be measured rather than guessed.
    for (choice in listOf(0, 1)) {
        canvas.clear(Ff4Palette.BLACK)
        canvas.draw(
            renderer.mainMenu(
                width, height, menuItems(), 0,
                status = listOf(
                    Ff4StatusLine("Free Enterprise 4.7.0.Gale"),
                    Ff4StatusLine("3 seed(s) ready"),
                ),
                hint = "A  Select      B  Exit",
            ),
        )
        canvas.draw(renderer.scrim(width, height))
        canvas.draw(
            renderer.confirmDialog(
                width, height,
                "Quit",
                "Leave Free Enterprise?\nYour seeds and saves stay in /tmp/library.",
                listOf("Quit", "Cancel"),
                choice,
            ),
        )
        canvas.writePng(File(out, "confirm-dialog-$choice.png"))
    }
    // The flag editor screens, for the same reason as the dialog: to be measured.
    val flagUi = FlagUi(
        SpecLoader.uiSpec(Config.from(emptyArray()).uiSpecFile),
        SpecLoader.presets(Config.from(emptyArray()).presetsFile),
        FlagsetStore(File("/tmp/ff4shots-library")),
    )
    flagUi.showCategories()
    for ((index, name) in listOf("flags-categories", "flags-section", "flags-selector").withIndex()) {
        if (index == 1) flagUi.activate(0)                       // open the first section
        if (index == 2) {
            // Walk the sections until one has a selector ("none" means no option chosen),
            // so level 2 is genuinely rendered rather than re-rendering level 1.
            var found = false
            for (section in 0 until flagUi.items().size) {
                flagUi.showCategories()
                flagUi.activate(section)
                val selector = flagUi.items().indexOfFirst { it.value == "none" }
                if (selector >= 0) { flagUi.activate(selector); found = true; break }
            }
            if (!found) flagUi.showCategories()
        }
        canvas.clear(Ff4Palette.BLACK)
        canvas.draw(
            renderer.mainMenu(
                width, height, flagUi.items(), 0,
                status = flagUi.status(), hint = "",
            ),
        )
        canvas.draw(renderer.hintBox(width, height, flagUi.hintLines()))
        canvas.writePng(File(out, "$name.png"))
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

/**
 * The app's pause menu: Resume, Save State, Load State (disabled when there is none),
 * Fast-forward (showing on/off), Checks tracker, Reset Core, Exit to sessions.
 * Built when the menu opens so the values are current.
 */
private fun pauseItems(hasState: Boolean, fastForward: Boolean) = listOf(
    Ff4MenuItem("Resume"),
    Ff4MenuItem("Save State"),
    Ff4MenuItem("Load State", if (hasState) "load" else "none", enabled = hasState),
    Ff4MenuItem("Fast-forward", if (fastForward) "on" else "off"),
    Ff4MenuItem("Checks tracker", "Tab"),
    Ff4MenuItem("Reset Core"),
    Ff4MenuItem("Exit to sessions"),
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

/**
 * What the hint bar says, per context. One place, so the two play loops and the
 * overlays cannot disagree about the controls.
 */
private const val HINT_VISIBLE_MILLIS = 12_000L

/**
 * Whether to draw the hint bar right now.
 *
 * Not always: the game draws its dialogue along the bottom of the screen, so a
 * permanent bar would sit on top of it. It appears when a session starts - long
 * enough to read the keys - and whenever one of our menus is open, which is when a
 * player is looking for the controls anyway.
 */
private fun hintVisible(overlay: Overlay, playingSince: Long): Boolean =
    overlay != Overlay.NONE || System.currentTimeMillis() - playingSince < HINT_VISIBLE_MILLIS

private fun inGameHint(overlay: Overlay, padConnected: Boolean): String = when (overlay) {
    Overlay.PAUSE -> "Up/Down  Move      Enter  Select      Esc  Close"
    Overlay.TRACKER -> "Left/Right  Page      Tab  Close"
    Overlay.NONE -> buildString {
        append("Tab  Tracker   Esc  Menu   F11  Window   F  Fast-forward   F5/F9  Save/Load")
        if (padConnected) append("      Pad: L2 Tracker   R2 Fast-forward")
    }
}

/** A modal text field: the app asks for a seed name and a flagset name this way. */
private class TextInput(
    val title: String,
    val prompt: String,
    var value: String,
    val onAccept: (String) -> Unit,
)

/**
 * Find a seed by name: beside the working directory first, then in FF4_FE, then by
 * bare file name in FF4_FE - the app's seeds live there, so `--rom PLAINNAME.smc`
 * should find one.
 */
private fun resolveRom(config: Config, path: String): File {
    val direct = File(path)
    if (direct.isFile) return direct
    val inLibrary = File(config.libraryDir, path)
    if (inLibrary.isFile) return inLibrary
    val byName = File(config.libraryDir, direct.name)
    if (byName.isFile) return byName
    return direct
}

/** The picture surface: the canvas, scaled to whatever size the window is. */
internal fun gamePanel(canvas: SoftwareCanvas) = object : JPanel() {
    override fun paintComponent(g: Graphics) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
        g2.drawImage(canvas.image, 0, 0, width, height, null)
    }
}.apply {
    isFocusable = true
    // Tab is a focus-traversal key by default, so it never reaches a KeyListener.
    // That is why the tracker shortcut did nothing.
    setFocusTraversalKeysEnabled(false)
}

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
