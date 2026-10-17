package com.freeenterprise.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a finished ROM to RetroArch (used as a fallback to the in-app core).
 * RetroArch ships under several package names — notably com.retroarch.aarch64
 * for the sideloaded arm64 build — so we probe all of them, and we do NOT use
 * a chooser when we know the package (a chooser would drop that restriction).
 */
object RetroArchLauncher {

    private val PACKAGES = listOf(
        "com.retroarch",
        "com.retroarch.aarch64",
        "com.retroarch.ra32",
    )

    fun installedPackage(context: Context): String? =
        PACKAGES.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

    fun retroArchInstalled(context: Context): Boolean = installedPackage(context) != null

    fun launch(context: Context, romFile: File) {
        if (!romFile.exists()) {
            Toast.makeText(context, "ROM file is missing.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", romFile)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share the ROM: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        val pkg = installedPackage(context)

        // Try progressively looser matches: exact package first (no chooser),
        // then a couple of mime types, then a plain chooser.
        val attempts = mutableListOf<Intent>()
        for (mime in listOf("*/*", "application/octet-stream")) {
            if (pkg != null) attempts.add(intentFor(uri, mime, pkg))
        }
        // System/other emulators
        attempts.add(intentFor(uri, "*/*", null))
        attempts.add(Intent.createChooser(intentFor(uri, "*/*", null), "Open with emulator"))

        for (intent in attempts) {
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // try the next variant
            } catch (_: SecurityException) {
                // try the next variant
            }
        }

        Toast.makeText(
            context,
            "No emulator app accepted the ROM. RetroArch found: ${pkg ?: "no"}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun intentFor(uri: android.net.Uri, mime: String, pkg: String?): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("rom", uri)
            if (pkg != null) setPackage(pkg)
        }
}