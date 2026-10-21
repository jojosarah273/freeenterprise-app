package com.freeenterprise.desktop

import com.freeenterprise.ff4.Ff4FlagControl
import com.freeenterprise.ff4.Ff4FlagEditor
import com.freeenterprise.ff4.Ff4FlagSection
import com.freeenterprise.ff4.Ff4MenuItem
import com.freeenterprise.ff4.Ff4Palette
import com.freeenterprise.ff4.Ff4StatusLine

/**
 * The flag editor and preset browser, mirroring the app's two screens.
 *
 * The editor is FF4FlagEditorActivity: categories, a section's controls, and a
 * selector's options. The browser is FF4PresetActivity: "My flagsets" first, then
 * the website's preset categories, with applying anything returning to the Generate
 * screen.
 *
 * Two behaviours that are easy to get wrong and are copied deliberately:
 *
 *  * the cursor is remembered *per level* - backing out of a section, or out of a
 *    selector, returns to the row you came from, exactly as the app's
 *    rememberedSelection does;
 *  * presets are their own screen, not a page of the editor, so backing out of them
 *    lands on Generate rather than on the editor.
 */
class FlagUi(
    private val sections: List<Ff4FlagSection>,
    private val presets: List<SpecLoader.PresetCategory>,
    private val store: FlagsetStore,
) {
    enum class Mode { CATEGORIES, SECTION, SELECTOR }

    /** Which browser page is showing; NONE means the editor is in charge. */
    enum class Browse { NONE, CATEGORIES, PRESETS, MY_FLAGSETS, DELETE_FLAGSET }

    /** What activating a row did. */
    enum class Action { STAY, CLOSE_PRESETS, CLOSE_EDITOR }

    private sealed class Act {
        class Toggle(val flag: String) : Act()
        class OpenSelector(val control: Ff4FlagControl) : Act()
        class ChooseOption(val control: Ff4FlagControl, val optionFlag: String) : Act()
        class OpenCategory(val index: Int) : Act()
        class ApplyPreset(val flags: String, val name: String) : Act()
        object OpenMyFlagsets : Act()
        object OpenDeleteFlagsets : Act()
        class DeleteFlagset(val name: String) : Act()
        object Back : Act()
    }

    private data class Row(
        val title: String,
        val value: String,
        val indent: Int,
        val subtitle: String?,
        val flag: String?,
        val description: String?,
    )

    val editor = Ff4FlagEditor(sections)

    var mode: Mode = Mode.CATEGORIES
        private set
    var browse: Browse = Browse.NONE
        private set

    private var currentSection: Ff4FlagSection? = null
    private var category: SpecLoader.PresetCategory? = null

    private var actions: List<Act> = emptyList()
    private var rows: List<Row> = emptyList()

    /** The cursor, and the memory that puts it back where it was per level. */
    var selection: Int = 0
        private set
    private val remembered = HashMap<String, Int>()

    /** The preset or flagset last applied, shown on the Generate screen. */
    var appliedName: String = ""
        private set

    var canonicalFlags: String = ""
        private set
    var valid: Boolean = true
        private set
    var errors: List<String> = emptyList()
        private set

    /** The browser's status, which differs per page as it does in the app. */
    fun status(): List<Ff4StatusLine> = when (browse) {
        Browse.CATEGORIES -> listOf(
            Ff4StatusLine("Presets - pick a category"),
            Ff4StatusLine("\"My flagsets\" holds the flagsets you saved (${store.list().size})."),
        )
        Browse.PRESETS -> listOf(Ff4StatusLine(category?.name.orEmpty()))
        Browse.MY_FLAGSETS -> {
            val sets = store.list()
            if (sets.isEmpty()) {
                listOf(
                    Ff4StatusLine("No saved flagsets yet.", Ff4Palette.YELLOW),
                    Ff4StatusLine("Save one from the Generate screen."),
                )
            } else {
                listOf(
                    Ff4StatusLine("Your flagsets - ${sets.size} saved"),
                    Ff4StatusLine("Pick one to apply it, or delete below."),
                )
            }
        }
        Browse.DELETE_FLAGSET -> listOf(
            Ff4StatusLine("Delete which flagset?", Ff4Palette.YELLOW),
            Ff4StatusLine("Saved rulesets only; seeds are untouched."),
        )
        Browse.NONE -> {
            val flags = canonicalFlags.ifBlank { editor.flagsText() }
            val first = Ff4StatusLine(flags.ifBlank { "No flags yet" })
            val second = if (valid) {
                Ff4StatusLine(if (flags.isBlank()) "" else "Flags OK", Ff4Palette.GREEN)
            } else {
                Ff4StatusLine(
                    "${errors.size} logical error(s)" + (errors.firstOrNull()?.let { " - $it" } ?: ""),
                    Ff4Palette.PINK,
                )
            }
            listOf(first, second)
        }
    }

    fun hint(): String = when (browse) {
        Browse.CATEGORIES -> "A  Open      B  Back"
        Browse.PRESETS -> "A  Apply      B  Back"
        Browse.MY_FLAGSETS -> "A  Apply      B  Back"
        Browse.DELETE_FLAGSET -> "A  Delete      B  Back"
        Browse.NONE -> when (mode) {
            Mode.CATEGORIES -> "A  Open      B  Exit"
            Mode.SELECTOR -> "A  Choose      B  Back"
            Mode.SECTION -> "A  Toggle      B  Back"
        }
    }

    /** The app's three-line hint for the editor: title [flag], description, keys. */
    fun hintLines(): List<String> {
        if (browse != Browse.NONE) return listOf(hint())
        val row = rows.getOrNull(selection) ?: return listOf(hint())
        val title = buildString {
            append(row.title)
            if (!row.flag.isNullOrBlank()) append("  [").append(row.flag).append("]")
        }
        return listOf(title, row.description?.let { stripHtml(it) }.orEmpty(), hint())
    }

    /** Move the cursor within the current page. */
    fun moveSelection(delta: Int) {
        selection = (selection + delta).coerceIn(0, maxOf(0, rows.lastIndex))
    }

    fun items(): List<Ff4MenuItem> = rows.map {
        Ff4MenuItem(it.title, it.value, indent = it.indent, subtitle = it.subtitle)
    }

    // ------------------------------------------------------------------ editor

    fun showCategories() {
        browse = Browse.NONE
        mode = Mode.CATEGORIES
        currentSection = null
        rows = sections.map { Row(it.title, "${enabledCount(it)} on", 0, null, null, null) }
        actions = sections.map { Act.OpenCategory(-1) }
        restore("cats")
    }

    private fun showSection() {
        val section = currentSection ?: return showCategories()
        mode = Mode.SECTION
        val newRows = mutableListOf<Row>()
        val newActs = mutableListOf<Act>()

        fun walk(controls: List<Ff4FlagControl>, indent: Int) {
            for (control in controls) {
                if (control.isSeparator) continue
                if (control.isSelect) {
                    val current = control.subcontrols.firstOrNull { editor.isEnabled(it.flag) }
                    newRows += Row(control.title, current?.title ?: "none", indent, null, null, control.description)
                    newActs += Act.OpenSelector(control)
                    if (current != null) walk(current.subcontrols, indent + 1)
                    continue
                }
                val on = editor.isEnabled(control.flag)
                newRows += Row(control.title, if (on) "ON" else "OFF", indent, null, control.flag, control.description)
                newActs += Act.Toggle(control.flag)
                if (on && control.subcontrols.isNotEmpty()) walk(control.subcontrols, indent + 1)
            }
        }
        walk(section.controls, 0)
        rows = newRows
        actions = newActs
        restore("section:" + section.title)
    }

    private fun showSelector(control: Ff4FlagControl) {
        mode = Mode.SELECTOR
        val newRows = mutableListOf<Row>()
        val newActs = mutableListOf<Act>()
        if (!control.important) {
            newRows += Row("(none)", if (control.subcontrols.none { editor.isEnabled(it.flag) }) "ON" else "", 0, null, null, null)
            newActs += Act.ChooseOption(control, "")
        }
        for (option in control.subcontrols) {
            newRows += Row(option.title, if (editor.isEnabled(option.flag)) "ON" else "", 0, null, option.flag, option.description)
            newActs += Act.ChooseOption(control, option.flag)
        }
        rows = newRows
        actions = newActs
        restore("selector:" + control.flag)
    }

    // ---------------------------------------------------------------- browser

    fun openPresets() {
        browse = Browse.CATEGORIES
        category = null
        val saved = store.list().size
        val newRows = mutableListOf<Row>()
        val newActs = mutableListOf<Act>()
        newRows += Row("My flagsets", "$saved saved", 0, null, null, null)
        newActs += Act.OpenMyFlagsets
        for ((index, cat) in presets.withIndex()) {
            newRows += Row(cat.name, "${cat.presets.size}", 0, null, null, null)
            newActs += Act.OpenCategory(index)
        }
        actions = newActs
        rows = newRows
        restore("presetcats")
    }

    private fun openCategory(index: Int) {
        val cat = presets.getOrNull(index) ?: return
        category = cat
        browse = Browse.PRESETS
        rows = cat.presets.map { Row(it.name, "", 0, stripHtml(it.description).take(140), null, it.description) }
        actions = cat.presets.map { Act.ApplyPreset(it.flags, it.name) }
        restore("presets:" + cat.name)
    }

    private fun openMyFlagsets() {
        browse = Browse.MY_FLAGSETS
        val sets = store.list()
        val newRows = mutableListOf<Row>()
        val newActs = mutableListOf<Act>()
        for (set in sets) {
            newRows += Row(set.name, "ruleset", 0, null, null, null)
            newActs += Act.ApplyPreset(set.flags, set.name)
        }
        newRows += Row("Delete a flagset...", "", 0, null, null, null)
        newActs += Act.OpenDeleteFlagsets
        newRows += Row("Back", "", 0, null, null, null)
        newActs += Act.Back
        rows = newRows
        actions = newActs
        restore("myflagsets")
    }

    private fun openDeleteFlagsets() {
        browse = Browse.DELETE_FLAGSET
        val sets = store.list()
        rows = sets.map { Row(it.name, "delete", 0, null, null, null) } +
            listOf(Row("Back", "", 0, null, null, null))
        actions = sets.map { Act.DeleteFlagset(it.name) } + Act.Back
        restore("deleteflagsets")
    }

    // ---------------------------------------------------------------- actions

    /** Returns what the caller should do with the screen. */
    fun activate(index: Int): Action {
        selection = index
        remember()
        return when (browse) {
            Browse.CATEGORIES -> {
                when (val act = actions.getOrNull(index)) {
                    is Act.OpenCategory -> openCategory(act.index)
                    is Act.OpenMyFlagsets -> openMyFlagsets()
                    else -> Unit
                }
                Action.STAY
            }
            Browse.PRESETS -> {
                val act = actions.getOrNull(index)
                if (act is Act.ApplyPreset) {
                    applyFlags(act.flags, act.name)
                    return Action.CLOSE_PRESETS
                }
                Action.STAY
            }
            Browse.MY_FLAGSETS -> {
                when (val act = actions.getOrNull(index)) {
                    is Act.ApplyPreset -> { applyFlags(act.flags, act.name); return Action.CLOSE_PRESETS }
                    is Act.OpenDeleteFlagsets -> openDeleteFlagsets()
                    is Act.Back -> return Action.CLOSE_PRESETS
                    else -> Unit
                }
                Action.STAY
            }
            Browse.DELETE_FLAGSET -> {
                when (val act = actions.getOrNull(index)) {
                    is Act.DeleteFlagset -> { store.delete(act.name); openMyFlagsets() }
                    is Act.Back -> openMyFlagsets()
                    else -> Unit
                }
                Action.STAY
            }
            Browse.NONE -> {
                when (mode) {
                    Mode.CATEGORIES -> {
                        currentSection = sections.getOrNull(index)
                        if (currentSection != null) showSection()
                    }
                    Mode.SECTION -> when (val act = actions.getOrNull(index)) {
                        is Act.Toggle -> {
                            editor.toggle(act.flag)
                            showSection()      // rebuild: children appear and disappear
                            return Action.STAY
                        }
                        is Act.OpenSelector -> showSelector(act.control)
                        else -> Unit
                    }
                    Mode.SELECTOR -> {
                        val act = actions.getOrNull(index)
                        if (act is Act.ChooseOption) {
                            editor.chooseExclusive(act.control.subcontrols.map { it.flag }, act.optionFlag)
                            showSection()
                        }
                    }
                }
                Action.STAY
            }
        }
    }

    /** Back one level. Returns CLOSE_* when there is nowhere further up. */
    fun back(): Action {
        remember()
        return when (browse) {
            Browse.DELETE_FLAGSET -> { openMyFlagsets(); Action.STAY }
            Browse.MY_FLAGSETS -> { openPresets(); Action.STAY }
            Browse.PRESETS -> { openPresets(); Action.STAY }
            Browse.CATEGORIES -> Action.CLOSE_PRESETS
            Browse.NONE -> when (mode) {
                Mode.SELECTOR -> { showSection(); Action.STAY }
                Mode.SECTION -> { showCategories(); Action.STAY }
                Mode.CATEGORIES -> Action.CLOSE_EDITOR
            }
        }
    }

    /** Leaving the browser: the Generate screen shows the flagset again, not a
     *  preset page's status. */
    fun closeBrowser() {
        if (browse == Browse.NONE) return
        browse = Browse.NONE
        showCategories()
    }

    fun saveCurrentFlags() {
        val flags = editor.flagsText()
        if (flags.isBlank()) return
        val name = flags.split(" ").filter { !it.startsWith("-") }.take(2).joinToString("+").ifBlank { "Flagset" }
        store.save(name, flags)
    }

    fun applyEngine(canonical: String, isValid: Boolean, errorMessages: List<String>) {
        canonicalFlags = canonical
        valid = isValid
        errors = errorMessages
    }

    private fun applyFlags(flags: String, name: String) {
        appliedName = name
        editor.clear()
        editor.addAll(flags.split(" ").filter { it.isNotBlank() })
        browse = Browse.NONE
        showCategories()
    }

    private fun remember() {
        remembered[key()] = selection
    }

    private fun restore(newKey: String) {
        selection = (remembered[newKey] ?: 0).coerceIn(0, maxOf(0, rows.lastIndex))
    }

    private fun key(): String = when (browse) {
        Browse.CATEGORIES -> "presetcats"
        Browse.PRESETS -> "presets:" + category?.name.orEmpty()
        Browse.MY_FLAGSETS -> "myflagsets"
        Browse.DELETE_FLAGSET -> "deleteflagsets"
        Browse.NONE -> when (mode) {
            Mode.CATEGORIES -> "cats"
            Mode.SECTION -> "section:" + currentSection?.title.orEmpty()
            Mode.SELECTOR -> "selector"
        }
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

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}
