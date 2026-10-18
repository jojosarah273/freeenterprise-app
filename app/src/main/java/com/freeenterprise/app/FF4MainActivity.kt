package com.freeenterprise.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.freeenterprise.app.ff4ui.FF4Menu
import com.freeenterprise.app.ff4ui.FF4MenuActivity
import com.freeenterprise.app.ff4ui.FF4Theme
import androidx.activity.result.contract.ActivityResultContracts

/**
 * FF4 main menu with the app's start-up gates:
 *   1. storage access for the FF4_FE library folder
 *   2. base FF2 (US) ROM import (first run)
 * then: Play Free Enterprise / Generate Seed / Settings / Exit.
 */
class FF4MainActivity : FF4MenuActivity() {

    private var inSettings = false
    private var notice: String? = null

    private val romPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importRom(uri)
    }
    private val legacyStoragePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        when {
            !RomLibrary.hasRootAccess(this) -> showStorageGate()
            !RomStore.hasBaseRom(this) -> showRomGate()
            inSettings -> showSettings()
            else -> showMainMenu()
        }
    }

    // ---------------------------------------------------------- start-up gates

    private fun showStorageGate() {
        setStatus(
            "Seeds are kept in FF4_FE at the root of storage.\n" +
                "Grant access so the app can create and manage it.",
            FF4Theme.YELLOW
        )
        setHint("A  Select      B  Exit")
        submitMenu("storage", listOf(
                FF4Menu.Item("Grant storage access"),
                FF4Menu.Item("Exit"),
            )) { i ->
            if (i == 0) requestStorageAccess() else finish()
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(RomLibrary.allFilesAccessIntent(this))
            } catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStoragePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun showRomGate() {
        setStatus(
            "Final Fantasy II (US) ROM required.\n" +
                "Import your own v1.0/v1.1 copy — it stays private to the app.",
            FF4Theme.WHITE
        )
        setHint("A  Select      B  Exit")
        submitMenu("romgate", listOf(
                FF4Menu.Item("Import ROM"),
                FF4Menu.Item("Exit"),
            )) { i ->
            if (i == 0) pickRom() else finish()
        }
    }

    private fun pickRom() {
        romPicker.launch(arrayOf("application/octet-stream", "application/x-rom", "*/*"))
    }

    private fun importRom(uri: Uri) {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            notice = "Could not read the selected file."
            refresh()
            return
        }
        val error = RomStore.validate(bytes)
        if (error != null) {
            setStatus(error, FF4Theme.PINK)
            setHint("A  Select      B  Exit")
            submitMenu("romgate", listOf(
                    FF4Menu.Item("Import ROM"),
                    FF4Menu.Item("Exit"),
                )) { i -> if (i == 0) pickRom() else finish() }
            return
        }
        RomStore.baseRomFile(this).writeBytes(bytes)
        notice = "Base ROM imported ✓"
        refresh()
    }

    // -------------------------------------------------------------- main menu

    private fun showMainMenu() {
        inSettings = false
        val noticeText = notice
        notice = null
        setStatus(noticeText, FF4Theme.GREEN)
        setHint("A  Select      B  Exit")
        val seedCount = RomLibrary.listRoms(this).size
        submitMenu("main", listOf(
                FF4Menu.Item("Play Free Enterprise", "$seedCount seed(s)"),
                FF4Menu.Item("Generate Seed", ""),
                FF4Menu.Item("Settings", ""),
                FF4Menu.Item("Exit", ""),
            )) { i ->
            when (i) {
                0 -> startActivity(Intent(this, FF4PlayActivity::class.java))
                1 -> startActivity(Intent(this, FF4GenerateActivity::class.java))
                2 -> showSettings()
                3 -> finish()
            }
        }
    }

    // ----------------------------------------------------------------- settings

    private fun showSettings() {
        inSettings = true
        val ff4 = EditorPrefs.uiMode(this) != EditorPrefs.UI_CLASSIC
        val folder = RomLibrary.folder(this)
        val engineMarker = java.io.File(filesDir, "python_ext/.engine_version")
        val engineVer = if (engineMarker.exists()) engineMarker.readText().trim() else "not extracted"
        setStatus(
            "Storage: ${if (RomLibrary.usingFallback(this)) "app storage (fallback)" else folder.absolutePath}\n" +
                "Engine build: $engineVer  (app v${BuildConfig.VERSION_NAME})\n" +
                "Seeds, battery saves and save states all live in FF4_FE.",
            FF4Theme.WHITE
        )
        setHint("A  Select      B  Back")
        submitMenu("settings", listOf(
                FF4Menu.Item("Interface", if (ff4) "FF4 style" else "Classic"),
                FF4Menu.Item("Appearance", "colors & size"),
                FF4Menu.Item("Open classic UI now", ""),
                FF4Menu.Item("Back", ""),
            )) { i ->
            when (i) {
                0 -> {
                    val nowFf4 = EditorPrefs.uiMode(this) != EditorPrefs.UI_CLASSIC
                    EditorPrefs.setUiMode(this, if (nowFf4) EditorPrefs.UI_CLASSIC else EditorPrefs.UI_FF4)
                    showSettings()
                }
                1 -> EditorSettingsDialog.show(this) { }
                2 -> startActivity(Intent(this, MainActivity::class.java))
                3 -> showMainMenu()
            }
        }
    }

    override fun onBackRequested(): Boolean {
        if (inSettings) {
            showMainMenu()
            return true
        }
        finish()
        return true
    }
}