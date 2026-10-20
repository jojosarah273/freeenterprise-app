package com.freeenterprise.ff4

/**
 * The engine's own key-item tracker: seventeen items, where item *i* owns bit *i*
 * of `Tracker__KeyItemFoundFlags` at WRAM $7E1500.
 *
 * The order and the item codes come from `FreeEnt/scripts/tracker.f4c`
 * (`Tracker__KeyItemCodes`), and four of them are not items from the usual key-item
 * range at all: the engine borrows a sword ($19 Legend), a weapon ($3E Spoon), a
 * consumable ($C8 Crystal) and the dummy slot ($FC Hook). Anything that looks for
 * key items by scanning the item bag for $E7-$FA therefore cannot see those four.
 *
 * This table is shared knowledge -- the app's tracker, the ff4dump harness and the
 * desktop build all need it -- so it lives here rather than in three places.
 */
object Ff4KeyItems {

    data class Entry(
        /** Bit position in `Tracker__KeyItemFoundFlags`. */
        val index: Int,
        /** The engine's own name for the item. */
        val name: String,
        /** The name the spoiler prints. */
        val label: String,
        /** The item id the engine uses for it. */
        val itemCode: Int,
    )

    val entries: List<Entry> = listOf(
        Entry(0, "package", "Package", 0xEE),
        Entry(1, "sandruby", "SandRuby", 0xF0),
        Entry(2, "legend_sword", "Legend Sword", 0x19),
        Entry(3, "baron_key", "Baron Key", 0xEF),
        Entry(4, "twinharp", "TwinHarp", 0xF4),
        Entry(5, "earth_crystal", "Earth Crystal", 0xF1),
        Entry(6, "magma_key", "Magma Key", 0xF2),
        Entry(7, "tower_key", "Tower Key", 0xFA),
        Entry(8, "hook", "Hook", 0xFC),
        Entry(9, "luca_key", "Luca Key", 0xF3),
        Entry(10, "dark_crystal", "Darkness Crystal", 0xF5),
        Entry(11, "rat_tail", "Rat Tail", 0xF6),
        Entry(12, "adamant", "Adamant", 0xF7),
        Entry(13, "pan", "Pan", 0xF8),
        Entry(14, "spoon", "Spoon", 0x3E),
        Entry(15, "pink_tail", "Pink Tail", 0xF9),
        Entry(16, "crystal", "Crystal", 0xC8),
    )

    val count: Int get() = entries.size

    /**
     * Loose name matching, because the spoiler prints display names ("Legend Sword",
     * "Darkness Crystal") while the item table uses short ones.
     */
    fun normalise(name: CharSequence): String {
        val collapsed = name.toString().lowercase().replace(" ", "").replace("_", "")
        return if (collapsed == "darknesscrystal") "darkcrystal" else collapsed
    }

    private val byNormalisedName: Map<String, Entry> =
        entries.associateBy { normalise(it.name) } + entries.associateBy { normalise(it.label) }

    /** The entry for a spoiler name, or null for something the engine does not track. */
    fun entryFor(name: CharSequence): Entry? = byNormalisedName[normalise(name)]

    /**
     * Whether item [index] has been found, read from the engine's flag bits.
     * `Tracker__LocateBit` uses byte = index >> 3 and bit = index & 7, least
     * significant first, and the array starts at offset 0x500 of the $7E1000 window.
     */
    fun isFound(wram: ByteArray, index: Int, foundOffset: Int = FOUND_OFFSET): Boolean {
        val offset = foundOffset + (index shr 3)
        if (index !in entries.indices || offset >= wram.size) return false
        return (wram[offset].toInt() and (1 shl (index and 7))) != 0
    }

    /** How many of the seventeen the engine says have been found. */
    fun foundCount(wram: ByteArray, foundOffset: Int = FOUND_OFFSET): Int =
        entries.count { isFound(wram, it.index, foundOffset) }

    /** Offset of `Tracker__KeyItemFoundFlags` within the $7E1000 window. */
    const val FOUND_OFFSET = 0x500

    /** Offset of the treasure switch array within the same window. */
    const val SWITCHES_OFFSET = 0x2A0

    const val SWITCH_BITS = 512

    /** How many treasure switches are set; the app's checks page counts the same bits. */
    fun switchesSet(wram: ByteArray, offset: Int = SWITCHES_OFFSET): Int {
        var n = 0
        for (bit in 0 until SWITCH_BITS) {
            val byteIndex = offset + (bit shr 3)
            if (byteIndex >= wram.size) break
            if ((wram[byteIndex].toInt() and (1 shl (bit and 7))) != 0) n++
        }
        return n
    }
}
