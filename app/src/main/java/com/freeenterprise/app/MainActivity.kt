package com.freeenterprise.app

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var romStatus: TextView
    private lateinit var engine: SeedEngine
    private lateinit var categorySpinner: Spinner
    private lateinit var presetList: LinearLayout
    private lateinit var flagsInput: EditText
    private lateinit var validationText: TextView
    private lateinit var seedInput: EditText

    private val categories = mutableListOf<String>()
    private val presetsByCategory = LinkedHashMap<String, List<Presets.Preset>>()

    private var engineReady = false
    private var validationVersion = 0L
    private var lastValidFlags: String? = null
    private var generating = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var validateRunnable: Runnable? = null

    private val romPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleRomPicked(uri)
    }

    private val exportSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && pendingExport != null) {
            writeUri(uri, pendingExport!!)
            pendingExport = null
            Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
        }
    }
    private var pendingExport: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        romStatus = findViewById(R.id.romStatus)
        categorySpinner = findViewById(R.id.categorySpinner)
        presetList = findViewById(R.id.presetList)
        flagsInput = findViewById(R.id.flagsInput)
        validationText = findViewById(R.id.validationText)
        seedInput = findViewById(R.id.seedInput)

        findViewById<Button>(R.id.btnAddRom).setOnClickListener { pickRom() }
        findViewById<Button>(R.id.btnEditor).setOnClickListener {
            startActivity(
                Intent(this, FlagEditorActivity::class.java)
                    .putExtra("flags", flagsInput.text.toString().trim())
            )
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            EditorSettingsDialog.show(this) {
                applyMainColors()
                renderPresets(categorySpinner.selectedItem as? String)
            }
        }
        findViewById<Button>(R.id.btnSeeds).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        findViewById<Button>(R.id.btnCopyFlags).setOnClickListener { copyFlags() }
        findViewById<Button>(R.id.btnGenerate).setOnClickListener { onGenerate() }

        loadPresets()
        setupCategorySpinner()
        flagsInput.addTextChangedListener(flagsWatcher)

        engine = SeedEngine(this) { resultJson -> onGenerationResult(resultJson) }
        engine.ensureExtracted(
            onDone = {
                engineReady = true
                updateRomStatus()
                validateNow()
            },
            onError = { msg ->
                engineReady = false
                romStatus.text = "Engine unpack failed: $msg"
            }
        )
        updateRomStatus()
        applyMainColors()
    }

    private fun applyMainColors() {
        findViewById<View>(R.id.mainRoot).setBackgroundColor(EditorPrefs.background(this))
        listOf(R.id.labelPresets, R.id.labelFlags, R.id.labelSeed).forEach { id ->
            findViewById<TextView>(id).setTextColor(EditorPrefs.saneTextColor(this))
        }
        flagsInput.setTextColor(EditorPrefs.saneTextColor(this))
        seedInput.setTextColor(EditorPrefs.saneTextColor(this))
        validationText.setTextColor(EditorPrefs.textColor(this))
    }

    // ------------------------------------------------------------- presets

    private fun loadPresets() {
        // Shared with the FF4 UI (and applies the doors/entrances filter there).
        val loaded = Presets.load(this)
        categories.clear()
        presetsByCategory.clear()
        for (cat in loaded) {
            categories.add(cat.name)
            presetsByCategory[cat.name] = cat.presets
        }
        if (categories.isEmpty()) {
            romStatus.text = "Presets failed to load."
        }
    }

    private fun setupCategorySpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapter
        categorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderPresets(categories.getOrNull(position))
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        if (categories.isNotEmpty()) renderPresets(categories[0])
    }

    private fun renderPresets(category: String?) {
        presetList.removeAllViews()
        val list = presetsByCategory[category] ?: return
        for (preset in list) {
            val row = layoutInflater.inflate(R.layout.item_preset, presetList, false)
            row.findViewById<TextView>(R.id.presetName).text = preset.name
            row.findViewById<TextView>(R.id.presetName).setTextColor(EditorPrefs.saneTextColor(this))
            val desc = row.findViewById<TextView>(R.id.presetDescription)
            desc.setTextColor(EditorPrefs.secondaryText(this))
            if (preset.description.isBlank()) desc.visibility = View.GONE
            else desc.text = android.text.Html.fromHtml(preset.description, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
            row.setOnClickListener {
                flagsInput.setText(preset.flags)
                Toast.makeText(this, "Preset \"${preset.name}\" applied.", Toast.LENGTH_SHORT).show()
            }
            presetList.addView(row)
        }
    }

    // ------------------------------------------------------------- flags

    private val flagsWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            validateRunnable?.let { uiHandler.removeCallbacks(it) }
            validateRunnable = Runnable { validateNow() }
            uiHandler.postDelayed(validateRunnable!!, 350)
        }
    }

    private fun validateNow() {
        if (!engineReady) {
            validationText.text = "Engine starting…"
            return
        }
        val flags = flagsInput.text.toString().trim()
        val version = ++validationVersion
        validationText.text = "Validating…"
        engine.validateFlags(flags) { json ->
            if (version != validationVersion) return@validateFlags
            try {
                val o = JSONObject(json)
                if (o.optString("status") == "ok") {
                    lastValidFlags = o.optString("flags")
                    validationText.text = "✓ " + lastValidFlags
                } else {
                    lastValidFlags = null
                    validationText.text = "✗ " + o.optString("error")
                }
            } catch (e: Exception) {
                validationText.text = "✗ validation error"
            }
        }
    }

    private fun copyFlags() {
        val text = lastValidFlags ?: flagsInput.text.toString().trim()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("FE flags", text))
        Toast.makeText(this, "Flag string copied.", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------- ROM

    private fun pickRom() {
        romPicker.launch(arrayOf("application/octet-stream", "application/x-rom", "*/*"))
    }

    private fun handleRomPicked(uri: Uri) {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            Toast.makeText(this, "Could not read the selected file.", Toast.LENGTH_LONG).show()
            return
        }
        val error = RomStore.validate(bytes)
        if (error != null) {
            AlertDialog.Builder(this)
                .setTitle("Invalid base ROM")
                .setMessage(error)
                .setPositiveButton("OK", null)
                .show()
            return
        }
        RomStore.baseRomFile(this).writeBytes(bytes)
        updateRomStatus()
        AlertDialog.Builder(this)
            .setTitle("Base ROM stored")
            .setMessage("FF2 (US) ROM accepted, stored on-device, never uploaded. " +
                "Configure flags and hit Generate!")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateRomStatus() {
        romStatus.text =
            "v${BuildConfig.VERSION_NAME}  ·  ROM ${if (RomStore.hasBaseRom(this)) "✓" else "—"}" +
            "  ·  Engine ${if (engineReady) "✓" else "…"}"
    }

    private fun writeUri(uri: Uri, file: File) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------- generate

    private fun onGenerate() {
        if (generating) {
            Toast.makeText(this, "Already generating…", Toast.LENGTH_SHORT).show()
            return
        }
        if (!RomStore.hasBaseRom(this)) {
            Toast.makeText(this, "Add your FF2 (US) ROM first.", Toast.LENGTH_LONG).show()
            pickRom()
            return
        }
        if (!engineReady) {
            Toast.makeText(this, "Engine still starting — wait a moment.", Toast.LENGTH_LONG).show()
            return
        }
        val flags = flagsInput.text.toString().trim()
        val seed = seedInput.text.toString().trim()
        generating = true
        val dialog = ProgressDialog(this).apply {
            setTitle("Generating seed")
            setMessage("Running the FE randomizer on-device…\n(first run takes up to ~a minute)")
            setCancelable(false)
            setIndeterminate(true)
            show()
        }
        pendingDialog = dialog
        engine.cacheDir.mkdirs()
        engine.romsDir.mkdirs()
        engine.generate(flags, seed, "{}")
    }

    private var pendingDialog: ProgressDialog? = null

    private fun onGenerationResult(resultJson: String) {
        pendingDialog?.dismiss()
        pendingDialog = null
        generating = false

        val result = try { JSONObject(resultJson) } catch (e: Exception) { null }
        if (result == null || result.optString("status") != "ok") {
            val err = result?.optString("error") ?: resultJson
            AlertDialog.Builder(this)
                .setTitle("Generation failed")
                .setMessage(err)
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val seed = result.optString("seed")
        val path = result.optString("path")
        val flags = result.optString("flags")
        val version = result.optString("version")
        val verification = buildList {
            val v = result.optJSONArray("verification")
            if (v != null) for (i in 0 until v.length()) add(v.getString(i))
        }
        val seconds = result.optDouble("seconds", 0.0)
        val romFile = File(path)

        val record = SeedRecord(
            time = System.currentTimeMillis(),
            seed = seed,
            flags = flags,
            binaryFlags = result.optString("binary_flags"),
            verification = verification,
            filePath = path,
            fileName = result.optString("file_name"),
            version = version,
            seconds = seconds,
        )
        SeedLibrary.add(this, record)

        if (!romFile.exists()) {
            AlertDialog.Builder(this)
                .setTitle("Engine reported success but the ROM is missing")
                .setMessage("Looked for: $path")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val check = verification.joinToString(", ")
        val time = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(record.time))
        val retro = if (RetroArchLauncher.retroArchInstalled(this)) "Play (RetroArch)" else "Play (hand-off)"
        AlertDialog.Builder(this)
            .setTitle("Seed $seed ✓")
            .setMessage(
                "Version: $version\n" +
                "Seed:    $seed\n" +
                "Check:   $check\n" +
                "Time:    ${seconds}s  (generated $time)\n\n" +
                "Flags: $flags\n\n" +
                "ROM saved at: ${record.fileName}"
            )
            .setPositiveButton(retro) { _, _ -> RetroArchLauncher.launch(this, romFile) }
            .setNeutralButton("Save a copy…") { _, _ ->
                pendingExport = romFile
                exportSaver.launch(record.fileName)
            }
            .setNegativeButton("New seed", null)
            .show()
    }
}