package com.freeenterprise.ff4

/**
 * The parts of the engine's private spoiler the interface needs.
 *
 * The tracker shows *where* a key-item check is, and whether it has been done - so
 * it needs the location list, and nothing else from the spoiler. Only the location
 * is ever displayed; which item sits there stays in the spoiler.
 *
 * Ported from the app's own parser, including the bug it once had: the section pads
 * names with dots, and the run of dots *shrinks as names get longer*, down to two
 * (`Darkness Crystal .. Sealed Cave item`). Requiring three or more truncated the
 * list from eighteen entries to nine.
 */
object Ff4Spoiler {

    private const val KEY_ITEM_HEADER = "KEY ITEM LOCATIONS"

    /** "Item ........... location" lines under the key-item header. */
    fun keyItemLocations(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val entry = Regex("^(.+?)\\s+\\.+\\s+(.+)$")

        var inSection = false
        var started = false
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r', ' ')

            if (!inSection) {
                if (line.trimStart().startsWith(KEY_ITEM_HEADER)) inSection = true
                continue
            }
            if (line.isBlank()) {
                // The header is followed by a blank line, so a blank line only ends
                // the section once entries have been read.
                if (started) break else continue
            }

            val match = entry.find(line) ?: break   // anything else ends the section
            out.add(match.groupValues[1].trim() to match.groupValues[2].trim())
            started = true
        }
        return out
    }

    /**
     * True when this location is the seed's free starting item. The engine prints a
     * "Starting item" row only when the item is handed over at the start, so the row
     * *is* the rule - and it cannot be read from the bag, since the Package is
     * delivered away minutes later.
     */
    fun isStartingItem(location: String): Boolean =
        location.lowercase().replace(" ", "").replace("_", "") == "startingitem"

    /** The locations the tracker shows: placeholders like "(not available)" dropped. */
    fun usableKeyItemLocations(text: String): List<Pair<String, String>> =
        keyItemLocations(text).filterNot { (_, place) ->
            place.contains("not available", ignoreCase = true)
        }
}
