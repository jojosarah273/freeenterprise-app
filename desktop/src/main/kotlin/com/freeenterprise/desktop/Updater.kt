package com.freeenterprise.desktop

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * In-app updates, the way Tor Browser does them: a self-contained directory, a
 * verified download, and a restart to run the new code.
 *
 * Two channels, because they are not the same problem:
 *
 *   * the **engine** is interpreted Python in `engine/`. It is read by a child
 *     process for each generation, so a new one takes effect immediately - no
 *     restart. The bundle is the *same asset Android consumes*, so one build serves
 *     both platforms.
 *   * the **app** is JVM code in `runtime/`, plus the native libraries and the
 *     launcher. Replacing it needs a restart; files are swapped atomically and the
 *     previous ones kept for rollback.
 *
 * `FF4_FE` is never part of an update: it is the player's data.
 *
 * Nothing here runs without the user asking for it. That is the only network use in
 * the desktop build, and it happens when "Check for updates" is chosen.
 */
object Updater {

    const val REPO = "jojosarah273/freeenterprise-app"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ENGINE_PREFIX = "freeenterprise-engine-"

    /** The version this build was made from, written into resources by Gradle. */
    fun appVersion(): String = try {
        Updater::class.java.getResourceAsStream("/version.txt")
            ?.bufferedReader()?.use { it.readText().trim() } ?: "0.0.0"
    } catch (t: Throwable) {
        "0.0.0"
    }

    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    data class Release(val tag: String, val version: String, val notes: String, val assets: List<Asset>) {
        /** The Linux build for this architecture, if the release carries one. */
        val appAsset: Asset?
            get() = assets.firstOrNull {
                it.name.startsWith("freeenterprise-linux-") && it.name.endsWith(".tar.gz")
            }

        /** The universal engine bundle - the same one the Android app uses. */
        val engineAsset: Asset?
            get() = assets.firstOrNull { it.name.startsWith(ENGINE_PREFIX) && it.name.endsWith(".zip") }

        val engineVersion: String?
            get() = engineAsset?.name?.removePrefix(ENGINE_PREFIX)?.removeSuffix(".zip")

        /** An asset name always carries the version, so a mismatched asset is refused. */
        val appVersionFromAsset: String?
            get() = appAsset?.name?.removeSuffix(".tar.gz")?.substringAfterLast('-')
    }

    /** Reads the latest release. Public repository: no token needed. */
    fun fetchLatest(): Release {
        val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "freeenterprise-desktop")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("GitHub returned HTTP ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = mutableListOf<Asset>()
            json.optJSONArray("assets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    assets.add(
                        Asset(
                            name = a.optString("name"),
                            url = a.optString("browser_download_url"),
                            size = a.optLong("size"),
                            sha256 = a.optString("digest")
                                .takeIf { it.startsWith("sha256:") }
                                ?.removePrefix("sha256:"),
                        )
                    )
                }
            }
            val tag = json.optString("tag_name")
            return Release(tag, tag.trimStart('v'), json.optString("body"), assets)
        } finally {
            connection.disconnect()
        }
    }

    /** x.y.z comparison, ignoring any tag suffix. True when [candidate] is newer. */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(s: String) = s.trimStart('v')
            .substringBefore('-').substringBefore('+')
            .split('.').mapNotNull { it.toIntOrNull() }

        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Downloads [asset] to [target], reporting 0..100, and verifies its sha256. */
    fun download(asset: Asset, target: File, onProgress: (Int) -> Unit) {
        target.parentFile?.mkdirs()
        val connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "freeenterprise-desktop")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("download failed: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.coerceAtLeast(asset.size)
            var copied = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (total > 0) onProgress(((copied * 100) / total).toInt())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        onProgress(100)

        // Verified before anything is unpacked or run.
        asset.sha256?.let { expected ->
            val actual = sha256(target)
            if (!actual.equals(expected, ignoreCase = true)) {
                target.delete()
                error("the download did not match its checksum")
            }
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ engine

    /**
     * Applies an engine bundle: `python_ext/`, `spec/ui_spec.json` and
     * `presets/presets.json` at the zip root, unpacked into `engine/`. The previous
     * engine is kept as `.old` so it can be put back.
     */
    fun applyEngine(installDir: File, zip: File, expectedVersion: String?): String {
        val staging = File(installDir, ".update/engine-staging")
        staging.deleteRecursively()
        staging.mkdirs()
        unzip(zip, staging)

        val stagedTree = File(staging, "python_ext")
        val versionFile = File(stagedTree, "FreeEnt/version.py")
        if (!versionFile.isFile) error("the bundle has no engine in it")
        val version = engineVersionIn(versionFile) ?: error("the bundle's engine has no version")
        if (expectedVersion != null && version != expectedVersion) {
            error("the bundle is $version, but the release says $expectedVersion")
        }

        val engineDir = File(installDir, "engine").apply { mkdirs() }
        val live = File(engineDir, "python_ext")
        val backup = File(engineDir, "python_ext.old")
        backup.deleteRecursively()
        if (live.exists() && !live.renameTo(backup)) error("could not set the old engine aside")
        if (!stagedTree.renameTo(live)) {
            backup.renameTo(live)
            error("could not move the new engine into place")
        }

        // The spec and presets live beside the engine on this platform.
        val assets = File(engineDir, "assets").apply { mkdirs() }
        copyWithBackup(File(staging, "spec/ui_spec.json"), File(assets, "ui_spec.json"))
        copyWithBackup(File(staging, "presets/presets.json"), File(assets, "presets.json"))

        staging.deleteRecursively()
        zip.delete()
        return version
    }

    fun engineInstalledVersion(installDir: File): String? =
        engineVersionIn(File(installDir, "engine/python_ext/FreeEnt/version.py"))

    fun canRollBackEngine(installDir: File): Boolean =
        File(installDir, "engine/python_ext.old").isDirectory

    fun rollbackEngine(installDir: File): String {
        val engineDir = File(installDir, "engine")
        val live = File(engineDir, "python_ext")
        val backup = File(engineDir, "python_ext.old")
        if (!backup.isDirectory) error("there is no previous engine to go back to")
        live.deleteRecursively()
        if (!backup.renameTo(live)) error("could not restore the previous engine")
        return engineInstalledVersion(installDir) ?: "unknown"
    }

    private fun engineVersionIn(versionFile: File): String? {
        if (!versionFile.isFile) return null
        val text = versionFile.readText()
        fun value(name: String) = Regex("^$name\\s*=\\s*(.+)$", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1)?.trim()?.trim('"')?.trim('\'')
        val major = value("MAJOR") ?: return null
        val minor = value("MINOR") ?: return null
        val patch = value("PATCH") ?: return null
        val fork = value("FORK")
        return if (fork.isNullOrBlank()) "$major.$minor.$patch" else "$major.$minor.$patch.$fork"
    }

    // --------------------------------------------------------------------- app

    /**
     * Applies a Linux build: extracts to staging, checks it is a build, then swaps
     * `runtime/`, `native/` and the launcher, keeping the old ones as `.old`.
     * `FF4_FE` and `engine/` are deliberately untouched.
     */
    fun applyApp(installDir: File, tarGz: File, expectedVersion: String?) {
        val stagingRoot = File(installDir, ".update/app-staging")
        stagingRoot.deleteRecursively()
        stagingRoot.mkdirs()

        val tar = ProcessBuilder("tar", "-xzf", tarGz.absolutePath, "-C", stagingRoot.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = tar.inputStream.bufferedReader().readText()
        if (!tar.waitFor(180, java.util.concurrent.TimeUnit.SECONDS) || tar.exitValue() != 0) {
            stagingRoot.deleteRecursively()
            tarGz.delete()
            error("could not unpack the update: ${output.takeLast(200)}")
        }

        // The tarball usually holds one directory; accept either layout.
        val staged = stagingRoot.listFiles()?.firstOrNull { it.isDirectory } ?: stagingRoot
        val stagedLauncher = File(staged, "FreeEnterprise")
        val stagedRuntime = File(staged, "runtime")
        val stagedNative = File(staged, "native")
        if (!stagedLauncher.isFile || !stagedRuntime.isDirectory || !stagedNative.isDirectory) {
            stagingRoot.deleteRecursively()
            tarGz.delete()
            error("the download does not look like a build")
        }
        expectedVersion?.let { wanted ->
            val found = File(staged, "runtime/lib/version.txt").takeIf { it.isFile }?.readText()?.trim()
            if (found != null && found != wanted) {
                stagingRoot.deleteRecursively()
                tarGz.delete()
                error("the download is $found, but the release says $wanted")
            }
        }

        // Swap the three parts, keeping the old ones for rollback.
        replace(File(installDir, "runtime"), stagedRuntime)
        replace(File(installDir, "native"), stagedNative)
        replace(File(installDir, "FreeEnterprise"), stagedLauncher)

        stagingRoot.deleteRecursively()
        tarGz.delete()
    }

    fun canRollBackApp(installDir: File): Boolean =
        File(installDir, "runtime.old").isDirectory

    fun rollbackApp(installDir: File) {
        val live = File(installDir, "runtime")
        val old = File(installDir, "runtime.old")
        if (!old.isDirectory) error("there is no previous build to go back to")
        live.deleteRecursively()
        if (!old.renameTo(live)) error("could not restore the previous build")
    }

    /** Starts the launcher again, so the new code runs. */
    fun restart(installDir: File) {
        val launcher = File(installDir, "FreeEnterprise")
        if (!launcher.isFile) error("the launcher is missing")
        ProcessBuilder(launcher.absolutePath)
            .directory(installDir)
            .inheritIO()
            .start()
    }

    // ----------------------------------------------------------------- helpers

    private fun replace(live: File, staged: File) {
        val backup = File(live.parentFile, live.name + ".old")
        backup.deleteRecursively()
        if (live.exists() && !live.renameTo(backup)) error("could not set ${live.name} aside")
        val moved = if (staged.renameTo(live)) true else {
            // Different filesystem: copy instead.
            staged.copyRecursively(live, overwrite = true)
            staged.deleteRecursively()
            true
        }
        if (!moved) error("could not move ${live.name} into place")
        if (live.isFile) live.setExecutable(true)
    }

    private fun copyWithBackup(from: File, live: File) {
        if (!from.isFile) return
        live.parentFile?.mkdirs()
        File(live.parentFile, live.name + ".old").also { old ->
            if (live.isFile) live.copyTo(old, overwrite = true)
        }
        from.copyTo(live, overwrite = true)
    }

    private fun unzip(zip: File, into: File) {
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = File(into, entry.name)
                // Refuse entries that escape the staging directory.
                if (!target.canonicalPath.startsWith(into.canonicalPath + File.separator)) {
                    error("the archive contains an unsafe path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                input.closeEntry()
            }
        }
    }
}
