package com.freeenterprise.app

import android.app.AlertDialog
import android.content.Intent
import org.json.JSONObject
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import com.freeenterprise.app.ff4ui.FF4Dialog
import com.freeenterprise.app.ff4ui.FF4ProgressOverlay
import com.freeenterprise.app.ff4ui.FF4Menu
import com.freeenterprise.app.ff4ui.FF4MenuActivity
import com.freeenterprise.app.ff4ui.FF4Theme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Play Free Enterprise" — the session library. Each ROM in FF4_FE is a session:
 * name, seed, checksum, and its battery save (.srm). Sessions can be played
 * (RetroArch hand-off for now), renamed, their save imported/exported/deleted,
 * or the whole session deleted.
 */
class FF4PlayActivity : FF4MenuActivity() {

    private lateinit var engine: SeedEngine
    private var engineReady = false
    private var regenerating = false
    private var regenOverlay: FF4ProgressOverlay? = null
    private var regenProgressFile: File? = null
    private var regenStartedAt = 0L
    private val regenHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val regenPoller = object : Runnable {
        override fun run() {
            val overlay = regenOverlay ?: return
            val file = regenProgressFile
            val elapsed = (System.currentTimeMillis() - regenStartedAt) / 1000
            var pct = 0; var stage = "Working"; var detail = ""
            try {
                if (file != null && file.exists()) {
                    val parts = file.readText().split("\n")
                    parts.getOrNull(0)?.trim()?.toIntOrNull()?.let { pct = it }
                    parts.getOrNull(1)?.let { stage = it.trim() }
                    parts.getOrNull(2)?.let { detail = it.trim() }
                }
            } catch (_: Exception) { }
            overlay.update(pct, stage, detail, elapsed)
            regenHandler.postDelayed(this, 200)
        }
    }

    private var mode = MODE_LIST
    private var entries: List<File> = emptyList()
    private var selected: File? = null

    private val srmImporter = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSave(uri)
    }
    private val srmExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) exportSave(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = SeedEngine(this) { }
        engine.ensureExtracted(
            onDone = { engineReady = true },
            onError = { engineReady = false },
        )
        showList()
    }

    override fun onResume() {
        super.onResume()
        if (mode == MODE_LIST) showList()
    }

    // ------------------------------------------------------------------ list

    private fun showList() {
        mode = MODE_LIST
        selected = null
        // Most recently played first, then newest.
        entries = RomLibrary.listRoms(this).sortedByDescending { rom ->
            val m = RomLibrary.readMeta(rom)
            val played = if (m.lastPlayed > 0) m.lastPlayed else m.created
            if (played > 0) played else rom.lastModified()
        }
        val where = if (RomLibrary.usingFallback(this)) "app storage (grant access for FF4_FE)" else "FF4_FE"
        if (entries.isEmpty()) {
            setStatus(
                "No seeds yet.\nLooking in: ${RomLibrary.folder(this).absolutePath}\n" +
                    "Generate one from the main menu.",
                FF4Theme.YELLOW
            )
            setHint("B  Back")
            submitMenu("list", listOf(
                    FF4Menu.Item("(no seeds)", enabled = false),
                    FF4Menu.Item("Back"),
                )) { finish() }
            return
        }
        setStatus("$where  ·  ${entries.size} session(s)")
        setHint("A  Select      B  Back")
        val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
        val items = entries.map { rom ->
            val m = RomLibrary.readMeta(rom)
            val save = RomLibrary.srmFile(rom)
            val saveText = if (save.exists()) "save ${save.length() / 1024}K" else "no save"
            val played = if (m.lastPlayed > 0) m.lastPlayed else m.created
            val playedText = if (played > 0) fmt.format(Date(played)) else "—"
            val check = if (m.verification.isEmpty()) "no check" else m.verification.joinToString("/")
            FF4Menu.Item(
                label = m.name.ifBlank { rom.nameWithoutExtension },
                value = "${rom.length() / 1024}K",
                subtitle = "seed ${m.seed.ifBlank { "?" }}  ·  $check  ·  $saveText  ·  last played $playedText",
            )
        }
        submitMenu("list", items) { i ->
            selected = entries.getOrNull(i)
            showActions()
        }
    }

    // --------------------------------------------------------------- actions

    private fun showActions() {
        val rom = selected ?: return
        mode = MODE_ACTIONS
        val m = RomLibrary.readMeta(rom)
        val save = RomLibrary.srmFile(rom)
        val progress = if (save.exists()) Ff4Save.summary(this, save) else null
        setStatus(
            "${m.name}\n${m.flags}".trim() +
                (if (progress != null) "\n$progress"
                 else if (save.exists()) "\nSave present (no readable progress yet)"
                 else "\nNo save file yet."),
            FF4Theme.WHITE
        )
        setHint("A  Select      B  Back")
        val romExists = rom.exists()
        submitMenu("actions", listOf(
                FF4Menu.Item("Play Seed"),
                FF4Menu.Item("Regenerate ROM", if (romExists) "same flags + seed" else "ROM missing!"),
                FF4Menu.Item("Manage Save", if (save.exists()) "present" else "none"),
                FF4Menu.Item("New seed with these flags", "same ruleset"),
                FF4Menu.Item("Save flags as Flagset"),
                FF4Menu.Item("Rename Seed"),
                FF4Menu.Item("Delete Seed"),
                FF4Menu.Item("Back"),
            )) { i ->
            when (i) {
                0 -> playSeed(rom)
                1 -> regenerate(rom)
                2 -> showSaveMenu()
                3 -> newSeedWithSameFlags(rom)
                4 -> saveFlagsAsFlagset(rom)
                5 -> rename(rom)
                6 -> confirmDelete(rom)
                7 -> showList()
            }
        }
    }

    private fun playSeed(rom: File) {
        mode = MODE_PLAY
        RomLibrary.touchLastPlayed(rom)   // record the session
        val m = RomLibrary.readMeta(rom)
        val save = RomLibrary.srmFile(rom)
        setStatus(
            "Play \"${m.name}\"\nseed ${m.seed}  ·  ${m.verification.joinToString("/")}\n" +
                (if (save.exists()) "Save: ${save.name} (${save.length()} bytes)" else "No save yet — a new one is created on play."),
            FF4Theme.WHITE
        )
        setHint("A  Select      B  Back")
        submitMenu("play", listOf(
                FF4Menu.Item("Play in-app", "Snes9x"),
                FF4Menu.Item("Open in RetroArch", "external"),
                FF4Menu.Item("Back"),
            )) { i ->
            when (i) {
                0 -> startActivity(
                    Intent(this, FF4PlayerActivity::class.java)
                        .putExtra("rom", rom.absolutePath)
                )
                1 -> RetroArchLauncher.launch(this, rom)
                2 -> showActions()
            }
        }
    }

    // ------------------------------------------------------------ save files

    private fun showSaveMenu() {
        val rom = selected ?: return
        mode = MODE_SAVE
        val save = RomLibrary.srmFile(rom)
        setStatus(
            if (save.exists()) "Save: ${save.name}\n${save.length()} bytes  ·  ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(save.lastModified()))}"
            else "No save file for this session yet.\nPlay it, then import the .srm from RetroArch.",
            FF4Theme.WHITE
        )
        setHint("A  Select      B  Back")
        val hasSave = save.exists()
        submitMenu("save", listOf(
                FF4Menu.Item("Import save (.srm)", if (hasSave) "replace" else ""),
                FF4Menu.Item("Export save (.srm)", enabled = hasSave),
                FF4Menu.Item("Delete save", enabled = hasSave),
                FF4Menu.Item("Back"),
            )) { i ->
            when (i) {
                0 -> srmImporter.launch(arrayOf("*/*"))
                1 -> srmExporter.launch(save.name)
                2 -> confirmDeleteSave(rom)
                3 -> showActions()
            }
        }
    }

    private fun importSave(uri: Uri) {
        val rom = selected ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                setStatus("Could not read that save file.", FF4Theme.PINK)
                return
            }
            RomLibrary.srmFile(rom).writeBytes(bytes)
            setStatus("Save imported (${bytes.size} bytes).", FF4Theme.GREEN)
        } catch (e: Exception) {
            setStatus("Import failed: ${e.message}", FF4Theme.PINK)
        }
        showSaveMenu()
    }

    private fun exportSave(uri: Uri) {
        val rom = selected ?: return
        try {
            val save = RomLibrary.srmFile(rom)
            contentResolver.openOutputStream(uri)?.use { out -> save.inputStream().use { it.copyTo(out) } }
            setStatus("Save exported.", FF4Theme.GREEN)
        } catch (e: Exception) {
            setStatus("Export failed: ${e.message}", FF4Theme.PINK)
        }
        showSaveMenu()
    }

    private fun confirmDeleteSave(rom: File) {
        FF4Dialog.confirm(
            this,
            "Delete save",
            "Delete the .srm save for this session?\nThe ROM stays.",
            yes = "Delete",
            no = "Cancel",
            onYes = {
                RomLibrary.deleteSave(rom)
                showSaveMenu()
            },
            onNo = { showSaveMenu() },
        )
    }

    // -------------------------------------------------------------- metadata

    /** Rolls the same ruleset into a brand new unique seed. */
    /**
     * Re-runs the engine with the session's own flags + seed (deterministic, so
     * the same ROM), replacing the file in place. Shows the modal progress
     * window, and cross-checks the checksum tiles against the stored record.
     */
    private fun regenerate(rom: File) {
        if (regenerating) {
            setStatus("Already regenerating…", FF4Theme.YELLOW)
            return
        }
        val meta = RomLibrary.readMeta(rom)
        if (meta.flags.isBlank()) {
            setStatus("This session has no stored flags to regenerate from.", FF4Theme.PINK)
            return
        }
        if (!engineReady) {
            setStatus("Engine still starting — try again in a moment.", FF4Theme.YELLOW)
            return
        }
        if (!RomStore.hasBaseRom(this)) {
            setStatus("Base ROM missing — re-import it from the main menu.", FF4Theme.PINK)
            return
        }
        regenerating = true
        setStatus("Regenerating…", FF4Theme.YELLOW)
        val overlay = FF4ProgressOverlay(this)
        regenOverlay = overlay
        regenProgressFile = File(filesDir, "regen_progress.txt").also {
            try { it.delete() } catch (_: Exception) { }
        }
        addContentView(
            overlay,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.show()
        overlay.update(0, "Regenerating ${meta.seed}", "same flags + seed", 0)
        regenStartedAt = System.currentTimeMillis()
        regenHandler.post(regenPoller)
        engine.cacheDir.mkdirs()
        engine.romsDir.mkdirs()
        engine.generate(
            meta.flags, meta.seed, "{}",
            progressPath = regenProgressFile?.absolutePath,
        ) { json ->
            runOnUiThread {
                regenHandler.removeCallbacks(regenPoller)
                regenOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
                regenOverlay = null
                regenProgressFile?.delete()
                regenProgressFile = null
                regenerating = false
                handleRegenResult(rom, meta, json)
            }
        }
    }

    private fun handleRegenResult(rom: File, old: SeedMeta, json: String) {
        try {
            val o = JSONObject(json)
            if (o.optString("status") != "ok") {
                setStatus("Regeneration failed: ${o.optString("error")}", FF4Theme.PINK)
                showActions()
                return
            }
            val path = o.optString("path")
            val fresh = File(path)
            if (!fresh.exists()) {
                setStatus("Engine finished but the ROM is missing:\n$path", FF4Theme.PINK)
                showActions()
                return
            }
            val verification = mutableListOf<String>()
            val v = o.optJSONArray("verification")
            if (v != null) for (i in 0 until v.length()) verification.add(v.getString(i))
            val same = verification == old.verification

            // Replace in place, keeping the session's name and its .srm.
            fresh.copyTo(rom, overwrite = true)
            RomLibrary.writeMeta(
                rom,
                old.copy(
                    flags = o.optString("flags"),
                    binaryFlags = o.optString("binary_flags"),
                    verification = verification,
                    version = o.optString("version"),
                    seconds = o.optDouble("seconds", 0.0),
                )
            )
            setStatus(
                if (same) "Re-saved ${old.seed} — ROM matches the stored check (${verification.joinToString("/")})"
                else "Re-saved ${old.seed} — ⚠ check changed: ${verification.joinToString("/")} vs ${old.verification.joinToString("/")}",
                if (same) FF4Theme.GREEN else FF4Theme.YELLOW
            )
        } catch (e: Exception) {
            setStatus("Regeneration error: ${e.message}", FF4Theme.PINK)
        }
        showActions()
    }

    private fun newSeedWithSameFlags(rom: File) {
        val m = RomLibrary.readMeta(rom)
        if (m.flags.isBlank()) {
            setStatus("This session has no stored flags to reuse.", FF4Theme.PINK)
            return
        }
        FlagSession.setFlags(m.flags)
        FlagSession.setPresetName("from ${m.name}")
        FlagSession.setSeed("")
        startActivity(Intent(this, FF4GenerateActivity::class.java))
    }

    private fun saveFlagsAsFlagset(rom: File) {
        val m = RomLibrary.readMeta(rom)
        if (m.flags.isBlank()) {
            setStatus("This session has no flags to save.", FF4Theme.PINK)
            return
        }
        val input = EditText(this).apply {
            setText(m.name.ifBlank { "Flagset" })
            setSelection(text.length)
            FF4Theme.applyText(this@FF4PlayActivity, this)
        }
        AlertDialog.Builder(this)
            .setTitle("Save flags as Flagset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                FlagsetLibrary.save(this, input.text.toString(), m.flags)
                setStatus("Saved flagset \"${input.text}\".", FF4Theme.GREEN)
                showActions()
            }
            .setNegativeButton("Cancel") { _, _ -> showActions() }
            .show()
    }

    private fun rename(rom: File) {
        val m = RomLibrary.readMeta(rom)
        val input = EditText(this).apply {
            setText(m.name.ifBlank { rom.nameWithoutExtension })
            setSelection(text.length)
            FF4Theme.applyText(this@FF4PlayActivity, this)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename session")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotBlank()) RomLibrary.rename(rom, newName)
                showList()
            }
            .setNegativeButton("Cancel") { _, _ -> showActions() }
            .show()
    }

    private fun confirmDelete(rom: File) {
        val m = RomLibrary.readMeta(rom)
        FF4Dialog.confirm(
            this,
            "Delete session",
            "Delete \"${m.name}\", its ROM and its save from FF4_FE?",
            yes = "Delete",
            no = "Cancel",
            onYes = {
                RomLibrary.delete(rom)
                showList()
            },
            onNo = { showActions() },
        )
    }

    override fun onBackRequested(): Boolean = when (mode) {
        MODE_ACTIONS -> { showList(); true }
        MODE_PLAY -> { showActions(); true }
        MODE_SAVE -> { showActions(); true }
        else -> { finish(); true }
    }

    companion object {
        private const val MODE_LIST = 0
        private const val MODE_ACTIONS = 1
        private const val MODE_PLAY = 2
        private const val MODE_SAVE = 3
    }
}