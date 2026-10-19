package com.freeenterprise.app

import android.app.AlertDialog
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Native replica of the website's flag editor, driven by the site's own
 * compiled spec (assets/spec/ui_spec.json) and the on-device engine for
 * canonicalization + logic checks (identical algorithm to the site's JS).
 */
class FlagEditorActivity : AppCompatActivity() {

    private lateinit var editorRoot: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var flagsSummary: TextView
    private lateinit var binarySummary: TextView
    private lateinit var validityStatus: TextView
    private lateinit var logicList: TextView
    private lateinit var seedInput: EditText
    private lateinit var romStatus: TextView
    private lateinit var engine: SeedEngine

    private var sections: List<UiSection> = emptyList()
    private var model: FlagEditorModel? = null
    private var engineReady = false
    private var syncToken = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private var syncRunnable: Runnable? = null

    private val exportSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        SeedFlow.onExportResult(this, uri)
    }
    private val romPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleRomPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        editorRoot = findViewById(R.id.editorRoot)
        scrollView = findViewById(R.id.editorScroll)
        flagsSummary = findViewById(R.id.flagsSummary)
        binarySummary = findViewById(R.id.binarySummary)
        validityStatus = findViewById(R.id.validityStatus)
        logicList = findViewById(R.id.logicList)
        seedInput = findViewById(R.id.editorSeedInput)
        intent.getStringExtra("seed")?.let { seedInput.setText(it) }
        romStatus = findViewById(R.id.editorRomStatus)

        findViewById<Button>(R.id.btnEditorDone).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnEditorSettings).setOnClickListener {
            EditorSettingsDialog.show(this) {
                applyColors()
                model?.let { renderAll() }
            }
        }
        findViewById<Button>(R.id.btnEditorGenerate).setOnClickListener { onGenerate() }
        findViewById<Button>(R.id.btnEditorReset).setOnClickListener {
            model?.clear()
            Toast.makeText(this, "Cleared — defaults apply.", Toast.LENGTH_SHORT).show()
        }

        engine = SeedEngine(this) { resultJson -> onGenerationResult(resultJson) }
        engine.ensureExtracted(
            onDone = {
                engineReady = true
                romStatus.text = "v${BuildConfig.VERSION_NAME} · Engine ready. " + baseRomStatus()
                startEditor()
            },
            onError = { msg ->
                engineReady = false
                romStatus.text = "v${BuildConfig.VERSION_NAME} · Engine unpack failed: $msg"
            }
        )
        romStatus.text = "v${BuildConfig.VERSION_NAME} · Starting engine… " + baseRomStatus()
        applyColors()
    }

    private fun applyColors() {
        val bg = EditorPrefs.background(this)
        findViewById<View>(R.id.editorRootPanel).setBackgroundColor(bg)
        scrollView.setBackgroundColor(bg)
        editorRoot.setBackgroundColor(bg)
        findViewById<View>(R.id.editorSummaryPanel).setBackgroundColor(EditorPrefs.panel(this))
        flagsSummary.setTextColor(EditorPrefs.saneTextColor(this))
        binarySummary.setTextColor(EditorPrefs.secondaryText(this))
    }

    private fun baseRomStatus(): String =
        if (RomStore.hasBaseRom(this)) "Base ROM loaded." else "Tap ADD ROM (in main screen)."
    private fun startEditor() {
        try {
            val raw = AppContent.read(this, "spec/ui_spec.json")
            sections = UiSpec.parse(raw)
        } catch (e: Exception) {
            romStatus.text = "Spec load failed: ${e.message}"
            return
        }

        val m = FlagEditorModel(sections)
        m.onModelChanged = { scheduleEngineSync() }
        m.onEngineResult = { renderAll() }
        model = m

        val initialFlags = intent.getStringExtra("flags") ?: ""
        if (initialFlags.isBlank()) {
            m.addAll(emptyList())   // triggers sync → canonical defaults
        } else {
            engine.flagList(initialFlags) { json ->
                runOnUiThread {
                    try {
                        val o = JSONObject(json)
                        val list = mutableListOf<String>()
                        val arr = o.optJSONArray("list")
                        if (arr != null) for (i in 0 until arr.length()) list.add(arr.getString(i))
                        m.addAll(list)
                    } catch (e: Exception) {
                        flagsSummary.text = "Could not load flag string: ${e.message}"
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ model sync

    private fun scheduleEngineSync() {
        syncRunnable?.let { uiHandler.removeCallbacks(it) }
        syncRunnable = Runnable { syncToEngine() }
        uiHandler.postDelayed(syncRunnable!!, 300)
    }

    private fun syncToEngine() {
        val m = model ?: return
        val token = ++syncToken
        val flags = m.rebuildFlagList()
        engine.flagsFromList(flags) { json ->
            runOnUiThread {
                if (token != syncToken) return@runOnUiThread
                try {
                    val o = JSONObject(json)
                    if (o.optString("status") != "ok") {
                        val err = o.optString("error")
                        val trace = o.optString("trace")
                        validityStatus.setTextColor(EditorPrefs.dangerText())
                        validityStatus.text = "✗ $err" + if (trace.isNotBlank()) "\n" + trace.takeLast(300) else ""
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
                    validityStatus.text = "✗ sync error: ${e.message}"
                }
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun renderAll() {
        val m = model ?: return
        val saved = scrollView.scrollY
        editorRoot.removeAllViews()
        val txt = EditorPrefs.saneTextColor(this)
        val accent = EditorPrefs.accentColor(this)
        val secondary = EditorPrefs.secondaryText(this)
        val scale = EditorPrefs.fontSize(this)

        for (section in sections) {
            val header = TextView(this).apply {
                text = section.title
                setTextColor(txt)
                setTypeface(null, Typeface.BOLD)
                textSize = 14f * scale
                setPadding(dp(8), dp(12), dp(8), dp(2))
            }
            editorRoot.addView(header)
            buildControls(section.controls, editorRoot, 0)
        }

        flagsSummary.text = m.canonicalFlags
        binarySummary.text = m.binaryFlags
        if (m.valid) {
            validityStatus.text = "✓ flags OK"
            validityStatus.setTextColor(accent)
            logicList.text = ""
        } else {
            validityStatus.text = "✗ ${m.log.count { it.first == "error" }} logical error(s)"
            validityStatus.setTextColor(EditorPrefs.dangerText())
            logicList.text = m.log.joinToString("\n") { "• ${it.second}" }.take(400)
        }
        scrollView.post { scrollView.scrollTo(0, saved) }
    }

    private fun buildControls(controls: List<UiControl>, parent: LinearLayout, depth: Int) {
        for (c in controls) {
            if (c.isSeparator) {
                val div = android.view.View(this).apply {
                    setBackgroundColor(0xFFCCCCCC.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    )
                }
                parent.addView(div)
                continue
            }
            if (c.isSelect) {
                parent.addView(buildSelectorRow(c, depth))
            } else {
                parent.addView(buildToggleRow(c, depth, parent))
            }
        }
    }

    private fun buildToggleRow(c: UiControl, depth: Int, parent: LinearLayout): LinearLayout {
        val m = model ?: return LinearLayout(this)
        val selected = m.isEnabled(c.flag)
        val txt = EditorPrefs.saneTextColor(this)
        val accent = EditorPrefs.accentColor(this)
        val secondary = EditorPrefs.secondaryText(this)
        val scale = EditorPrefs.fontSize(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { m.toggle(c.flag) }
        }

        val icon = TextView(this).apply {
            text = if (selected && !c.nullFlag) "✓" else if (c.isContainer) "…" else "·"
            setTextColor(if (selected && !c.nullFlag) accent else secondary)
            minWidth = dp(22)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        row.addView(icon)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(this).apply {
            text = c.title.ifBlank { c.flag }
            textSize = 14f * scale
            setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (c.hard) EditorPrefs.dangerText() else txt)
        }
        col.addView(title)
        if (!c.isContainer) {
            val name = TextView(this).apply {
                text = c.flag
                textSize = 10f * scale
                setTextColor(secondary)
            }
            col.addView(name)
        }
        row.addView(col)

        if (selected) {
            if (c.description != null && c.description.isNotBlank()) {
                val desc = TextView(this).apply {
                    val plain = Html.fromHtml(c.description, Html.FROM_HTML_MODE_COMPACT).toString()
                    text = plain
                    textSize = 11f * scale
                    setTextColor(secondary)
                    setPadding(dp(26), dp(2), dp(6), dp(2))
                }
                parent.addView(desc)
            }
            if (c.subcontrols.isNotEmpty()) {
                val sub = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                }
                parent.addView(sub)
                buildControls(c.subcontrols, sub, depth + 1)
            }
        }
        return row
    }

    private fun buildSelectorRow(c: UiControl, depth: Int): LinearLayout {
        val m = model ?: return LinearLayout(this)
        val options = c.subcontrols
        val current = options.firstOrNull { m.isEnabled(it.flag) }
        val txt = EditorPrefs.saneTextColor(this)
        val accent = EditorPrefs.accentColor(this)
        val secondary = EditorPrefs.secondaryText(this)
        val scale = EditorPrefs.fontSize(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { showSelectorDialog(c) }
        }
        val icon = TextView(this).apply {
            text = "≡"
            setTextColor(accent)
            minWidth = dp(22)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        row.addView(icon)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = c.title
            textSize = 14f * scale
            setTypeface(null, Typeface.BOLD)
            setTextColor(txt)
        })
        col.addView(TextView(this).apply {
            text = current?.title ?: "(none)"
            textSize = 11f * scale
            setTextColor(if (current != null) accent else secondary)
        })
        row.addView(col)
        return row
    }

    private fun showSelectorDialog(c: UiControl) {
        val m = model ?: return
        val options = c.subcontrols
        val titles = mutableListOf<String>()
        val flags = mutableListOf<String>()
        if (!c.important) {
            titles.add("— none —")
            flags.add("")
        }
        for (o in options) {
            titles.add(o.title)
            flags.add(o.flag)
        }
        val checked = options.indexOfFirst { m.isEnabled(it.flag) }.let { if (it < 0) 0 else it + (if (c.important) 0 else 1) }

        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setSingleChoiceItems(titles.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val chosen = flags[which]
                if (chosen.isEmpty()) {
                    m.chooseExclusive(options.map { it.flag }, "__none__")
                } else {
                    m.chooseExclusive(options.map { it.flag }, chosen)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ generate

    private fun onGenerate() {
        val m = model ?: return
        if (!m.valid) {
            Toast.makeText(this, "Fix the logical errors before generating.", Toast.LENGTH_LONG).show()
            return
        }
        val flags = m.canonicalFlags.ifBlank { m.rebuildFlagList().joinToString(" ") }
        SeedFlow.generate(
            activity = this,
            engine = engine,
            flags = flags,
            seed = seedInput.text.toString().trim(),
            metaconfigJson = "{}",
            exportLauncher = exportSaver,
            onNeedRom = { romPicker.launch(arrayOf("application/octet-stream", "application/x-rom", "*/*")) },
        )
    }

    private fun onGenerationResult(resultJson: String) {
        // handled by SeedFlow.generate's callback path; nothing to do here
    }

    private fun handleRomPicked(uri: Uri) {
        val bytes = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
        if (bytes == null) {
            Toast.makeText(this, "Could not read the selected file.", Toast.LENGTH_LONG).show()
            return
        }
        val error = RomStore.validate(bytes)
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        RomStore.baseRomFile(this).writeBytes(bytes)
        romStatus.text = "v${BuildConfig.VERSION_NAME} · Engine ready. Base ROM loaded."
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}