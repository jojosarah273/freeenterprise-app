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
class SeedLibrary(private val dir: File, private val baseRom: File? = null) {

    data class Seed(val rom: File, val name: String, val detail: String)

    /** Names the base ROM is commonly given; never listed as a seed. */
    private val baseRomNames = setOf("ff2.smc", "ff2.sfc", "base_rom.smc", "base_rom.sfc")

    fun seeds(): List<Seed> = dir.listFiles { f -> f.isFile && f.name.endsWith(".smc") }
        .orEmpty()
        .filterNot { it.name.lowercase() in baseRomNames }
        .filterNot { baseRom != null && it.absolutePath == baseRom.absolutePath }
        .sortedByDescending { it.lastModified() }
        .map { rom ->
            val parts = rom.nameWithoutExtension.split(".")
            val seed = parts.getOrNull(1) ?: rom.nameWithoutExtension
            val flags = parts.getOrNull(2).orEmpty()
            Seed(rom, seed, if (flags.isEmpty()) rom.name else "flags ${flags.take(12)}…")
        }

    fun ensureExists(): File = dir.apply { mkdirs() }
}
