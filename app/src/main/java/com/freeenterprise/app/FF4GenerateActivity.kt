package com.freeenterprise.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import com.freeenterprise.ff4.Ff4Rows
import com.freeenterprise.app.ff4ui.FF4Menu
import com.freeenterprise.app.ff4ui.FF4MenuActivity
import com.freeenterprise.app.ff4ui.FF4ProgressOverlay
import com.freeenterprise.app.ff4ui.GameIcons
import com.freeenterprise.app.ff4ui.FF4Theme
import org.json.JSONObject

/**
 * "Generate Seed": shows the current flagset (kept in FlagSession, so it
 * survives menu navigation), allows preset / flag / seed edits, then runs the
 * randomizer and drops the ROM + metadata into FF4_FE.
 */
class FF4GenerateActivity : FF4MenuActivity() {

    private lateinit var engine: SeedEngine
    private var engineReady = false
    private var generating = false
    private var syncToken = 0
    private var lastValid: Boolean? = null
    private var lastError: String? = null

    private var progressOverlay: FF4ProgressOverlay? = null
    private var progressFile: java.io.File? = null
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var progressStartedAt = 0L
    private val progressPoller = object : Runnable {
        override fun run() {
            val overlay = progressOverlay ?: return
            val file = progressFile
            val elapsed = (System.currentTimeMillis() - progressStartedAt) / 1000
            var pct = overlayPct
            var stage = overlayStage
            var detail = overlayDetail
            try {
                if (file != null && file.exists()) {
                    val parts = file.readText().split("\n")
                    if (parts.isNotEmpty()) {
                        parts.getOrNull(0)?.trim()?.toIntOrNull()?.let { pct = it }
                        parts.getOrNull(1)?.let { stage = it.trim() }
                        parts.getOrNull(2)?.let { detail = it.trim() }
                    }
                }
            } catch (_: Exception) {
            }
            overlayPct = pct; overlayStage = stage; overlayDetail = detail
            overlay.update(pct, stage, detail, elapsed)
            progressHandler.postDelayed(this, 200)
        }
    }
    private var overlayPct = 0
    private var overlayStage = ""
    private var overlayDetail = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = SeedEngine(this) {}
        engine.ensureExtracted(
            onDone = { engineReady = true; refresh() },
            onError = { setStatus("Engine unpack failed: $it", FF4Theme.PINK) }
        )
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        showMenu()
        checkFlags()
    }

    private fun currentFlags() = FlagSession.flags()
    private fun currentSeed() = FlagSession.seed()

    private fun showMenu() {
        val flags = currentFlags()
        val seed = currentSeed()
        val preset = FlagSession.presetName()
        setHint("A  Select      B  Back")
        submitMenu("generate", Ff4Rows.generate(
                presetName = preset,
                flagsSet = flags.isNotBlank(),
                seedName = seed,
                valid = lastValid != false,
            ).toMenuItems()) { i ->
            when (i) {
                0 -> startActivity(Intent(this, FF4PresetActivity::class.java))
                1 -> startActivity(
                    Intent(this, FF4FlagEditorActivity::class.java)
                        .putExtra("flags", currentFlags())
                )
                2 -> editSeed()
                3 -> generate()
                4 -> finish()
            }
        }
    }

    /** Set by the sanitiser so the status line can explain any translation. */
    private var flagNote: String? = null

    private fun checkFlags() {
        val raw = currentFlags()
        if (raw.isBlank()) {
            lastValid = true
            lastError = null
            setStatus("Flags: none yet — choose a preset or open the flag editor.", FF4Theme.YELLOW)
            return
        }
        if (!engineReady) {
            setStatus("Flags: $raw\n(engine starting…)", FF4Theme.WHITE)
            return
        }
        val token = ++syncToken

        // Presets and saved flagsets are often written against an older flag spec,
        // so translate/drop before validating rather than showing an error.
        engine.sanitizeFlags(raw) { sanitised ->
            runOnUiThread {
                if (token != syncToken) return@runOnUiThread
                try {
                    val o = JSONObject(sanitised)
                    if (o.optString("status") == "ok") {
                        val canonical = o.optString("flags")
                        if (canonical != raw) FlagSession.setFlags(canonical)
                        val renamed = o.optJSONArray("renamed")
                        val dropped = o.optJSONArray("dropped")
                        flagNote = when {
                            (renamed != null && renamed.length() > 0) || (dropped != null && dropped.length() > 0) -> {
                                val from = buildList { if (renamed != null) for (i in 0 until renamed.length()) add(renamed.getJSONObject(i).optString("from")) }
                                val gone = buildList { if (dropped != null) for (i in 0 until dropped.length()) add(dropped.getString(i)) }
                                buildString {
                                    if (from.isNotEmpty()) append("updated flags for this version: ").append(from.joinToString(", "))
                                    if (gone.isNotEmpty()) {
                                        if (from.isNotEmpty()) append("  ·  ")
                                        append("dropped (no longer exist): ").append(gone.joinToString(", "))
                                    }
                                }
                            }
                            else -> null
                        }
                    }
                } catch (_: Exception) {
                }
                validateSanitised()
            }
        }
    }

    private fun validateSanitised() {
        val flags = currentFlags()
        if (flags.isBlank()) return
        val token = ++syncToken
        engine.checkFlags(flags) { json ->
            runOnUiThread {
                if (token != syncToken) return@runOnUiThread
                try {
                    val o = JSONObject(json)
                    if (o.optString("status") != "ok") {
                        lastValid = false
                        lastError = o.optString("error")
                        setStatus("Flags error: $lastError", FF4Theme.PINK)
                        return@runOnUiThread
                    }
                    lastValid = o.optBoolean("valid", false)
                    val canonical = o.optString("flags")
                    FlagSession.setFlags(canonical)
                    val log = o.optJSONArray("log")
                    val firstMsg = if (log != null && log.length() > 0) {
                        log.getJSONObject(0).optString("message")
                    } else null
                    val note = flagNote?.let { "\n$it" } ?: ""
                    if (lastValid == true) {
                        setStatus("Flags: $canonical$note", FF4Theme.WHITE)
                    } else {
                        setStatus("Flags: $canonical$note\n✗ $firstMsg", FF4Theme.PINK)
                    }
                    showMenu()
                } catch (e: Exception) {
                    setStatus("Flag check failed: ${e.message}", FF4Theme.PINK)
                }
            }
        }
    }

    /** Choose a saved ruleset and load it into the current flagset. */
    private fun showFlagsetPicker() {
        val sets = FlagsetLibrary.list(this)
        if (sets.isEmpty()) {
            setStatus("No saved flagsets yet.\nBuild flags, then use Save Flagset.", FF4Theme.YELLOW)
            showMenu()
            return
        }
        setStatus("Saved flagsets", FF4Theme.WHITE)
        setHint("A  Load      B  Back")
        submitMenu("flagsets", sets.map { FF4Menu.Item(it.name, "ruleset") }) { i ->
            val set = sets.getOrNull(i) ?: return@submitMenu
            FlagSession.setFlags(set.flags)
            FlagSession.setPresetName(set.name)
            FlagSession.setSeed("")
            setStatus("Loaded flagset \"${set.name}\". Pick a seed and Generate.", FF4Theme.GREEN)
            showMenu()
            checkFlags()
        }
    }

    private fun saveFlagset() {
        val flags = currentFlags()
        if (flags.isBlank()) return
        val suggested = FlagSession.presetName().ifBlank { "My flagset" }
        val input = EditText(this).apply {
            setText(suggested)
            setSelection(text.length)
            FF4Theme.applyText(this@FF4GenerateActivity, this)
        }
        AlertDialog.Builder(this)
            .setTitle("Save Flagset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().ifBlank { "My flagset" }
                FlagsetLibrary.save(this, name, flags)
                FlagSession.setPresetName(name)
                setStatus("Saved flagset \"$name\". It can be re-applied to any new seed.", FF4Theme.GREEN)
                showMenu()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editSeed() {
        val input = EditText(this).apply {
            setText(currentSeed())
            setSelection(text.length)
            FF4Theme.applyText(this@FF4GenerateActivity, this)
        }
        AlertDialog.Builder(this)
            .setTitle("Seed (10 letters/numbers, blank = random)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                FlagSession.setSeed(input.text.toString().trim().uppercase())
                showMenu()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generate() {
        if (generating) return
        if (!engineReady) {
            setStatus("Engine still starting…", FF4Theme.YELLOW)
            return
        }
        if (!RomStore.hasBaseRom(this)) {
            setStatus("Add your FF2 (US) base ROM first (main menu).", FF4Theme.PINK)
            return
        }
        if (lastValid == false) {
            setStatus("Fix the logical flag errors before generating.", FF4Theme.PINK)
            return
        }
        generating = true
        setStatus("Generating — this can take up to a minute…", FF4Theme.YELLOW)
        setHint("Please wait…")
        // Modal progress window: nothing is usable during generation, so we
        // block the menus and show real engine progress instead.
        val overlay = FF4ProgressOverlay(this)
        overlay.title = "GENERATING SEED"
        progressOverlay = overlay
        progressFile = java.io.File(filesDir, "generation_progress.txt").also {
            try { it.delete() } catch (_: Exception) {}
        }
        overlayPct = 0; overlayStage = "Starting engine"; overlayDetail = ""
        addContentView(
            overlay,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.show()
        progressStartedAt = System.currentTimeMillis()
        progressHandler.post(progressPoller)
        // Generate into app-private storage (always writable), then copy into
        // FF4_FE with Java I/O — more reliable than having Python write to
        // shared storage.
        engine.generate(
            currentFlags(),
            currentSeed(),
            "{}",
            progressPath = progressFile?.absolutePath,
        ) { json ->
            runOnUiThread { onGenerateResult(json) }
        }
    }

    private fun onGenerateResult(json: String) {
        generating = false
        progressHandler.removeCallbacks(progressPoller)
        progressOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        progressOverlay = null
        progressFile?.delete()
        progressFile = null
        try {
            val o = JSONObject(json)
            if (o.optString("status") != "ok") {
                setStatus("Generation failed: ${o.optString("error")}", FF4Theme.PINK)
                showMenu()
                return
            }
            val seed = o.optString("seed")
            val sourcePath = o.optString("path")
            val verification = mutableListOf<String>()
            val v = o.optJSONArray("verification")
            if (v != null) for (i in 0 until v.length()) verification.add(v.getString(i))
            val flags = o.optString("flags")
            val source = java.io.File(sourcePath)
            android.util.Log.i("FEGen", "generated seed=$seed source=$sourcePath exists=${source.exists()}")

            if (!source.exists()) {
                setStatus("Engine finished but the ROM file is missing:\n$sourcePath", FF4Theme.PINK)
                showMenu()
                return
            }

            val libraryFile = try {
                RomLibrary.save(
                    this,
                    source,
                    SeedMeta(
                        name = seed,
                        seed = seed,
                        flags = flags,
                        binaryFlags = o.optString("binary_flags"),
                        verification = verification,
                        version = o.optString("version"),
                        seconds = o.optDouble("seconds", 0.0),
                        created = System.currentTimeMillis(),
                        // Which checks hold key items, parsed from the spoiler we
                        // already receive. Only the location is ever shown; no
                        // separate spoiler file is required for the tracker.
                        keyItems = Spoiler.keyItemLocations(o.optString("private_spoiler")),
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("FEGen", "copy to library failed", e)
                setStatus("Could not save into the library folder:\n${e.message}", FF4Theme.PINK)
                showMenu()
                return
            }

            val visible = RomLibrary.listRoms(this).any { it.absolutePath == libraryFile.absolutePath }
            android.util.Log.i(
                "FEGen",
                "library=${libraryFile.absolutePath} visible=$visible folder=${RomLibrary.folder(this).absolutePath}"
            )
            if (!visible) {
                setStatus("Saved, but the library cannot see it yet:\n${libraryFile.absolutePath}", FF4Theme.PINK)
                showMenu()
                return
            }

            FlagSession.setSeed(seed)
            setStatus(
                "Generated seed $seed\nCheck: ${verification.joinToString("/")}\n" +
                    "Saved to ${RomLibrary.folder(this).name}/${libraryFile.name}",
                FF4Theme.GREEN
            )
            setHint("A  Select      B  Back")
            submitMenu("genresult", Ff4Rows.completed().toMenuItems()) { i ->
                when (i) {
                    0 -> startActivity(Intent(this, FF4PlayActivity::class.java))
                    1 -> { FlagSession.clear(); showMenu() }
                    2 -> finish()
                }
            }
        } catch (e: Exception) {
            setStatus("Generation result error: ${e.message}", FF4Theme.PINK)
        }
    }

    override fun onBackRequested(): Boolean {
        if (generating) return true   // generation is modal
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacks(progressPoller)
        progressOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        progressOverlay = null
    }
}