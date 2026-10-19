package com.freeenterprise.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Installs an engine bundle **silently, in place** — no APK, no installer, no
 * system dialog.
 *
 * This is possible only because the randomizer is interpreted Python plus data:
 * replacing `python_ext`, `spec` and `presets` in app storage changes the engine
 * without touching the installed application. Anything compiled — the FF4 UI,
 * native libraries — still needs a real APK update (see [AppInstaller]).
 *
 * Stages: download → verify (SHA-256 from the release asset digest) → stage and
 * check → swap, keeping the previous engine for rollback, → tell the interpreter
 * to drop its cached modules → smoke-test, rolling back automatically on failure.
 */
object EngineUpdater {

    /** Directories an engine bundle replaces. */
    private val TREES = listOf("python_ext", "spec", "presets")

    /** The engine version currently on disk. */
    fun installedVersion(context: Context): String? =
        versionIn(File(context.filesDir, "python_ext/FreeEnt/version.py"))

    fun canRollBack(context: Context): Boolean =
        TREES.any { File(context.filesDir, "$it.prev").exists() }

    /** @param onProgress (percent, stage, detail) — called from the worker thread. */
    fun update(
        context: Context,
        engine: SeedEngine,
        asset: ReleaseAsset,
        onProgress: (Int, String, String) -> Unit,
    ): Result<String> {
        return try {
            val download = File(context.cacheDir, "engine-update.zip")
            download.delete()
            onProgress(0, "Downloading engine", "")
            val digest = download(asset, download, onProgress)

            asset.sha256?.let { expected ->
                if (!digest.equals(expected, ignoreCase = true)) {
                    download.delete()
                    return Result.failure(IllegalStateException(
                        "Checksum mismatch — refusing to install.\nexpected $expected\ngot      $digest"
                    ))
                }
            }

            onProgress(100, "Unpacking", "")
            val staging = File(context.filesDir, "engine-staging")
            staging.deleteRecursively()
            staging.mkdirs()
            unzip(download, staging)
            download.delete()

            val expectedVersion = asset.name
                .removePrefix(ReleaseInfo.ENGINE_PREFIX).removeSuffix(".zip")
            val stagedVersion = versionIn(File(staging, "python_ext/FreeEnt/version.py"))
            if (stagedVersion == null) {
                staging.deleteRecursively()
                return Result.failure(IllegalStateException(
                    "The bundle has no engine version file — it is not an engine bundle."
                ))
            }
            if (stagedVersion != expectedVersion) {
                staging.deleteRecursively()
                return Result.failure(IllegalStateException(
                    "Bundle says $stagedVersion but the asset is named $expectedVersion."
                ))
            }
            TREES.firstOrNull { !File(staging, it).isDirectory }?.let { missing ->
                staging.deleteRecursively()
                return Result.failure(IllegalStateException("The bundle is missing '$missing/'."))
            }

            onProgress(100, "Installing", stagedVersion)
            swapIn(context, staging)
            staging.deleteRecursively()

            engine.reloadEngineBlocking()
            val check = engine.smokeTestBlocking()
            if (check != null) {
                rollback(context, engine)
                return Result.failure(IllegalStateException("New engine failed its check: $check"))
            }
            Result.success(stagedVersion)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Puts the previous engine back and reloads. */
    fun rollback(context: Context, engine: SeedEngine): Result<String> {
        return try {
            val files = context.filesDir
            for (tree in TREES) {
                val live = File(files, tree)
                val prev = File(files, "$tree.prev")
                if (!prev.exists()) continue
                val dead = File(files, "$tree.old")
                dead.deleteRecursively()
                live.renameTo(dead)
                prev.renameTo(live)
                dead.deleteRecursively()
            }
            engine.reloadEngineBlocking()
            Result.success(installedVersion(context) ?: "unknown")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun swapIn(context: Context, staging: File) {
        val files = context.filesDir
        for (tree in TREES) {
            val live = File(files, tree)
            val prev = File(files, "$tree.prev")
            prev.deleteRecursively()
            if (live.exists()) live.renameTo(prev)
            File(staging, tree).renameTo(live)
        }
    }

    /**
     * Compose the engine version from its parts.
     *
     * `version.py` declares `VERSION_STR = f"{MAJOR}.{MINOR}.{PATCH}.{FORK}"`, so
     * reading that line gives the *template*, not the version — and MAJOR/MINOR/
     * PATCH are bare ints while FORK is quoted, so each part is read and trimmed.
     */
    private fun versionIn(versionFile: File): String? {
        if (!versionFile.exists()) return null
        val text = try {
            versionFile.readText()
        } catch (_: Exception) {
            return null
        }
        fun part(key: String): String? {
            val raw = Regex("(?m)^\\s*$key\\s*=\\s*(.+?)\\s*$")
                .find(text)?.groupValues?.get(1) ?: return null
            return raw.trim().trim('"').trim('\'')
        }
        val major = part("MAJOR") ?: return null
        val minor = part("MINOR") ?: return null
        val patch = part("PATCH") ?: return null
        val fork = part("FORK") ?: return null
        return "$major.$minor.$patch.$fork"
    }

    private fun download(
        asset: ReleaseAsset,
        target: File,
        onProgress: (Int, String, String) -> Unit,
    ): String {
        val connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "freeenterprise-app")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val total = if (asset.sizeBytes > 0) asset.sizeBytes else connection.contentLengthLong
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(target).use { output ->
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
                                onProgress(
                                    percent, "Downloading engine",
                                    "${copied / 1024 / 1024} / ${total / 1024 / 1024} MB"
                                )
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

    /** Zip extraction with a zip-slip guard. */
    private fun unzip(zip: File, destination: File) {
        val root = destination.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val target = File(destination, entry.name)
                if (!target.canonicalPath.startsWith(root)) {
                    throw SecurityException("Refusing an entry outside the bundle: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> stream.copyTo(out) }
                }
                stream.closeEntry()
            }
        }
    }
}
