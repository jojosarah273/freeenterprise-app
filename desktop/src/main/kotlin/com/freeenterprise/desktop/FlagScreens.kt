package com.freeenterprise.desktop

import com.freeenterprise.ff4.Ff4FlagControl
import com.freeenterprise.ff4.Ff4FlagEditor
import com.freeenterprise.ff4.Ff4FlagSection
import com.freeenterprise.ff4.Ff4MenuItem
import com.freeenterprise.ff4.Ff4MenuLayout
import com.freeenterprise.ff4.Ff4MenuModel
import com.freeenterprise.ff4.Ff4Palette
import com.freeenterprise.ff4.Ff4Rect
import com.freeenterprise.ff4.Ff4Scale
import com.freeenterprise.ff4.Ff4ScreenLayout
import com.freeenterprise.ff4.Ff4WindowChrome
import com.freeenterprise.ff4.emitTo

/**
 * The flag editor, over the desktop build's screens: the same structure as the app's
 * FF4 flag editor, driven by the same `ui_spec.json`.
 *
 * Navigation mirrors the app: sections, then a section's controls, with containers
 * opening another level and selects opening their options. Each row shows its state
 * on the right, the way the app's rows do, and the control's description goes in the
 * status box.
 */
class FlagUi(
    private val sections: List<Ff4FlagSection>,
    private val presets: List<SpecLoader.PresetCategory>,
    private val store: FlagsetStore,
) {
    val editor = Ff4FlagEditor(sections)
    private var loadedPreset = ""

    /** Where we are: the flag home, a section's controls, presets, or saved sets. */
    enum class Page { HOME, CONTROLS, PRESET_CATEGORIES, PRESET_LIST, FLAGSETS }

    var page: Page = Page.HOME
    var section: Ff4FlagSection? = null
    var container: Ff4FlagControl? = null
    var category: SpecLoader.PresetCategory? = null

    /** Controls currently listed: a section's, or a container's children. */
    private fun currentControls(): List<Ff4FlagControl> =
        container?.subcontrols ?: section?.controls.orEmpty()

    fun items(): List<Ff4MenuItem> = when (page) {
        Page.HOME -> listOf(
            Ff4MenuItem("Edit flags"),
            Ff4MenuItem("Presets", if (presets.isEmpty()) "none" else "${presets.sumOf { it.presets.size }} available"),
            Ff4MenuItem("My flagsets", "${store.list().size} saved"),
            Ff4MenuItem("Save these flags", editor.flagsText().ifBlank { "nothing set" }),
            Ff4MenuItem("Back"),
        )
        Page.CONTROLS -> when {
            section == null -> sections.map { Ff4MenuItem(it.title, "${it.controls.size}") }
                .plus(Ff4MenuItem("Back"))
            else -> currentControls().map { control -> row(control) }
                .plus(Ff4MenuItem("Back"))
        }
        Page.PRESET_CATEGORIES -> presets.map { Ff4MenuItem(it.name, "${it.presets.size}") }
            .plus(Ff4MenuItem("Back"))
        Page.PRESET_LIST -> (category?.presets ?: emptyList()).map { Ff4MenuItem(it.name, it.description.take(22)) }
            .plus(Ff4MenuItem("Back"))
        Page.FLAGSETS -> store.list().map { Ff4MenuItem(it.name, it.flags.take(22)) }
            .plus(Ff4MenuItem("Back"))
    }

    private fun row(control: Ff4FlagControl): Ff4MenuItem {
        val value = when {
            control.isContainer -> ">"
            control.isSelect -> ">"
            control.isSeparator -> ""
            editor.isEnabled(control.flag) -> "on"
            else -> "off"
        }
        return Ff4MenuItem(control.title.ifBlank { control.flag }, value, indent = if (control.hard) 1 else 0)
    }

    /** What the status box says: the flagset, or the highlighted control's description. */
    fun status(selected: Int): List<String> {
        val flags = editor.flagsText()
        val summary = if (flags.isBlank()) "No flags yet" else flags
        if (page != Page.CONTROLS) {
            val suffix = if (loadedPreset.isBlank()) "" else "  (preset: $loadedPreset)"
            return listOf(summary.take(58), "loaded:${if (flags.isBlank()) " -" else " ${editor.currentFlagList().size} flags"}$suffix")
        }
        if (section == null) {
            val title = sections.getOrNull(selected)?.title ?: "Sections"
            return listOf(summary.take(58), "Enter opens $title")
        }
        val control = currentControls().getOrNull(selected)
        val description = control?.description?.let { stripHtml(it) }?.takeIf { it.isNotBlank() }
        return listOf(summary.take(58), description?.take(58) ?: "Enter toggles, Back returns")
    }

    fun hint(): String = when (page) {
        Page.CONTROLS -> "Up/Down  Move      Enter  Change      Esc  Back"
        else -> "Up/Down  Move      Enter  Select      Esc  Back"
    }

    /**
     * Activate the row at [selected]. Returns what the caller should do: stay, go
     * back a level, generate a seed, or leave the flag screens.
     */
    enum class Action { STAY, BACK, GENERATE, CLOSE }

    fun activate(selected: Int): Action {
        return when (page) {
        Page.HOME -> when (selected) {
            0 -> { page = Page.CONTROLS; section = null; container = null; Action.STAY }
            1 -> { page = Page.PRESET_CATEGORIES; Action.STAY }
            2 -> { page = Page.FLAGSETS; Action.STAY }
            3 -> { saveCurrentFlags(); Action.STAY }
            else -> Action.CLOSE
        }
        Page.CONTROLS -> {
            if (section == null) {
                // The section list: enter one, or leave.
                if (selected >= sections.size) {
                    back()
                } else {
                    section = sections.getOrNull(selected)
                    container = null
                }
                return Action.STAY
            }
            val controls = currentControls()
            if (selected == controls.size) {
                back()
            } else {
                controls.getOrNull(selected)?.let { control ->
                    when {
                        control.isContainer -> container = control
                        control.isSelect -> chooseSelectOption(control)
                        else -> editor.toggle(control.flag)
                    }
                }
                Action.STAY
            }
        }
        Page.PRESET_CATEGORIES -> {
            if (selected == presets.size) {
                page = Page.HOME
            } else {
                category = presets.getOrNull(selected)
                page = Page.PRESET_LIST
            }
            Action.STAY
        }
        Page.PRESET_LIST -> {
            val list = category?.presets.orEmpty()
            if (selected == list.size) {
                page = Page.PRESET_CATEGORIES
            } else {
                list.getOrNull(selected)?.let { preset ->
                    editor.clear()
                    editor.addAll(preset.flags.split(" ").filter { it.isNotBlank() })
                    loadedPreset = preset.name
                    page = Page.HOME
                }
            }
            Action.STAY
        }
        Page.FLAGSETS -> {
            val sets = store.list()
            if (selected == sets.size) {
                page = Page.HOME
            } else {
                sets.getOrNull(selected)?.let { set ->
                    editor.clear()
                    editor.addAll(set.flags.split(" ").filter { it.isNotBlank() })
                    loadedPreset = set.name
                    page = Page.HOME
                }
            }
            Action.STAY
        }
    }
    }

    /**
     * A select offers its options as a sub-page. Rather than another page type, the
     * options are pushed into the current control list by swapping in a synthetic
     * container, which keeps navigation to the same two keys.
     */
    private fun chooseSelectOption(control: Ff4FlagControl) {
        val options = control.subcontrols.map { it.flag }
        if (options.isEmpty()) return
        // Cycle: each press picks the next option, wrapping. Predictable with a d-pad,
        // and it needs no third navigation key.
        val current = options.indexOfFirst { editor.isEnabled(it) }
        editor.chooseExclusive(options, options[(current + 1) % options.size])
    }

    /** Back one level; true when there is nowhere further up. */
    fun back(): Action {
        return when {
            page == Page.CONTROLS && container != null -> { container = null; Action.STAY }
            page == Page.CONTROLS && section != null -> { section = null; container = null; Action.STAY }
            page == Page.CONTROLS -> { page = Page.HOME; Action.STAY }
            page == Page.PRESET_LIST -> { page = Page.PRESET_CATEGORIES; Action.STAY }
            page == Page.PRESET_CATEGORIES || page == Page.FLAGSETS -> { page = Page.HOME; Action.STAY }
            page == Page.HOME -> Action.CLOSE
            else -> Action.STAY
        }
    }

    /** Sections are entered from the home screen: the first row of CONTROLS lists them. */
    fun openSections() {
        page = Page.CONTROLS
        section = null
        container = null
    }

    fun saveCurrentFlags() {
        val flags = editor.flagsText()
        if (flags.isBlank()) return
        // Named after its distinguishing flags, since the desktop shell has no text
        // entry yet: "Kmain Cvanilla" becomes "Kmain+Cvanilla".
        val name = flags.split(" ").filter { !it.startsWith("-") }.take(2).joinToString("+")
            .ifBlank { "Flagset" }
        store.save(name, flags)
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}

/**
 * The flag screens, drawn as draw lists: a status box, a scrolling list and a hint.
 * The list is the same widget as every other menu, so the cursor and the highlighting
 * behave identically.
 */
fun ScreenRenderer.flagScreen(
    width: Int,
    height: Int,
    items: List<Ff4MenuItem>,
    selected: Int,
    status: List<String>,
    hint: String,
): com.freeenterprise.ff4.Ff4DrawList = mainMenu(width, height, items, selected, status, hint)
