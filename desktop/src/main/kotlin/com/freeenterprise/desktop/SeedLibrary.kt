package com.freeenterprise.desktop

import java.io.File

/**
 * The seeds on disk.
 *
 * A seed is a ROM whose name already carries its identity: the engine writes
 * `FF4FE.<seed>.<binary flags>.smc`, so the list needs no sidecar file to be
 * useful. The app's metadata JSON (flags in readable form, verification, key item
 * locations) is not read yet; that belongs with the rest of the shared metadata
 * once it moves into the core.
 */
class SeedLibrary(private val dir: File) {

    data class Seed(val rom: File, val name: String, val detail: String)

    fun seeds(): List<Seed> = dir.listFiles { f -> f.isFile && f.name.endsWith(".smc") }
        .orEmpty()
        .sortedByDescending { it.lastModified() }
        .map { rom ->
            val parts = rom.nameWithoutExtension.split(".")
            val seed = parts.getOrNull(1) ?: rom.nameWithoutExtension
            val flags = parts.getOrNull(2).orEmpty()
            Seed(rom, seed, if (flags.isEmpty()) rom.name else "flags ${flags.take(12)}…")
        }

    fun ensureExists(): File = dir.apply { mkdirs() }
}
