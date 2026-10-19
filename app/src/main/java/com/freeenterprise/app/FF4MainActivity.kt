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

    private lateinit var engine: SeedEngine
    private val ioHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var progressOverlay: com.freeenterprise.app.ff4ui.FF4ProgressOverlay? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = SeedEngine(this) { }
        engine.ensureExtracted(onDone = { }, onError = { })
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        progressOverlay = null
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

    // ------------------------------------------------------------------ updates

    /**
     * Manual only: fetches the project's latest release and offers an
     * **engine-only** update, which installs in place (no APK, no installer).
     * A newer *app* version is reported, but installing an APK needs the
     * installer permission, which we have not taken yet.
     */
    private fun checkForUpdates() {
        if (busy) return
        busy = true
        setStatus("Checking for updates…", FF4Theme.YELLOW)
        setHint("Please wait…")
        Thread {
            val result = runCatching { UpdateChecker.fetchLatest() }
            ioHandler.post { busy = false; onUpdateCheckFinished(result) }
        }.start()
    }

    private fun onUpdateCheckFinished(result: Result<ReleaseInfo>) {
        val installedApp = BuildConfig.VERSION_NAME
        val installedEngine = EngineUpdater.installedVersion(this)
        val rollbackAvailable = EngineUpdater.canRollBack(this)

        result.onFailure { error ->
            setStatus("Could not check for updates:\n${error.message}", FF4Theme.PINK)
            submitMenu("update", listOf(
                FF4Menu.Item("Try again"),
                FF4Menu.Item("Back"),
            )) { i -> if (i == 0) checkForUpdates() else showMainMenu() }
        }

        result.onSuccess { release ->
            val engineAsset = release.engineAsset
            val engineVersion = release.engineVersion
            val appNewer = UpdateChecker.isNewer(release.version, installedApp)
            val engineNewer = engineAsset != null && engineVersion != null &&
                (installedEngine == null || UpdateChecker.isNewer(engineVersion, installedEngine))

            setStatus(
                "Installed   app v$installedApp  ·  engine ${installedEngine ?: "?"}\n" +
                    "Latest      app v${release.version}" +
                    (if (engineVersion != null) "  ·  engine $engineVersion" else "  ·  no engine bundle") +
                    (if (engineNewer) "\n\nAn engine update is ready to install in place." else ""),
                if (engineNewer) FF4Theme.GREEN else FF4Theme.WHITE
            )
            setHint("A  Select      B  Back")

            val items = mutableListOf<FF4Menu.Item>()
            val actions = mutableListOf<() -> Unit>()

            if (engineNewer && engineAsset != null && engineVersion != null) {
                items.add(FF4Menu.Item("Update engine to $engineVersion", "in place"))
                actions.add { installEngineUpdate(release, engineAsset, engineVersion) }
            } else if (installedEngine != null) {
                items.add(FF4Menu.Item("Engine is up to date", "none", enabled = false))
                actions.add { }
            }

            if (appNewer) {
                items.add(FF4Menu.Item("App v${release.version} available", "see release"))
                actions.add { openReleasePage() }
            }

            if (rollbackAvailable) {
                items.add(FF4Menu.Item("Roll back the engine", "restore previous"))
                actions.add { doRollback() }
            }

            items.add(FF4Menu.Item("Check again"))
            actions.add { checkForUpdates() }
            items.add(FF4Menu.Item("Back"))
            actions.add { showMainMenu() }

            submitMenu("update", items) { index ->
                actions.getOrNull(index)?.invoke()
            }
        }
    }

    private fun installEngineUpdate(release: ReleaseInfo, asset: ReleaseAsset, version: String) {
        if (busy) return
        busy = true
        val overlay = com.freeenterprise.app.ff4ui.FF4ProgressOverlay(this)
        progressOverlay = overlay
        addContentView(overlay, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        overlay.show()
        overlay.update(0, "Updating engine", version, 0)
        val started = System.currentTimeMillis()

        Thread {
            val result = EngineUpdater.update(this, engine, asset) { percent, stage, detail ->
                ioHandler.post {
                    overlay.update(
                        percent, stage, detail,
                        (System.currentTimeMillis() - started) / 1000
                    )
                }
            }
            ioHandler.post {
                busy = false
                progressOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
                progressOverlay = null
                onEngineUpdateFinished(result)
            }
        }.start()
    }

    private fun onEngineUpdateFinished(result: Result<String>) {
        result.onSuccess { version ->
            setStatus(
                "Engine updated to $version — installed in place, no reinstall needed.\n" +
                    "Seeds you already made are untouched; new seeds use the new engine.",
                FF4Theme.GREEN
            )
        }
        result.onFailure { error ->
            setStatus("Engine update failed:\n${error.message}", FF4Theme.PINK)
        }
        setHint("A  Select      B  Back")
        submitMenu("update", listOf(
            FF4Menu.Item("Back to menu"),
            FF4Menu.Item("Check for updates"),
        )) { i -> if (i == 0) showMainMenu() else checkForUpdates() }
    }

    private fun doRollback() {
        if (busy) return
        busy = true
        setStatus("Rolling back…", FF4Theme.YELLOW)
        Thread {
            val result = EngineUpdater.rollback(this, engine)
            ioHandler.post {
                busy = false
                result.onSuccess { v ->
                    setStatus("Rolled back to engine $v.", FF4Theme.GREEN)
                }.onFailure { e ->
                    setStatus("Rollback failed: ${e.message}", FF4Theme.PINK)
                }
                submitMenu("update", listOf(FF4Menu.Item("Back to menu"))) { showMainMenu() }
            }
        }.start()
    }

    private fun openReleasePage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/${UpdateChecker.REPO}/releases/latest")))
        } catch (_: Exception) {
            setStatus("No browser available to open the release page.", FF4Theme.PINK)
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