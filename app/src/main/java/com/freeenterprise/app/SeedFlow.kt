package com.freeenterprise.app

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared generate → result flow: ROM check, progress dialog, result dialog with
 * Play (RetroArch) / Save-a-copy / New seed, and seed-history recording.
 *
 * Activities that call this should register a CreateDocument launcher and pass
 * it as [exportLauncher] so "Save a copy…" works with a single registration.
 */
object SeedFlow {

    private val pendingExports = HashMap<Context, File>()

    fun generate(
        activity: ComponentActivity,
        engine: SeedEngine,
        flags: String,
        seed: String,
        metaconfigJson: String = "{}",
        exportLauncher: ActivityResultLauncher<String>,
        onNeedRom: (() -> Unit)? = null,
    ) {
        if (!RomStore.hasBaseRom(activity)) {
            Toast.makeText(activity, "Add your FF2 (US) ROM first.", Toast.LENGTH_LONG).show()
            onNeedRom?.invoke()
            return
        }
        val dialog = ProgressDialog(activity).apply {
            setTitle("Generating seed")
            setMessage("Running the FE randomizer on-device…\n(first run takes up to ~a minute)")
            setCancelable(false)
            setIndeterminate(true)
            show()
        }
        engine.cacheDir.mkdirs()
        engine.romsDir.mkdirs()
        engine.generate(flags, seed, metaconfigJson) { resultJson ->
            dialog.dismiss()
            handleResult(activity, resultJson, exportLauncher)
        }
    }

    fun onExportResult(context: Context, uri: android.net.Uri?) {
        val file = pendingExports.remove(context) ?: return
        if (uri == null) return
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            Toast.makeText(context, "Saved.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun setPendingExport(context: Context, file: File) {
        pendingExports[context] = file
    }

    private fun handleResult(context: Context, resultJson: String, exportLauncher: ActivityResultLauncher<String>) {
        val result = try { JSONObject(resultJson) } catch (e: Exception) { null }
        if (result == null || result.optString("status") != "ok") {
            val err = result?.optString("error") ?: resultJson
            AlertDialog.Builder(context)
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
        val file = File(path)

        SeedLibrary.add(
            context,
            SeedRecord(
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
        )

        if (!file.exists()) {
            AlertDialog.Builder(context)
                .setTitle("Engine reported success but the ROM is missing")
                .setMessage("Looked for: $path")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val check = verification.joinToString(", ")
        val time = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
        val retro = if (RetroArchLauncher.retroArchInstalled(context)) "Play (RetroArch)" else "Play (hand-off)"
        AlertDialog.Builder(context)
            .setTitle("Seed $seed ✓")
            .setMessage(
                "Version: $version\n" +
                "Seed:    $seed\n" +
                "Check:   $check\n" +
                "Time:    ${seconds}s  (generated $time)\n\n" +
                "Flags: $flags\n\n" +
                "ROM saved at: ${result.optString("file_name")}"
            )
            .setPositiveButton(retro) { _, _ -> RetroArchLauncher.launch(context, file) }
            .setNeutralButton("Save a copy…") { _, _ ->
                setPendingExport(context, file)
                exportLauncher.launch(result.optString("file_name"))
            }
            .setNegativeButton("New seed", null)
            .show()
    }
}