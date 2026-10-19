package com.freeenterprise.app

import java.io.File

/**
 * Reads the "KEY ITEM LOCATIONS" section of a seed's private spoiler.
 *
 * The tracker needs to know which checks hold key items, and that cannot come
 * from live memory: Free Enterprise randomizes key-item placement, so the
 * placement is seed data. At generation time the section is parsed and kept in
 * the seed's metadata (name -> location); the tracker displays only the location
 * and whether it is done, never which item is there.
 *
 * A `<name>.spoiler.txt` beside the ROM is still honoured if present, so a
 * spoiler downloaded from the website also works.
 */
object Spoiler {

    private const val KEY_ITEM_HEADER = "KEY ITEM LOCATIONS"

    /** Parses a spoiler file, if it exists. */
    fun keyItemLocations(file: File): List<Pair<String, String>> {
        if (!file.exists()) return emptyList()
        return try {
            keyItemLocations(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Parses the key-item section out of spoiler text. */
    fun keyItemLocations(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val entry = Regex("^(.+?)\\s*\\.{3,}\\s*(.+)$")
        try {
            var inSection = false
            var started = false

            for (raw in text.lineSequence()) {
                val trimmed = raw.trimEnd('\r', ' ')

                if (!inSection) {
                    if (trimmed.trimStart().startsWith(KEY_ITEM_HEADER)) inSection = true
                    continue
                }

                if (trimmed.isBlank()) {
                    // The header is followed by a blank line, so a blank line only
                    // ends the section once entries have actually been read.
                    if (started) break else continue
                }

                val match = entry.find(trimmed) ?: break   // anything else ends it
                out.add(match.groupValues[1].trim() to match.groupValues[2].trim())
                started = true
            }
        } catch (_: Exception) {
        }
        return out
    }
}
