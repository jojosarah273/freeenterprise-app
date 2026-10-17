package com.freeenterprise.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.view.KeyEvent
import android.widget.TextView
import com.freeenterprise.app.ff4ui.FF4Activity
import com.freeenterprise.app.ff4ui.FF4Menu
import com.freeenterprise.app.ff4ui.FF4Theme
import org.json.JSONObject

/**
 * FF4 menu-style flag editor (v0.6.0).
 *
 * Level 0: categories (one per ui_spec section).
 * Level 1: the section's controls — A toggles a flag (or opens a selector).
 * Level 2: selector options — A picks one exclusively.
 * B backs out one level. All choices live in FlagEditorModel, so returning to
 * the category list keeps everything; the engine still canonicalizes + runs the
 * same logic checks as the website / classic editor.
 */
class FF4FlagEditorActivity : FF4Activity() {

    private lateinit var engine: SeedEngine
    private lateinit var menu: FF4Menu
    private lateinit var flagsText: TextView
    private lateinit var validityText: TextView
    private lateinit var hintText: TextView

    private var sections: List<UiSection> = emptyList()
    private var model: FlagEditorModel? = null
    private var engineReady = false

    private var mode = MODE_CATEGORIES
    private var currentSection: UiSection? = null
    private var selectorControl: UiControl? = null

    private var actions: List<Action> = emptyList()
    private var rowInfo: List<RowInfo> = emptyList()

    private var syncToken = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private var syncRunnable: Runnable? = null

    private sealed class Action {
        object OpenSection : Action()
        class Toggle(val flag: String) : Action()
        class OpenSelector(val control: UiControl) : Action()
        class ChooseOption(val control: UiControl, val optionFlag: String) : Action()
    }

    private data class RowInfo(val title: String, val flag: String?, val description: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ff4_editor)

        menu = findViewById(R.id.ff4EditorMenu)
        flagsText = findViewById(R.id.ff4EditorFlags)
        validityText = findViewById(R.id.ff4EditorValidity)
        hintText = findViewById(R.id.ff4EditorHint)

        FF4Theme.applyText(this, flagsText, FF4Theme.textSmallPx(this))
        FF4Theme.applyText(this, validityText, FF4Theme.textSmallPx(this))
        FF4Theme.applyText(this, hintText, FF4Theme.textSmallPx(this))

        menu.onSelectionChanged = { i -> updateHintForSelection(i) }

        engine = SeedEngine(this) {}
        engine.ensureExtracted(
            onDone = { engineReady = true; startEditor() },
            onError = { hintText.text = "Engine unpack failed: $it" }
        )
        hintText.text = "Starting engine…"
    }

    // ------------------------------------------------------------------ setup

    private fun startEditor() {
        try {
            val raw = assets.open("spec/ui_spec.json").use { it.readBytes().toString(Charsets.UTF_8) }
            sections = UiSpec.parse(raw)
        } catch (e: Exception) {
            hintText.text = "Spec load failed: ${e.message}"
            return
        }
        val m = FlagEditorModel(sections)
        m.onModelChanged = { scheduleSync() }
        m.onEngineResult = { updateStatus() }
        model = m

        val initial = intent.getStringExtra("flags") ?: FlagSession.flags()
        if (initial.isBlank()) {
            m.addAll(emptyList())
        } else {
            engine.flagList(initial) { json ->
                runOnUiThread {
                    try {
                        val o = JSONObject(json)
                        val list = mutableListOf<String>()
                        val arr = o.optJSONArray("list")
                        if (arr != null) for (i in 0 until arr.length()) list.add(arr.getString(i))
                        m.addAll(list)
                    } catch (e: Exception) {
                        hintText.text = "Could not load flags: ${e.message}"
                    }
                }
            }
        }
        showCategories()
    }

    // ------------------------------------------------------------- engine sync

    private fun scheduleSync() {
        syncRunnable?.let { uiHandler.removeCallbacks(it) }
        syncRunnable = Runnable { syncToEngine() }
        uiHandler.postDelayed(syncRunnable!!, 300)
    }

    private fun syncToEngine() {
        val m = model ?: return
        val token = ++syncToken
        engine.flagsFromList(m.rebuildFlagList()) { json ->
            runOnUiThread {
                if (token != syncToken) return@runOnUiThread
                try {
                    val o = JSONObject(json)
                    if (o.optString("status") != "ok") {
                        validityText.text = "✗ " + o.optString("error")
                        validityText.setTextColor(FF4Theme.PINK)
                        return@runOnUiThread
                    }
                    val log = buildList {
                        val arr = o.optJSONArray("log")
                        if (arr != null) for (i in 0 until arr.length()) {
                            val e = arr.getJSONObject(i)
                            add(e.optString("severity") to e.optString("message"))
                        }
                    }
                    m.applyEngineResult(
                        canonical = o.optString("flags"),
                        binary = o.optString("binary_flags"),
                        log = log,
                        valid = o.optBoolean("valid", false),
                    )
                } catch (e: Exception) {
                    validityText.text = "✗ sync error: ${e.message}"
                    validityText.setTextColor(FF4Theme.PINK)
                }
            }
        }
    }

    private fun updateStatus() {
        val m = model ?: return
        FlagSession.setFlags(m.canonicalFlags)
        flagsText.text = m.canonicalFlags
        if (m.valid) {
            validityText.text = "Flags OK"
            validityText.setTextColor(FF4Theme.GREEN)
        } else {
            val errors = m.log.filter { it.first == "error" }
            validityText.text = "${errors.size} logical error(s)" +
                (errors.firstOrNull()?.let { " — ${it.second}" } ?: "")
            validityText.setTextColor(FF4Theme.PINK)
        }
    }

    // -------------------------------------------------------------- level 0

    private fun showCategories() {
        mode = MODE_CATEGORIES
        currentSection = null
        selectorControl = null
        val items = sections.map { s -> FF4Menu.Item(s.title, "${enabledCount(s)} on") }
        actions = sections.map { Action.OpenSection }
        rowInfo = sections.map { RowInfo(it.title, null, null) }
        menu.submit(items, rememberedSelection("cats")) { i ->
            rememberSelection("cats", i)      // remember the category we entered
            openSection(i)
        }
        menu.requestFocus()
        updateHintForSelection(menu.selected)
    }

    private fun enabledCount(section: UiSection): Int {
        val m = model ?: return 0
        var n = 0
        fun walk(controls: List<UiControl>) {
            for (c in controls) {
                if (!c.isContainer && !c.isSeparator && !c.isSelect && m.isEnabled(c.flag)) n++
                if (c.subcontrols.isNotEmpty()) walk(c.subcontrols)
            }
        }
        walk(section.controls)
        return n
    }

    private fun openSection(index: Int) {
        currentSection = sections.getOrNull(index) ?: return
        showSection()
    }

    // -------------------------------------------------------------- level 1

    private fun showSection() {
        val section = currentSection ?: return
        mode = MODE_SECTION
        selectorControl = null
        val (items, acts, infos) = buildSection(section)
        actions = acts
        rowInfo = infos
        // Keyed per section, so backing out of a selector returns to the row
        // that opened it (and never confuses a row index with a category index).
        val key = "section:" + section.title
        menu.submit(items, rememberedSelection(key)) { i ->
            rememberSelection(key, i)
            onSectionAction(i)
        }
        menu.requestFocus()
        updateHintForSelection(menu.selected)
    }

    private fun buildSection(section: UiSection): Triple<List<FF4Menu.Item>, List<Action>, List<RowInfo>> {
        val m = model ?: return Triple(emptyList(), emptyList(), emptyList())
        val items = mutableListOf<FF4Menu.Item>()
        val acts = mutableListOf<Action>()
        val infos = mutableListOf<RowInfo>()

        fun walk(controls: List<UiControl>, indent: Int) {
            for (c in controls) {
                if (c.isSeparator) continue
                if (c.isSelect) {
                    val current = c.subcontrols.firstOrNull { m.isEnabled(it.flag) }
                    items.add(FF4Menu.Item(c.title, current?.title ?: "none", indent = indent))
                    acts.add(Action.OpenSelector(c))
                    infos.add(RowInfo(c.title, null, c.description))
                    // The chosen mode carries its own options (e.g. Standard →
                    // "Restrict item quality" Tmaxtier:*, "Unrestrict item
                    // quality", "Sparse distribution"). Walk into them so they
                    // are visible and editable, exactly like the website.
                    if (current != null) walk(current.subcontrols, indent + 1)
                    continue
                }
                val on = m.isEnabled(c.flag)
                items.add(
                    FF4Menu.Item(
                        c.title,
                        if (on) "ON" else "OFF",
                        indent = indent,
                    )
                )
                acts.add(Action.Toggle(c.flag))
                infos.add(RowInfo(c.title, c.flag, c.description))
                if (on && c.subcontrols.isNotEmpty()) walk(c.subcontrols, indent + 1)
            }
        }
        walk(section.controls, 0)
        return Triple(items, acts, infos)
    }

    private fun onSectionAction(index: Int) {
        when (val action = actions.getOrNull(index) ?: return) {
            is Action.Toggle -> {
                model?.toggle(action.flag)
                showSection()          // selection already remembered above
            }
            is Action.OpenSelector -> showSelector(action.control)
            else -> {}
        }
    }

    // -------------------------------------------------------------- level 2

    private fun showSelector(control: UiControl) {
        mode = MODE_SELECTOR
        selectorControl = control
        val m = model ?: return
        val items = mutableListOf<FF4Menu.Item>()
        val acts = mutableListOf<Action>()
        val infos = mutableListOf<RowInfo>()
        if (!control.important) {
            items.add(FF4Menu.Item("(none)", if (control.subcontrols.none { m.isEnabled(it.flag) }) "ON" else ""))
            acts.add(Action.ChooseOption(control, ""))
            infos.add(RowInfo("(none)", null, null))
        }
        for (o in control.subcontrols) {
            items.add(FF4Menu.Item(o.title, if (m.isEnabled(o.flag)) "ON" else "", indent = 0))
            acts.add(Action.ChooseOption(control, o.flag))
            infos.add(RowInfo(o.title, o.flag, o.description))
        }
        actions = acts
        rowInfo = infos
        val key = "selector:" + control.flag
        menu.submit(items, rememberedSelection(key)) { i ->
            rememberSelection(key, i)
            onSelectorAction(i)
        }
        menu.requestFocus()
        updateHintForSelection(menu.selected)
    }

    private fun onSelectorAction(index: Int) {
        val action = actions.getOrNull(index) ?: return
        if (action is Action.ChooseOption) {
            val optionFlags = action.control.subcontrols.map { it.flag }
            val chosen = action.optionFlag.ifEmpty { "__none__" }
            model?.chooseExclusive(optionFlags, chosen)
        }
        showSection()
    }

    // ------------------------------------------------------------------ hints

    private fun updateHintForSelection(index: Int) {
        val info = rowInfo.getOrNull(index)
        val nav = when (mode) {
            MODE_CATEGORIES -> "A Open    B Exit"
            MODE_SELECTOR -> "A Choose    B Back"
            else -> "A Toggle    B Back"
        }
        if (info == null) {
            hintText.text = nav
            return
        }
        val desc = info.description?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString() }
        hintText.text = buildString {
            append(info.title)
            if (!info.flag.isNullOrBlank()) append("  [").append(info.flag).append("]")
            if (!desc.isNullOrBlank()) append("\n").append(desc)
            append("\n").append(nav)
        }
    }

    // ------------------------------------------------------------------- back

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            when (mode) {
                MODE_SELECTOR -> showSection()
                MODE_SECTION -> showCategories()
                else -> finish()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val MODE_CATEGORIES = 0
        private const val MODE_SECTION = 1
        private const val MODE_SELECTOR = 2
    }
}