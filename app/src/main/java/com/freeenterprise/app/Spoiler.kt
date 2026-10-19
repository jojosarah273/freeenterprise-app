package com.freeenterprise.app

import java.io.File

/**
 * Reads the seed's saved private spoiler (written beside the ROM as
 * `<name>.spoiler.txt`). Only the parts the tracker needs are parsed — the file
 * is otherwise the full spoiler, so it doubles as a "view spoiler" artefact.
 */
object Spoiler {

    private const val KEY_ITEM_HEADER = "KEY ITEM LOCATIONS"

    /** "Name ....... location" lines under the key-item header. */
    fun keyItemLocations(file: File): List<Pair<String, String>> {
        if (!file.exists()) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val line = Regex("^(.+?)\\s*\\.{3,}\\s*(.+)$")
        try {
            var inSection = false
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val text = raw.trimEnd('\r', ' ')
                    if (!inSection) {
                        if (text.trimStart().startsWith(KEY_ITEM_HEADER)) inSection = true
                        continue
                    }
                    if (text.isBlank()) break          // the section ends at a blank line
                    val m = line.find(text) ?: continue
                    out.add(m.groupValues[1].trim() to m.groupValues[2].trim())
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}
