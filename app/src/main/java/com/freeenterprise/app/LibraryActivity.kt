package com.freeenterprise.app

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LibraryActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var engine: SeedEngine
    private var engineReady = false
    private var regenerating = false
    private var records: List<SeedRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        listView = findViewById(R.id.seedList)
        engine = SeedEngine(this) { }
        engine.ensureExtracted(
            onDone = { engineReady = true },
            onError = { engineReady = false }
        )
        bind()
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    private fun bind() {
        records = SeedLibrary.load(this)
        val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        val labels = records.map { r ->
            "SEED ${r.seed}  ·  check: ${r.verification.joinToString("/")}  ·  ${dateFmt.format(Date(r.time))}"
        }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_2, android.R.id.text1, labels) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val r = records[position]
                view.findViewById<TextView>(android.R.id.text1).text =
                    "SEED ${r.seed}  ·  check: ${r.verification.joinToString("/")}"
                view.findViewById<TextView>(android.R.id.text2).text =
                    "${r.flags}\n${r.version}  ·  ${dateFmt.format(Date(r.time))}  ·  ${r.fileName}" +
                    if (File(r.filePath).exists()) "" else "  (ROM deleted)"
                return view
            }
        }
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            showActions(records[position])
        }
    }

    private fun showActions(r: SeedRecord) {
        val fileExists = File(r.filePath).exists()
        val actions = mutableListOf<String>()
        actions.add(if (fileExists) "Play (RetroArch)" else "ROM file missing — regenerate")
        actions.add("Regenerate / Re-save ROM (same flags + seed)")
        actions.add("Load flags into editor")
        actions.add("Copy flag string")
        actions.add("Delete record")

        AlertDialog.Builder(this)
            .setTitle("Seed ${r.seed}")
            .setMessage(r.flags)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> if (fileExists) RetroArchLauncher.launch(this, File(r.filePath))
                        else regenerate(r)
                    1 -> regenerate(r)
                    2 -> loadFlagsIntoEditor(r)
                    3 -> copyFlags(r)
                    4 -> {
                        SeedLibrary.remove(this, r)
                        bind()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Deterministic: same flags + seed ⇒ same ROM. Cross-checks the checksum tiles. */
    private fun regenerate(r: SeedRecord) {
        if (regenerating) {
            Toast.makeText(this, "Already regenerating…", Toast.LENGTH_SHORT).show()
            return
        }
        if (!engineReady) {
            Toast.makeText(this, "Engine still starting…", Toast.LENGTH_LONG).show()
            return
        }
        if (!RomStore.hasBaseRom(this)) {
            Toast.makeText(this, "Add your FF2 (US) ROM first (main screen → ADD ROM).", Toast.LENGTH_LONG).show()
            return
        }
        regenerating = true
        val dialog = ProgressDialog(this).apply {
            setTitle("Regenerating ${r.seed}")
            setMessage("Same flags + seed → same ROM (deterministic)\nSaving a fresh copy…")
            setCancelable(false)
            setIndeterminate(true)
            show()
        }
        engine.cacheDir.mkdirs()
        engine.romsDir.mkdirs()
        engine.generate(r.flags, r.seed, "{}") { json ->
            regenerating = false
            dialog.dismiss()
            runOnUiThread { handleRegenResult(r, json) }
        }
    }

    private fun handleRegenResult(old: SeedRecord, json: String) {
        try {
            val o = JSONObject(json)
            if (o.optString("status") != "ok") {
                AlertDialog.Builder(this)
                    .setTitle("Regeneration failed")
                    .setMessage(o.optString("error"))
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            val path = o.optString("path")
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(this, "Engine finished but the file is missing: $path", Toast.LENGTH_LONG).show()
                return
            }
            val ver = buildList {
                val v = o.optJSONArray("verification")
                if (v != null) for (i in 0 until v.length()) add(v.getString(i))
            }
            val same = ver == old.verification
            SeedLibrary.replace(
                this, old,
                old.copy(
                    time = System.currentTimeMillis(),
                    filePath = path,
                    fileName = o.optString("file_name"),
                    seconds = o.optDouble("seconds", 0.0),
                )
            )
            Toast.makeText(
                this,
                if (same) "✓ Re-saved ${old.seed} — ROM identical to the original check (${ver.joinToString("/")})"
                else "Re-saved ${old.seed} — ⚠ check differs from saved (${ver.joinToString("/")} vs ${old.verification.joinToString("/")})",
                Toast.LENGTH_LONG
            ).show()
            bind()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Regeneration error")
                .setMessage(e.message ?: "unknown error")
                .setPositiveButton("OK", null)
                .show()
            bind()
        }
    }

    private fun loadFlagsIntoEditor(r: SeedRecord) {
        Screens.openEditor(this, r.flags, r.seed)
    }

    private fun copyFlags(r: SeedRecord) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FE flags", r.flags))
        Toast.makeText(this, "Flag string copied.", Toast.LENGTH_SHORT).show()
    }
}