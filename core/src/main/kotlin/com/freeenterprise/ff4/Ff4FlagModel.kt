package com.freeenterprise.ff4

/**
 * One control in the flag editor, as the generated `ui_spec.json` describes it.
 *
 * Controls nest: a container (its flag starts with `@`) holds sub-controls, a
 * `select` offers a set of mutually exclusive options, and a `toggle` is on or off.
 */
data class Ff4FlagControl(
    val flag: String,
    val title: String,
    /** "toggle", "select" or "separator". */
    val type: String = "toggle",
    val hard: Boolean = false,
    val compact: Boolean = false,
    val nullFlag: Boolean = false,
    val important: Boolean = false,
    /** HTML on the website; shown as plain text. */
    val description: String? = null,
    val subcontrols: List<Ff4FlagControl> = emptyList(),
) {
    val isContainer: Boolean get() = flag.startsWith("@")
    val isSelect: Boolean get() = type == "select"
    val isSeparator: Boolean get() = type == "separator"
}

data class Ff4FlagSection(val title: String, val controls: List<Ff4FlagControl>)

/**
 * The flag editor's state machine, a faithful port of the app's FlagEditorModel
 * (itself a port of the website's `make_react.js`).
 *
 * The behaviour that matters and is easy to get wrong:
 *
 *  * toggling a control inside a container auto-enables the `@` containers above it,
 *    so the section opens itself;
 *  * `@` containers never emit flags of their own, they only gather;
 *  * a `select` emits exactly one of its options;
 *  * the visible flag list is rebuilt from the spec tree, not from the order things
 *    were clicked, so the result is stable and matches the website.
 *
 * The resulting list is handed to the engine, which canonicalises it (and reports
 * anything contradictory) - that step is the platform's job, because it needs the
 * engine, not because it is complicated.
 */
class Ff4FlagEditor(private val sections: List<Ff4FlagSection>) {

    private val enabled = LinkedHashMap<String, Boolean>()

    /** The flags as the user has them, before the engine canonicalises them. */
    fun currentFlagList(): List<String> = enabled.keys.toList()

    fun isEnabled(flag: String): Boolean = enabled.containsKey(flag)

    fun toggle(flag: String) = mutate(flag, !isEnabled(flag))

    fun setFlag(flag: String, on: Boolean) = mutate(flag, on)

    /** A select sets exactly one of [options]. */
    fun chooseExclusive(options: List<String>, chosen: String) {
        for (option in options) setEnabled(option, option == chosen)
        compute()
    }

    fun addAll(flags: List<String>) {
        for (flag in flags) if (flag.isNotBlank()) enabled[flag] = true
        compute()
    }

    fun clear() {
        enabled.clear()
    }

    /**
     * The flag list to send to the engine: walk the spec in order and emit every
     * enabled non-container control, descending through enabled selects.
     */
    fun rebuildFlagList(): List<String> {
        val visible = mutableListOf<String>()
        fun walk(controls: List<Ff4FlagControl>) {
            for (control in controls) {
                if (isEnabled(control.flag) || control.isSelect) {
                    if (!control.isContainer) visible.add(control.flag)
                    if (control.subcontrols.isNotEmpty()) walk(control.subcontrols)
                }
            }
        }
        for (section in sections) walk(section.controls)
        return visible
    }

    /** The flags as a space-separated string, which is what the engine takes. */
    fun flagsText(): String = rebuildFlagList().joinToString(" ")

    private fun mutate(flag: String, on: Boolean) {
        setEnabled(flag, on)
        compute()
    }

    private fun setEnabled(flag: String, on: Boolean) {
        if (on) enabled[flag] = true else enabled.remove(flag)
    }

    /** Auto-enable the `@` containers above anything that is on. */
    private fun compute() {
        for (container in requiredContainers()) enabled[container] = true
    }

    private fun requiredContainers(): Set<String> {
        val required = HashSet<String>()

        fun walk(controls: List<Ff4FlagControl>, containers: List<String>) {
            for (control in controls) {
                if (control.isContainer || enabled.containsKey(control.flag)) {
                    val next = if (control.isContainer) containers + control.flag else containers
                    if (!control.isContainer && enabled.containsKey(control.flag)) {
                        required.addAll(containers)
                    }
                    if (control.subcontrols.isNotEmpty()) walk(control.subcontrols, next)
                }
            }
        }

        for (section in sections) walk(section.controls, emptyList())
        return required
    }

    companion object {
        /** Doors and entrances randomization ship in the engine but are not offered. */
        const val DOORS_ENABLED = false

        private val DOORS = Regex("^-?@?(doors|entrances)rando", RegexOption.IGNORE_CASE)

        fun isHidden(flag: String): Boolean = !DOORS_ENABLED && DOORS.containsMatchIn(flag)

        /** Presets that use the hidden feature are filtered out the same way. */
        fun usesHiddenFeature(flags: String): Boolean =
            !DOORS_ENABLED && Regex("(doors|entrances)rando", RegexOption.IGNORE_CASE).containsMatchIn(flags)
    }
}
