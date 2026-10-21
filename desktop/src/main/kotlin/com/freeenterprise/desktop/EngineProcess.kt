package com.freeenterprise.desktop

import com.freeenterprise.ff4.Ff4Spoiler
import org.json.JSONObject
import java.io.File

/**
 * Runs the randomizer engine as a child process.
 *
 * The engine is the same Python the Android app calls through Chaquopy; on the
 * desktop it is simply `python3 fe_bridge.py`, whose CLI was added for exactly this.
 * Progress comes from the file the bridge writes, which is the same mechanism the
 * app polls, so the interface shows real phases rather than a guess.
 */
class EngineProcess(
    private val engineDir: File,
    private val bridgeFile: File,
    private val baseRom: File,
    private val cacheDir: File,
) {
    data class Progress(val percent: Int, val stage: String, val detail: String)

    data class Result(
        val rom: File,
        val seed: String,
        val flags: String,
        val verification: String,
        val seconds: Double,
        /** The private spoiler, kept beside the ROM: the tracker reads locations from it. */
        val spoiler: File?,
    )

    class EngineError(message: String) : Exception(message)

    /**
     * Generates a seed into [outDir]. [onProgress] is called as the bridge reports
     * phases, and the returned result carries what the interface wants to show.
     */
    fun generate(
        seed: String,
        flags: String,
        outDir: File,
        progressFile: File,
        onProgress: (Progress) -> Unit,
    ): Result {
        outDir.mkdirs()
        cacheDir.mkdirs()
        progressFile.delete()

        val command = listOf(
            python(),
            bridgeFile.absolutePath,
            "--ext", engineDir.absolutePath,
            "generate",
            "--flags", flags,
            "--seed", seed,
            "--rom", baseRom.absolutePath,
            "--out", outDir.absolutePath,
            "--cache", cacheDir.absolutePath,
            "--progress", progressFile.absolutePath,
        )

        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()

        // Poll the progress file while the engine works; the bridge only writes on
        // change, so this is cheap.
        val stderr = StringBuilder()
        val errorReader = Thread {
            process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) }
        }
        errorReader.isDaemon = true
        errorReader.start()

        var last = ""
        while (process.isAlive) {
            val text = progressFile.takeIf { it.isFile }?.readText().orEmpty()
            if (text.isNotEmpty() && text != last) {
                last = text
                onProgress(parseProgress(text))
            }
            Thread.sleep(120)
        }
        val output = process.inputStream.bufferedReader().readText()
        errorReader.join(500)

        if (output.isBlank()) {
            throw EngineError("the engine produced no output. ${stderr.toString().trim().takeLast(400)}")
        }
        val json = try {
            JSONObject(output.trim().lines().last())
        } catch (e: Exception) {
            throw EngineError("could not read the engine's reply: ${output.takeLast(300)}")
        }
        if (json.optString("status") != "ok") {
            throw EngineError(json.optString("error").ifBlank { "generation failed" })
        }
        val path = json.optString("path").ifBlank { json.optString("file_name") }
        val rom = File(path)
        if (!rom.isFile) throw EngineError("the engine reported success but wrote no ROM: $path")

        // Keep the spoiler next to the ROM. The app does the same, and the tracker
        // needs it to say *where* a check is without saying which item is there.
        val spoilerText = json.optString("private_spoiler")
        val spoiler = if (spoilerText.isNotBlank()) {
            File(rom.parentFile, rom.nameWithoutExtension + ".spoiler.txt").also {
                runCatching { it.writeText(spoilerText) }
            }
        } else {
            null
        }

        // The same metadata file the app writes, with the same keys, so a library
        // folder is interchangeable between the two.
        val verification = json.optJSONArray("verification")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
        SeedMeta.write(
            rom,
            SeedMeta.Meta(
                name = rom.nameWithoutExtension,
                seed = json.optString("seed", seed),
                flags = json.optString("flags", flags),
                binaryFlags = json.optString("binary_flags"),
                verification = verification,
                version = json.optString("version"),
                seconds = json.optDouble("seconds", 0.0),
                created = System.currentTimeMillis(),
                keyItems = Ff4Spoiler.usableKeyItemLocations(spoilerText),
            ),
            spoilerText,
        )

        return Result(
            rom = rom,
            seed = json.optString("seed", seed),
            flags = json.optString("flags", flags),
            verification = json.optJSONArray("verification")
                ?.let { arr -> (0 until arr.length()).joinToString(" ") { arr.optString(it) } }
                .orEmpty(),
            seconds = json.optDouble("seconds", 0.0),
            spoiler = spoiler,
        )
    }

    /**
     * The engine's version string, e.g. "4.7.0.Gale". The app shows this in its main
     * menu, so the desktop does too - it is how you know which engine you are on.
     */
    fun version(): String {
        val command = listOf(python(), bridgeFile.absolutePath, "--ext", engineDir.absolutePath, "version")
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            JSONObject(output.trim().lines().last()).optString("version")
        } catch (t: Throwable) {
            ""
        }
    }

    data class Check(
        val canonical: String,
        val binary: String,
        val valid: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
    )

    /**
     * Canonicalise a flag list and run the engine's logic checks - the same call the
     * app's editor makes after every change, so the desktop shows the same readable
     * flagset and the same errors.
     */
    fun checkFlags(flags: String): Check {
        val command = listOf(
            python(),
            bridgeFile.absolutePath,
            "--ext", engineDir.absolutePath,
            "check",
            "--flags", flags,
        )
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw EngineError("the engine did not answer within 90s")
        }
        val json = try {
            JSONObject(output.trim().lines().last())
        } catch (e: Exception) {
            throw EngineError("could not read the engine's reply: ${output.takeLast(200)} ${stderr.takeLast(200)}")
        }
        if (json.optString("status") != "ok") {
            throw EngineError(json.optString("error").ifBlank { "the engine rejected the flags" })
        }
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        json.optJSONArray("log")?.let { log ->
            for (i in 0 until log.length()) {
                val entry = log.getJSONObject(i)
                val message = entry.optString("message")
                when (entry.optString("severity")) {
                    "error" -> errors.add(message)
                    "warning" -> warnings.add(message)
                }
            }
        }
        return Check(
            canonical = json.optString("flags"),
            binary = json.optString("binary_flags"),
            valid = json.optBoolean("valid", errors.isEmpty()),
            errors = errors,
            warnings = warnings,
        )
    }

    private fun parseProgress(text: String): Progress {
        val lines = text.lines()
        return Progress(
            percent = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: 0,
            stage = lines.getOrNull(1)?.trim().orEmpty(),
            detail = lines.getOrNull(2)?.trim().orEmpty(),
        )
    }

    private fun python(): String {
        for (candidate in listOf("python3", "python")) {
            try {
                val probe = ProcessBuilder(candidate, "--version").redirectErrorStream(true).start()
                probe.waitFor()
                if (probe.exitValue() == 0) return candidate
            } catch (_: Exception) {
                // try the next one
            }
        }
        throw EngineError("no python3 on PATH; the engine needs it to generate seeds")
    }
}
