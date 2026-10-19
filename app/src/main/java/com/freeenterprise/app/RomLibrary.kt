package com.freeenterprise.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Metadata stored beside each generated ROM as `<name>.json`. */
data class SeedMeta(
    val name: String,
    val seed: String,
    val flags: String,
    val binaryFlags: String,
    val verification: List<String>,
    val version: String,
    val seconds: Double,
    val created: Long,
    val lastPlayed: Long = 0L,
    /** File name of this session's battery save, kept in FF4_FE beside the ROM. */
    val saveName: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("seed", seed)
        put("flags", flags)
        put("binary_flags", binaryFlags)
        put("verification", JSONArray(verification))
        put("version", version)
        put("seconds", seconds)
        put("created", created)
        put("last_played", lastPlayed)
        if (saveName != null) put("save_name", saveName)
    }

    companion object {
        fun fromJson(o: JSONObject): SeedMeta {
            val ver = mutableListOf<String>()
            val v = o.optJSONArray("verification")
            if (v != null) for (i in 0 until v.length()) ver.add(v.getString(i))
            return SeedMeta(
                name = o.optString("name"),
                seed = o.optString("seed"),
                flags = o.optString("flags"),
                binaryFlags = o.optString("binary_flags"),
                verification = ver,
                version = o.optString("version"),
                seconds = o.optDouble("seconds", 0.0),
                created = o.optLong("created"),
                lastPlayed = o.optLong("last_played"),
                saveName = if (o.has("save_name")) o.optString("save_name") else null,
            )
        }

        /** For ROMs dropped into the folder without a sidecar. */
        fun guessed(rom: File): SeedMeta {
            val parts = rom.nameWithoutExtension.split('.')
            val seed = if (parts.size >= 2 && parts[0].equals("FF4FE", ignoreCase = true)) {
                parts[1]
            } else {
                rom.nameWithoutExtension
            }
            return SeedMeta(
                name = rom.nameWithoutExtension,
                seed = seed,
                flags = "",
                binaryFlags = "",
                verification = emptyList(),
                version = "",
                seconds = 0.0,
                created = rom.lastModified(),
            )
        }
    }
}

/**
 * The on-device ROM library: /storage/emulated/0/FF4_FE (root of shared
 * storage), created and written directly with all-files access. Falls back to
 * the app's external files dir when access hasn't been granted yet.
 */
object RomLibrary {
    const val FOLDER_NAME = "FF4_FE"

    fun rootFolder(): File = File(Environment.getExternalStorageDirectory(), FOLDER_NAME)

    private fun fallbackFolder(context: Context): File =
        File(context.getExternalFilesDir(null), FOLDER_NAME)

    fun hasRootAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    fun folder(context: Context): File =
        if (hasRootAccess(context)) rootFolder() else fallbackFolder(context)

    fun usingFallback(context: Context): Boolean = !hasRootAccess(context)

    fun ensureFolder(context: Context): File {
        val f = folder(context)
        f.mkdirs()
        return f
    }

    /** Intent that opens the "All files access" settings page (API 30+). */
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    fun listRoms(context: Context): List<File> =
        ensureFolder(context).listFiles { f ->
            f.isFile && (f.extension.equals("smc", true) || f.extension.equals("sfc", true))
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun metaFile(rom: File): File =
        File(rom.parentFile, rom.nameWithoutExtension + ".json")

    fun readMeta(rom: File): SeedMeta {
        val mf = metaFile(rom)
        if (!mf.exists()) return SeedMeta.guessed(rom)
        return try {
            SeedMeta.fromJson(JSONObject(mf.readText()))
        } catch (e: Exception) {
            SeedMeta.guessed(rom)
        }
    }

    fun writeMeta(rom: File, meta: SeedMeta) {
        try {
            val stamped = meta.copy(saveName = rom.nameWithoutExtension + ".srm")
            metaFile(rom).writeText(stamped.toJson().toString(2))
        } catch (_: Exception) {
        }
    }

    fun delete(rom: File) {
        rom.delete()
        metaFile(rom).delete()
        srmFile(rom).delete()
        stateFile(rom).delete()
        spoilerFile(rom).delete()
    }

    // ------------------------------------------------------------- save files

    /**
     * This session's battery save, living beside the ROM in FF4_FE. The sidecar
     * metadata is the authoritative link; the naming convention is the fallback
     * (and wins if the metadata's file is missing while the convention exists).
     */
    fun srmFile(rom: File): File {
        val convention = File(rom.parentFile, rom.nameWithoutExtension + ".srm")
        val linked = readMeta(rom).saveName?.takeIf { it.isNotBlank() }?.let { File(rom.parentFile, it) }
        return when {
            linked == null -> convention
            linked.exists() -> linked
            convention.exists() -> convention
            else -> linked
        }
    }

    fun hasSave(rom: File): Boolean = srmFile(rom).exists()

    /** Save state for this session, kept beside the ROM (single slot). */
    fun stateFile(rom: File): File = File(rom.parentFile, rom.nameWithoutExtension + ".state")

    /** The seed's private spoiler, kept beside the ROM (the tracker reads it). */
    fun spoilerFile(rom: File): File = File(rom.parentFile, rom.nameWithoutExtension + ".spoiler.txt")

    fun deleteSave(rom: File) {
        srmFile(rom).delete()
    }

    fun touchLastPlayed(rom: File) {
        try {
            writeMeta(rom, readMeta(rom).copy(lastPlayed = System.currentTimeMillis()))
        } catch (_: Exception) {
        }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9 _\\-]"), "_").trim()
        return cleaned.ifBlank { "seed" }.take(48)
    }

    /**
     * Renames a session: the ROM, its `.srm` save and its `.json` sidecar all
     * move together, so the seed keeps its save attached.
     */
    fun rename(rom: File, newName: String): File {
        val base = sanitize(newName)
        val parent = rom.parentFile
        val currentBase = rom.nameWithoutExtension

        fun baseTaken(b: String): Boolean =
            b != currentBase && (File(parent, "$b.smc").exists() || File(parent, "$b.srm").exists())

        var newBase = base
        var i = 2
        while (baseTaken(newBase)) {
            newBase = "$base ($i)"
            i++
        }

        val oldMeta = readMeta(rom)
        val oldSrm = srmFile(rom)
        val oldMetaFile = metaFile(rom)
        val target = File(parent, "$newBase.smc")

        if (target.absolutePath == rom.absolutePath && oldSrm.name == "$newBase.srm") {
            writeMeta(rom, oldMeta.copy(name = newBase))
            return rom
        }

        val oldState = stateFile(rom)
        val oldSpoiler = spoilerFile(rom)
        if (!rom.renameTo(target)) return rom
        if (oldSrm.exists()) oldSrm.renameTo(File(parent, "$newBase.srm"))
        if (oldState.exists()) oldState.renameTo(File(parent, "$newBase.state"))
        if (oldSpoiler.exists()) oldSpoiler.renameTo(File(parent, "$newBase.spoiler.txt"))
        oldMetaFile.delete()
        writeMeta(target, oldMeta.copy(name = newBase))
        return target
    }

    /** Writes a generated ROM + metadata into the library. Returns the ROM file. */
    fun save(context: Context, source: File, meta: SeedMeta): File {
        ensureFolder(context)
        var target = File(folder(context), "${sanitize(meta.name)}.smc")
        var i = 2
        while (target.exists() || File(folder(context), target.nameWithoutExtension + ".srm").exists()) {
            target = File(folder(context), "${sanitize(meta.name)} ($i).smc")
            i++
        }
        source.copyTo(target, overwrite = false)
        writeMeta(target, meta.copy(name = target.nameWithoutExtension))
        return target
    }
}