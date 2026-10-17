package com.freeenterprise.app

/**
 * 1:1 port of the flag editor state machine in make_react.js:
 *
 *   - `enabledItems`            : Map<flag, true> of every toggled control
 *   - `calculateRequiredContainers()` : auto-enable ancestor @ containers of
 *     enabled flags (so parent sections open themselves)
 *   - toggle/select edits       : mutate enabledItems then rebuild the "true"
 *     visible flag list (selects always recurse; @ controls never emit flags)
 *
 * The resulting flag LIST is handed to the engine (fe_bridge.flags_from_list)
 * which canonicalizes, encodes and runs FlagLogic.fix() — the same logic the
 * website uses — giving back the summary string, binary string and the
 * warnings/errors that gate generation.
 */
class FlagEditorModel(private val sections: List<UiSection>) {

    private val enabled = HashMap<String, Boolean>()

    // Results from the last engine round-trip (see applyEngineResult)
    var canonicalFlags: String = ""
        private set
    var binaryFlags: String = ""
        private set
    var log: List<Pair<String, String>> = emptyList()
        private set
    var valid: Boolean = false
        private set

    fun isEnabled(flag: String): Boolean = enabled.containsKey(flag)
    fun currentFlagList(): List<String> = enabled.keys.toList()

    fun toggle(flag: String) = mutate(flag, !isEnabled(flag))

    fun setFlag(flag: String, on: Boolean) = mutate(flag, on)

    /** Selector semantics: set exactly `chosen` among `options`. */
    fun chooseExclusive(options: List<String>, chosen: String) {
        for (o in options) setEnabled(o, o == chosen)
        compute()
        onModelChanged?.invoke()
    }

    private fun mutate(flag: String, on: Boolean) {
        setEnabled(flag, on)
        compute()
        onModelChanged?.invoke()
    }

    private fun setEnabled(flag: String, on: Boolean) {
        if (on) enabled[flag] = true else enabled.remove(flag)
    }

    /** Mirrors make_react.js calculateEnabledItems: enabled flags + required containers. */
    private fun compute() {
        calculateRequiredContainers().forEach { enabled[it] = true }
    }

    private fun calculateRequiredContainers(): Set<String> {
        val required = HashSet<String>()
        fun walk(spec: UiControl? /* null = section level */, branch: List<String>) {
            val children: List<UiControl> = if (spec == null) {
                sections.flatMap { it.controls }
            } else {
                spec.subcontrols
            }
            for (sub in children) {
                if (sub.isContainer || enabled.containsKey(sub.flag)) {
                    val nextBranch = if (sub.isContainer) branch + sub.flag else branch
                    if (!sub.isContainer && enabled.containsKey(sub.flag)) {
                        branch.forEach { required.add(it) }
                    }
                    if (sub.subcontrols.isNotEmpty()) walk(sub, nextBranch)
                }
            }
        }
        walk(null, emptyList())
        return required
    }

    /** Mirrors make_react.js handleEdits: derive the visible flag list from the spec tree. */
    fun rebuildFlagList(): List<String> {
        val visible = mutableListOf<String>()
        fun walk(controls: List<UiControl>) {
            for (c in controls) {
                if (isEnabled(c.flag) || c.isSelect) {
                    if (!c.isContainer) visible.add(c.flag)
                    if (c.subcontrols.isNotEmpty()) walk(c.subcontrols)
                }
            }
        }
        for (s in sections) walk(s.controls)
        return visible
    }

    /** Called when the engine answers flags_from_list: stores canonical state. */
    fun applyEngineResult(canonical: String, binary: String, log: List<Pair<String, String>>, valid: Boolean) {
        this.canonicalFlags = canonical
        this.binaryFlags = binary
        this.log = log
        this.valid = valid
        onEngineResult?.invoke()
    }

    fun addAll(flags: List<String>) {
        for (f in flags) enabled[f] = true
        compute()
        onModelChanged?.invoke()
    }

    fun clear() {
        enabled.clear()
        canonicalFlags = ""
        binaryFlags = ""
        log = emptyList()
        valid = false
        onModelChanged?.invoke()
    }

    /** UI hook fired when the model changes (toggle/select/apply/clear). */
    var onModelChanged: (() -> Unit)? = null

    /** UI hook fired when the engine round-trip lands. */
    var onEngineResult: (() -> Unit)? = null
}