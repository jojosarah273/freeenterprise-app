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
                FF4Menu.Item("Check for updates", "v${BuildConfig.VERSION_NAME}"),
                FF4Menu.Item("Settings", ""),
                FF4Menu.Item("Exit", ""),
            )) { i ->
            when (i) {
                0 -> startActivity(Intent(this, FF4PlayActivity::class.java))
                1 -> startActivity(Intent(this, FF4GenerateActivity::class.java))
                2 -> checkForUpdates()
                3 -> showSettings()
                4 -> finish()
            }
        }
    }

    // ------------------------------------------------------------------ updates

    /**
     * Manual only. Reads the project's latest release and, if it is newer, offers
     * to **install the app itself** — the engine ships inside the APK, so this
     * updates everything: UI, engine, assets.
     *
     * Android will not let an app replace itself silently, so the download is
     * verified and then handed to the system installer, which shows its own
     * Update dialog. It is an update, not a reinstall: seeds, saves and settings
     * are all kept.
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
        val installed = BuildConfig.VERSION_NAME

        result.onFailure { error ->
            // No connection and DNS failures are the common case for an app that
            // is normally used offline, so say what actually happened and give a
            // way out rather than just echoing the exception.
            val message = error.message ?: "unknown error"
            val offline = message.contains("resolve", ignoreCase = true) ||
                message.contains("UnknownHost", ignoreCase = true) ||
                message.contains("Unable to resolve", ignoreCase = true) ||
                error is java.net.UnknownHostException
            setStatus(
                if (offline) {
                    "No connection to the update server.\n\n" +
                        "Android could not look up api.github.com, so nothing was sent.\n" +
                        "Check that this device is online (and not on a VPN or a DNS\n" +
                        "filter that blocks github.com), then try again.\n\n" +
                        "Everything else in the app works offline."
                } else {
                    "Could not check for updates:\n$message"
                },
                FF4Theme.PINK
            )
            android.util.Log.w("FEUpdate", networkDiagnosis())
            setHint(networkDiagnosis())
            submitMenu("update", listOf(
                FF4Menu.Item("Try again"),
                FF4Menu.Item("Open release page", "in a browser"),
                FF4Menu.Item("Back"),
            )) { i ->
                when (i) {
                    0 -> checkForUpdates()
                    1 -> openReleasePage()
                    2 -> showMainMenu()
                }
            }
        }

        result.onSuccess { release ->
            val apk = release.apkAsset
            val engineAsset = release.engineAsset
            val engineVersion = release.engineVersion
            val installedEngine = EngineUpdater.installedVersion(this)
            val appNewer = UpdateChecker.isNewer(release.version, installed)
            val engineNewer = engineAsset != null && engineVersion != null &&
                (installedEngine == null || UpdateChecker.isNewer(engineVersion, installedEngine))

            // Engine-first: an engine change installs silently and in place, with
            // no system dialog. Only a genuinely new APK needs the one-tap
            // installer. Users see a single "Update" either way.
            if (engineNewer && engineAsset != null && engineVersion != null) {
                setStatus(
                    "Installed   app v$installed  ·  engine ${installedEngine ?: "?"}\n" +
                        "Available   engine $engineVersion" +
                        (if (appNewer) "  ·  app v${release.version}" else "") + "\n\n" +
                        "This is an engine change, so it installs in place — no reinstall,\n" +
                        "no confirmation dialog. Seeds, saves and settings are untouched.",
                    FF4Theme.GREEN
                )
                setHint("A  Select      B  Back")
                submitMenu("update", listOf(
                    FF4Menu.Item("Update to $engineVersion", "in place, silent"),
                    FF4Menu.Item("Roll back the engine", "restore previous"),
                    FF4Menu.Item("Check again"),
                    FF4Menu.Item("Back"),
                )) { i ->
                    when (i) {
                        0 -> installEngineUpdate(release, engineAsset, engineVersion)
                        1 -> doRollback()
                        2 -> checkForUpdates()
                        3 -> showMainMenu()
                    }
                }
                return@onSuccess
            }

            if (appNewer && apk != null) {
                setStatus(
                    "Installed   v$installed\n" +
                        "Available   v${release.version}  ·  ${apk.sizeBytes / 1024 / 1024} MB\n\n" +
                        "This release changes the app itself (UI or native code), which Android\n" +
                        "only installs with one confirmation tap. It is an update, not a\n" +
                        "reinstall — seeds, saves and settings are kept.",
                    FF4Theme.GREEN
                )
                setHint("A  Select      B  Back")
                submitMenu("update", listOf(
                    FF4Menu.Item("Download & install v${release.version}", "in place"),
                    FF4Menu.Item("Open release page", "what's new"),
                    FF4Menu.Item("Check again"),
                    FF4Menu.Item("Back"),
                )) { i ->
                    when (i) {
                        0 -> downloadAndInstall(release, apk)
                        1 -> openReleasePage()
                        2 -> checkForUpdates()
                        3 -> showMainMenu()
                    }
                }
            } else {
                setStatus(
                    "You are up to date.\n\nInstalled v$installed  ·  latest v${release.version}" +
                        if (apk == null) "\n(This release has no APK attached.)" else "",
                    FF4Theme.WHITE
                )
                setHint("A  Select      B  Back")
                submitMenu("update", listOf(
                    FF4Menu.Item("Check again"),
                    FF4Menu.Item("Open release page", "what's new"),
                    FF4Menu.Item("Back"),
                )) { i ->
                    when (i) {
                        0 -> checkForUpdates()
                        1 -> openReleasePage()
                        2 -> showMainMenu()
                    }
                }
            }
        }
    }

    private fun installEngineUpdate(release: ReleaseInfo, asset: ReleaseAsset, version: String) {
        if (busy) return
        busy = true
        val overlay = com.freeenterprise.app.ff4ui.FF4ProgressOverlay(this).apply {
            addContentView(this, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ))
            show()
            update(0, "Updating engine", version, 0)
        }
        progressOverlay = overlay
        val started = System.currentTimeMillis()

        Thread {
            val result = EngineUpdater.update(this, engine, asset) { percent, stage, detail ->
                ioHandler.post {
                    overlay.update(percent, stage, detail, (System.currentTimeMillis() - started) / 1000)
                }
            }
            ioHandler.post {
                busy = false
                progressOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
                progressOverlay = null
                result.onSuccess { v ->
                    setStatus(
                        "Engine updated to $v — installed in place, no dialog, no reinstall.\n" +
                            "Existing seeds are untouched; new ones use the new engine.",
                        FF4Theme.GREEN
                    )
                }.onFailure { e ->
                    setStatus("Engine update failed:\n${e.message}", FF4Theme.PINK)
                }
                submitMenu("update", listOf(
                    FF4Menu.Item("Back to menu"),
                    FF4Menu.Item("Check for updates"),
                )) { i -> if (i == 0) showMainMenu() else checkForUpdates() }
            }
        }.start()
    }

    private fun doRollback() {
        if (busy) return
        busy = true
        setStatus("Rolling back…", FF4Theme.YELLOW)
        Thread {
            val result = EngineUpdater.rollback(this, engine)
            ioHandler.post {
                busy = false
                result.onSuccess { v -> setStatus("Rolled back to engine $v.", FF4Theme.GREEN) }
                    .onFailure { e -> setStatus("Rollback failed: ${e.message}", FF4Theme.PINK) }
                submitMenu("update", listOf(FF4Menu.Item("Back to menu"))) { showMainMenu() }
            }
        }.start()
    }

    private fun downloadAndInstall(release: ReleaseInfo, asset: ReleaseAsset) {
        if (busy) return
        busy = true
        val overlay = com.freeenterprise.app.ff4ui.FF4ProgressOverlay(this).apply {
            addContentView(this, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ))
            show()
            update(0, "Downloading v${release.version}", "", 0)
        }
        progressOverlay = overlay
        val started = System.currentTimeMillis()

        Thread {
            val outcome = runCatching {
                val apkFile = java.io.File(cacheDir, "update.apk")
                apkFile.delete()
                val sha = download(asset, apkFile) { percent, copied, total ->
                    ioHandler.post {
                        overlay.update(
                            percent, "Downloading v${release.version}",
                            "${copied / 1024 / 1024} / ${total / 1024 / 1024} MB",
                            (System.currentTimeMillis() - started) / 1000
                        )
                    }
                }

                asset.sha256?.let { expected ->
                    check(sha.equals(expected, ignoreCase = true)) {
                        "Checksum mismatch — refusing to install"
                    }
                }
                val apkVersion = AppInstaller.apkVersionCode(this, apkFile.absolutePath)
                check(apkVersion > BuildConfig.VERSION_CODE.toLong()) {
                    "The download is not newer than the installed version"
                }
                val apkSigner = AppInstaller.apkSignerSha256(this, apkFile.absolutePath)
                val ourSigner = AppInstaller.installedSignerSha256(this)
                check(apkSigner != null && ourSigner != null && apkSigner == ourSigner) {
                    "The download is signed by a different key — refusing to install"
                }
                apkFile
            }
            ioHandler.post {
                busy = false
                progressOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
                progressOverlay = null
                outcome.onSuccess { apkFile -> beginInstall(apkFile) }
                outcome.onFailure { error ->
                    setStatus("Update failed:\n${error.message}", FF4Theme.PINK)
                    submitMenu("update", listOf(
                        FF4Menu.Item("Try again"),
                        FF4Menu.Item("Back"),
                    )) { i -> if (i == 0) downloadAndInstall(release, asset) else showMainMenu() }
                }
            }
        }.start()
    }

    private fun beginInstall(apkFile: java.io.File) {
        if (!AppInstaller.canInstall(this)) {
            setStatus(
                "Android needs your permission before it will install an update.\n\n" +
                    "Allow \"Install unknown apps\" for Free Enterprise, then come back and\n" +
                    "install again — the download is already done.",
                FF4Theme.YELLOW
            )
            setHint("A  Select      B  Back")
            submitMenu("update", listOf(
                FF4Menu.Item("Allow installs (opens Android settings)"),
                FF4Menu.Item("Install now"),
                FF4Menu.Item("Back"),
            )) { i ->
                when (i) {
                    0 -> try {
                        startActivity(AppInstaller.unknownSourcesIntent(this))
                    } catch (_: Exception) {
                        setStatus("Could not open the setting on this device.", FF4Theme.PINK)
                    }
                    1 -> beginInstall(apkFile)
                    2 -> showMainMenu()
                }
            }
            return
        }

        InstallResultReceiver.remember(this)
        InstallResultReceiver.onResult = { message ->
            if (message != null) {
                setStatus("Update failed:\n$message", FF4Theme.PINK)
                submitMenu("update", listOf(FF4Menu.Item("Back to menu"))) { showMainMenu() }
            } else {
                // The install was accepted; Android replaces us and restarts the app.
                setStatus("Installing… the app will restart on the new version.", FF4Theme.GREEN)
            }
        }
        try {
            AppInstaller.install(this, apkFile)
        } catch (e: Exception) {
            setStatus("Could not start the installer:\n${e.message}", FF4Theme.PINK)
        }
    }

    /** Streams the APK to cache, returning its SHA-256 and reporting progress. */
    private fun download(
        asset: ReleaseAsset,
        target: java.io.File,
        onProgress: (Int, Long, Long) -> Unit,
    ): String {
        val connection = (java.net.URL(asset.url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "freeenterprise-app")
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        try {
            val total = if (asset.sizeBytes > 0) asset.sizeBytes else connection.contentLengthLong
            java.io.BufferedInputStream(connection.inputStream).use { input ->
                java.io.FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        copied += n
                        if (total > 0) {
                            val percent = (copied * 100 / total).toInt()
                            if (percent != last) {
                                last = percent
                                onProgress(percent, copied, total)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Facts, not guesses, when a check fails: whether INTERNET is declared *and*
     * granted to this installed package, and whether Android thinks there is a
     * network. A missing grant is the one failure that affects only this app —
     * browsers keep working — and it looks exactly like a DNS error.
     */
    private fun networkDiagnosis(): String {
        val declared = try {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
        } catch (_: Exception) {
            false
        }
        val granted = checkSelfPermission(android.Manifest.permission.INTERNET) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val state = try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "no active network"
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "connected"
                else -> "connected, no internet"
            }
        } catch (_: Exception) {
            "unknown"
        }
        return "Diagnosis — INTERNET declared: $declared, granted: $granted · network: $state"
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