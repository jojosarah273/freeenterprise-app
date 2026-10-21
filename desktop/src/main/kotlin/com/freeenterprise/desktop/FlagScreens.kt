package com.freeenterprise.desktop

import com.freeenterprise.ff4.Ff4FlagControl
import com.freeenterprise.ff4.Ff4FlagEditor
import com.freeenterprise.ff4.Ff4FlagSection
import com.freeenterprise.ff4.Ff4MenuItem
import com.freeenterprise.ff4.Ff4Palette
import com.freeenterprise.ff4.Ff4StatusLine

/**
 * The flag editor, mirroring the app's FF4FlagEditorActivity.
 *
 * Three levels, exactly as the app has them:
 *
 *   level 0  categories - one row per section, with a count of what is on
 *   level 1  a section's controls - A toggles a flag, or opens a selector; a select
 *            shows its current option and, beneath it, that option's own controls;
 *            a toggle that is on shows its children the same way
 *   level 2  a selector's options - A picks one, exclusively, and returns to level 1
 *
 * The same rows, values ("ON"/"OFF", an option's name, "none", "(none)"), indents and
 * hint text as the app, because the two should be the same interface rather than two
 * takes on it. The app renders HTML in the hint; here the tags are stripped.
 *
 * Validation is the engine's: `check_flags` canonicalises the set and runs the same
 * logic checks the website does, so the status box can say "Flags OK" or how many
 * logical errors there are. The caller supplies that result through [applyEngine].
 */
class FlagUi(
    private val sections: List<Ff4FlagSection>,
    private val presets: List<SpecLoader.PresetCategory>,
    private val store: FlagsetStore,
) {
    enum class Mode { CATEGORIES, SECTION, SELECTOR }

    /** What activating a row does. */
    private sealed class Action {
        object OpenSection : Action()
        class Toggle(val flag: String) : Action()
        class OpenSelector(val control: Ff4FlagControl) : Action()
        class ChooseOption(val control: Ff4FlagControl, val optionFlag: String) : Action()
    }

    private data class Row(
        val title: String,
        val value: String,
        val indent: Int,
        val flag: String?,
        val description: String?,
    )

    val editor = Ff4FlagEditor(sections)

    var mode: Mode = Mode.CATEGORIES
        private set
    private var currentSection: Ff4FlagSection? = null
    private var selectorControl: Ff4FlagControl? = null

    private var actions: List<Action> = emptyList()
    private var rows: List<Row> = emptyList()

    /** The engine's answer; the desktop fills these in after each change. */
    var canonicalFlags: String = ""
        private set
    var valid: Boolean = true
        private set
    var errors: List<String> = emptyList()
        private set

    /** Somewhere else to be: the presets and saved sets pages hang off this. */
    var extra: Extra? = null
        private set
    enum class Extra { PRESET_CATEGORIES, PRESET_LIST, FLAGSETS }
    private var category: SpecLoader.PresetCategory? = null

    /** The preset or flagset last applied, shown on the generate screen. */
    var appliedName: String = ""
        private set

    /** Called after any change, so the caller can compose and re-check. */
    var onChanged: (() -> Unit)? = null

    // ------------------------------------------------------------------- rows

    fun items(): List<Ff4MenuItem> = when (extra) {
        Extra.PRESET_CATEGORIES -> presets.map { Ff4MenuItem(it.name, "${it.presets.size}") }
            .plus(Ff4MenuItem("Back"))
        Extra.PRESET_LIST -> (category?.presets.orEmpty()).map { Ff4MenuItem(it.name, it.description.take(22)) }
            .plus(Ff4MenuItem("Back"))
        Extra.FLAGSETS -> store.list().map { Ff4MenuItem(it.name, it.flags.take(22)) }
            .plus(Ff4MenuItem("Back"))
        null -> rows.map { Ff4MenuItem(it.title, it.value, indent = it.indent) }
    }

    /** The two status lines the app shows: the flagset, and whether it is valid. */
    fun status(): List<Ff4StatusLine> {
        val flags = canonicalFlags.ifBlank { editor.flagsText() }
        val first = Ff4StatusLine(flags.ifBlank { "No flags yet" })
        val second = if (valid) {
            Ff4StatusLine(if (flags.isBlank()) "" else "Flags OK", Ff4Palette.GREEN)
        } else {
            val firstError = errors.firstOrNull()?.let { " — $it" } ?: ""
            Ff4StatusLine("${errors.size} logical error(s)$firstError", Ff4Palette.PINK)
        }
        return listOf(first, second)
    }

    /** The app's hint, as the lines its three-line hint box shows. */
    fun hintLines(selected: Int): List<String> {
        val nav = navigationHint()
        val row = rows.getOrNull(selected)
        if (row == null) return listOf(nav)
        val title = buildString {
            append(row.title)
            if (!row.flag.isNullOrBlank()) append("  [").append(row.flag).append("]")
        }
        val description = row.description?.let { stripHtml(it) }.orEmpty()
        return listOf(title, description, nav)
    }

    private fun navigationHint(): String =
        when (extra) {
            Extra.PRESET_CATEGORIES, Extra.PRESET_LIST, Extra.FLAGSETS -> "A  Open      B  Back"
            null -> when (mode) {
                Mode.CATEGORIES -> "A  Open      B  Exit"
                Mode.SELECTOR -> "A  Choose      B  Back"
                Mode.SECTION -> "A  Toggle      B  Back"
            }
        }

    /** The app's hint: the row's title, its flag, its description, then the keys. */
    fun hint(selected: Int): String {
        val nav = when (extra) {
            Extra.PRESET_CATEGORIES, Extra.PRESET_LIST, Extra.FLAGSETS -> "A Open    B Back"
            null -> when (mode) {
                Mode.CATEGORIES -> "A Open    B Exit"
                Mode.SELECTOR -> "A Choose    B Back"
                Mode.SECTION -> "A Toggle    B Back"
            }
        }
        val row = rows.getOrNull(selected) ?: return nav
        return buildString {
            append(row.title)
            if (!row.flag.isNullOrBlank()) append("  [").append(row.flag).append("]")
            val description = row.description?.let { stripHtml(it) }
            if (!description.isNullOrBlank()) append("\n").append(description)
            append("\n").append(nav)
        }
    }

    // ---------------------------------------------------------------- actions

    fun activate(selected: Int) {
        when (extra) {
            Extra.PRESET_CATEGORIES -> {
                if (selected >= presets.size) extra = null
                else { category = presets.getOrNull(selected); extra = Extra.PRESET_LIST }
            }
            Extra.PRESET_LIST -> {
                val list = category?.presets.orEmpty()
                if (selected >= list.size) extra = Extra.PRESET_CATEGORIES
                else list.getOrNull(selected)?.let { applyFlags(it.flags, it.name) }
            }
            Extra.FLAGSETS -> {
                val sets = store.list()
                if (selected >= sets.size) extra = null
                else sets.getOrNull(selected)?.let { applyFlags(it.flags, it.name) }
            }
            null -> when (mode) {
                Mode.CATEGORIES -> {
                    currentSection = sections.getOrNull(selected)
                    if (currentSection != null) showSection()
                }
                Mode.SECTION -> when (val action = actions.getOrNull(selected)) {
                    is Action.Toggle -> {
                        editor.toggle(action.flag)
                        showSection()      // rebuild: children appear and disappear
                    }
                    is Action.OpenSelector -> showSelector(action.control)
                    else -> Unit
                }
                Mode.SELECTOR -> {
                    val action = actions.getOrNull(selected)
                    if (action is Action.ChooseOption) {
                        val options = action.control.subcontrols.map { it.flag }
                        editor.chooseExclusive(options, action.optionFlag)
                        showSection()
                    }
                }
            }
        }
        onChanged?.invoke()
    }

    /** Back one level. True when there is nowhere further up. */
    fun back(): Boolean {
        when {
            extra != null -> extra = when (extra) {
                Extra.PRESET_LIST -> Extra.PRESET_CATEGORIES
                else -> null
            }
            mode == Mode.SELECTOR -> showSection()
            mode == Mode.SECTION -> showCategories()
            else -> return true
        }
        onChanged?.invoke()
        return false
    }

    /** Open the presets page (from the flags home). */
    fun openPresets() { extra = Extra.PRESET_CATEGORIES; onChanged?.invoke() }
    fun openFlagsets() { extra = Extra.FLAGSETS; onChanged?.invoke() }
    fun closeExtra() { extra = null; onChanged?.invoke() }

    fun saveCurrentFlags() {
        val flags = editor.flagsText()
        if (flags.isBlank()) return
        val name = flags.split(" ").filter { !it.startsWith("-") }.take(2).joinToString("+").ifBlank { "Flagset" }
        store.save(name, flags)
        onChanged?.invoke()
    }

    private fun applyFlags(flags: String, name: String = "") {
        appliedName = name
        editor.clear()
        editor.addAll(flags.split(" ").filter { it.isNotBlank() })
        extra = null
        showCategories()
    }

    // ----------------------------------------------------------------- levels

    fun showCategories() {
        mode = Mode.CATEGORIES
        currentSection = null
        selectorControl = null
        rows = sections.map { Row(it.title, "${enabledCount(it)} on", 0, null, null) }
        actions = sections.map { Action.OpenSection }
    }

    private fun showSection() {
        val section = currentSection ?: return showCategories()
        mode = Mode.SECTION
        selectorControl = null

        val newRows = mutableListOf<Row>()
        val newActions = mutableListOf<Action>()

        fun walk(controls: List<Ff4FlagControl>, indent: Int) {
            for (control in controls) {
                if (control.isSeparator) continue
                if (control.isSelect) {
                    val current = control.subcontrols.firstOrNull { editor.isEnabled(it.flag) }
                    newRows += Row(control.title, current?.title ?: "none", indent, null, control.description)
                    newActions += Action.OpenSelector(control)
                    // The chosen option carries its own controls; show them, as the app does.
                    if (current != null) walk(current.subcontrols, indent + 1)
                    continue
                }
                val on = editor.isEnabled(control.flag)
                newRows += Row(control.title, if (on) "ON" else "OFF", indent, control.flag, control.description)
                newActions += Action.Toggle(control.flag)
                if (on && control.subcontrols.isNotEmpty()) walk(control.subcontrols, indent + 1)
            }
        }
        walk(section.controls, 0)

        rows = newRows
        actions = newActions
    }

    private fun showSelector(control: Ff4FlagControl) {
        mode = Mode.SELECTOR
        selectorControl = control

        val newRows = mutableListOf<Row>()
        val newActions = mutableListOf<Action>()
        if (!control.important) {
            newRows += Row("(none)", if (control.subcontrols.none { editor.isEnabled(it.flag) }) "ON" else "", 0, null, null)
            newActions += Action.ChooseOption(control, "")
        }
        for (option in control.subcontrols) {
            newRows += Row(option.title, if (editor.isEnabled(option.flag)) "ON" else "", 0, option.flag, option.description)
            newActions += Action.ChooseOption(control, option.flag)
        }
        rows = newRows
        actions = newActions
    }

    private fun enabledCount(section: Ff4FlagSection): Int {
        var count = 0
        fun walk(controls: List<Ff4FlagControl>) {
            for (control in controls) {
                if (!control.isContainer && !control.isSeparator && !control.isSelect && editor.isEnabled(control.flag)) {
                    count++
                }
                if (control.subcontrols.isNotEmpty()) walk(control.subcontrols)
            }
        }
        walk(section.controls)
        return count
    }

    /** The engine's canonicalisation and logic check for the current set. */
    fun applyEngine(canonical: String, isValid: Boolean, errorMessages: List<String>) {
        canonicalFlags = canonical
        valid = isValid
        errors = errorMessages
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}
