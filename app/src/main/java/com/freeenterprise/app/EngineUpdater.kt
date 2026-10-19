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
 * Installs an engine bundle **in place** — no APK, no installer, no reinstall.
 *
 * The randomizer is interpreted Python plus data, so replacing `python_ext`,
 * `spec` and `presets` in app storage is enough to change the engine. The four
 * stages are: download → verify (sha256 from the release asset's digest) →
 * stage and check → swap, then ask the interpreter to drop its cached modules.
 *
 * The previous engine is kept as `*.prev` so a bad update can be rolled back.
 */
object EngineUpdater {

    /** Directories replaced by an engine bundle, in the order they are swapped. */
    private val TREES = listOf("python_ext", "spec", "presets")

    /** The engine version currently on disk, read from the engine itself. */
    fun installedVersion(context: Context): String? =
        versionIn(File(context.filesDir, "python_ext/FreeEnt/version.py"))

    fun canRollBack(context: Context): Boolean =
        TREES.any { File(context.filesDir, "$it.prev").exists() }

    /** @param onProgress (percent 0..100, stage, detail) — called from the worker thread. */
    fun update(
        context: Context,
        engine: SeedEngine,
        asset: ReleaseAsset,
        onProgress: (Int, String, String) -> Unit,
    ): Result<String> {
        return try {
            val download = File(context.cacheDir, "engine-update.zip")
            download.delete()
            onProgress(0, "Downloading engine", "${asset.sizeBytes / 1024 / 1024} MB")
            val digest = download(asset.url, download, asset.sizeBytes, onProgress)

            asset.sha256?.let { expected ->
                if (!digest.equals(expected, ignoreCase = true)) {
                    download.delete()
                    return Result.failure(IllegalStateException(
                        "Checksum mismatch — refusing to install.\n" +
                        "expected $expected\ngot      $digest"
                    ))
                }
            }

            onProgress(100, "Unpacking", "")
            val staging = File(context.filesDir, "engine-staging")
            staging.deleteRecursively()
            staging.mkdirs()
            unzip(download, staging)
            download.delete()

            // structural + identity check before touching the working engine
            val stagedVersion = versionIn(File(staging, "python_ext/FreeEnt/version.py"))
            val expectedVersion = asset.name
                .removePrefix(ReleaseInfo.ENGINE_PREFIX).removeSuffix(".zip")
            if (stagedVersion == null) {
                staging.deleteRecursively()
                return Result.failure(IllegalStateException(
                    "The bundle has no engine version file — it does not look like an engine bundle."
                ))
            }
            if (stagedVersion != expectedVersion) {
                staging.deleteRecursively()
                return Result.failure(IllegalStateException(
                    "Bundle says $stagedVersion but the asset is named $expectedVersion."
                ))
            }
            val missing = TREES.firstOrNull { !File(staging, it).isDirectory }
            if (missing != null) {
                staging.deleteRecursively()
                return Result.failure(IllegalStateException("The bundle is missing '$missing/'."))
            }

            onProgress(100, "Installing", stagedVersion)
            swapIn(context, staging)
            staging.deleteRecursively()

            // make the running interpreter notice, then prove it works
            val reload = engine.reloadEngineBlocking()
            val check = engine.smokeTestBlocking()
            if (check != null) {
                rollback(context, engine)   // put the old engine back
                return Result.failure(IllegalStateException("New engine failed its check: $check"))
            }
            Result.success(stagedVersion + if (reload) "" else " (reload pending)")
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
     * Compose the engine version from its component assignments.
     *
     * `version.py` defines `VERSION_STR = f"{MAJOR}.{MINOR}.{PATCH}.{FORK}"`, so
     * reading that line yields the *template*, not the version — the parts have to
     * be read and joined.
     */
    private fun versionIn(versionFile: File): String? {
        if (!versionFile.exists()) return null
        val text = try {
            versionFile.readText()
        } catch (_: Exception) {
            return null
        }
        // MAJOR/MINOR/PATCH are bare ints; FORK is a quoted string, so take
        // whatever follows '=' and strip quotes rather than requiring them.
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
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Int, String, String) -> Unit,
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "freeenterprise-app")
        }
        val md5 = MessageDigest.getInstance("SHA-256")
        try {
            val total = if (expectedSize > 0) expectedSize else connection.contentLengthLong
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        md5.update(buffer, 0, n)
                        copied += n
                        if (total > 0) {
                            val percent = (copied * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
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
        return md5.digest().joinToString("") { "%02x".format(it) }
    }

    /** Zip extraction with a zip-slip guard. */
    private fun unzip(zip: File, destination: File) {
        val destinationPath = destination.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val target = File(destination, entry.name)
                if (!target.canonicalPath.startsWith(destinationPath)) {
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
