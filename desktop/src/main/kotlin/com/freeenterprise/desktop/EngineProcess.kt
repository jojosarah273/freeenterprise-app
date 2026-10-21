package com.freeenterprise.desktop

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
