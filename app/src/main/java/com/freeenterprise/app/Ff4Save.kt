package com.freeenterprise.app

import java.io.File

/** One character as stored in a save slot. */
data class Ff4Character(
    val id: Int,
    val name: String,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    val mp: Int,
    val maxMp: Int,
)

/** A decoded save slot. Layout documented in FF4_ASMTOC ff4_state.h. */
data class Ff4Slot(
    val index: Int,
    val valid: Boolean,
    val checksumOk: Boolean,
    val leaderName: String?,
    val characters: List<Ff4Character>,
    val gil: Int,
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
    val mapType: Int,
    val world: Int,
    val mapId: Int,
) {
    val playtime: String
        get() = "%d:%02d".format(hours, minutes)

    val mapName: String
        get() = when (mapType) {
            0 -> "Overworld"
            1 -> "Underworld"
            2 -> "Moon"
            else -> "Map $mapId"
        }
}

/**
 * Reads a Final Fantasy II (US) battery save (*.srm) and decodes the four save
 * slots into a readable progress summary. Offsets come from the documented
 * save-block layout ($1000-$17FF per slot, 0x800 bytes):
 *
 *   character n : +0x000 + n*0x40   (id, level, HP/MP)
 *   gil         : +0x6A0 (3 bytes)
 *   playtime    : +0x6A3 frames, +0x6A4 sec, +0x6A5 min, +0x6A6 hour
 *   world       : +0x700 map type, +0x701 world, +0x702 map, +0x703 leader
 *   valid       : +0x7FB, checksum (sum of 0x000-0x7FA) at +0x7FC
 */
object Ff4Save {

    const val SLOT_SIZE = 0x800
    const val SLOT_COUNT = 4

    private val CHARACTER_NAMES = mapOf(
        0x01 to "Cecil", 0x02 to "Kain", 0x03 to "Rydia", 0x04 to "Tellah",
        0x05 to "Edward", 0x06 to "Rosa", 0x07 to "Yang", 0x08 to "Palom",
        0x09 to "Porom", 0x0A to "Tellah", 0x0B to "Cecil", 0x0C to "Tellah",
        0x0D to "Yang", 0x0E to "Cid", 0x0F to "Kain", 0x10 to "Rosa",
        0x11 to "Rydia", 0x12 to "Edge", 0x13 to "FuSoYa", 0x14 to "Kain",
        0x15 to "Golbez", 0x16 to "Anna",
    )

    fun characterName(id: Int): String = CHARACTER_NAMES[id] ?: "??"

    /** Key items occupy item ids 0xE7-0xFA (from FE's item database). */
    private val KEY_ITEM_NAMES = mapOf(
        0xE7 to "Odin", 0xE8 to "Leviathan", 0xE9 to "Asura", 0xEA to "Bahamut",
        0xEB to "Carrot", 0xEC to "Pass", 0xED to "Whistle", 0xEE to "Package",
        0xEF to "Baron Key", 0xF0 to "SandRuby", 0xF1 to "Earth Crystal",
        0xF2 to "Magma Key", 0xF3 to "Luca Key", 0xF4 to "TwinHarp",
        0xF5 to "Dark Crystal", 0xF6 to "Rat Tail", 0xF7 to "Adamant",
        0xF8 to "Pan", 0xF9 to "Pink Tail", 0xFA to "Tower Key",
    )

    /** Key items held in a slot (inventory bag at +0x440, 48 entries x 2 bytes). */
    fun keyItems(data: ByteArray, slotIndex: Int): List<String> {
        val base = slotIndex * SLOT_SIZE
        if (base + SLOT_SIZE > data.size) return emptyList()
        val found = mutableListOf<String>()
        for (k in 0 until 48) {
            val off = base + 0x440 + k * 2
            if (off + 1 >= data.size) break
            val id = data[off].toInt() and 0xFF
            val qty = data[off + 1].toInt() and 0xFF
            if (id in 0xE7..0xFA && qty > 0) {
                found.add(KEY_ITEM_NAMES[id] ?: "0x%02X".format(id))
            }
        }
        return found.distinct()
    }

    /** Decodes every slot present in the file. */
    fun parse(file: File): List<Ff4Slot> {
        if (!file.exists()) return emptyList()
        val data = try {
            file.readBytes()
        } catch (e: Exception) {
            return emptyList()
        }
        return parse(data)
    }

    /** Decodes every slot in a raw SRAM image (live or from disk). */
    fun parse(data: ByteArray): List<Ff4Slot> {
        val slots = mutableListOf<Ff4Slot>()
        for (i in 0 until SLOT_COUNT) {
            val base = i * SLOT_SIZE
            if (base + SLOT_SIZE > data.size) break
            slots.add(parseSlot(data, base, i))
        }
        return slots
    }

    private fun u16(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    private fun parseSlot(d: ByteArray, base: Int, index: Int): Ff4Slot {
        var sum = 0
        for (o in 0 until 0x7FB) sum += d[base + o].toInt() and 0xFF
        val checksumOk = (sum and 0xFFFF) == u16(d, base + 0x7FC)
        val valid = (d[base + 0x7FB].toInt() and 0xFF) != 0

        val characters = mutableListOf<Ff4Character>()
        for (n in 0 until 5) {
            val c = base + n * 0x40
            val id = d[c].toInt() and 0x1F
            if (id == 0) continue
            val level = d[c + 0x02].toInt() and 0xFF
            characters.add(
                Ff4Character(
                    id = id,
                    name = characterName(id),
                    level = level,
                    hp = u16(d, c + 0x07),
                    maxHp = u16(d, c + 0x09),
                    mp = u16(d, c + 0x0B),
                    maxMp = u16(d, c + 0x0D),
                )
            )
        }

        val gil = (d[base + 0x6A0].toInt() and 0xFF) or
            ((d[base + 0x6A1].toInt() and 0xFF) shl 8) or
            ((d[base + 0x6A2].toInt() and 0xFF) shl 16)

        val hours = d[base + 0x6A6].toInt() and 0xFF
        val minutes = d[base + 0x6A5].toInt() and 0xFF
        val seconds = d[base + 0x6A4].toInt() and 0xFF

        val leaderIndex = d[base + 0x703].toInt() and 0xFF
        val leaderChar = characters.getOrNull(leaderIndex)

        return Ff4Slot(
            index = index,
            valid = valid,
            checksumOk = checksumOk,
            leaderName = leaderChar?.name ?: characters.firstOrNull()?.name,
            characters = characters,
            gil = gil,
            hours = hours,
            minutes = minutes,
            seconds = seconds,
            mapType = d[base + 0x700].toInt() and 0xFF,
            world = d[base + 0x701].toInt() and 0xFF,
            mapId = d[base + 0x702].toInt() and 0xFF,
        )
    }

    /** The slot most likely to be the player's current game. */
    fun bestSlot(file: File): Ff4Slot? = pickBest(parse(file))

    fun bestSlot(data: ByteArray): Ff4Slot? = pickBest(parse(data))

    private fun pickBest(slots: List<Ff4Slot>): Ff4Slot? =
        slots.filter { it.valid && it.checksumOk && it.characters.isNotEmpty() }
            .maxByOrNull { it.hours * 3600 + it.minutes * 60 + it.seconds }
            ?: slots.firstOrNull { it.characters.isNotEmpty() }

    /**
     * Progress summary for the session list: how many of FE's checks are open
     * (per the treasure switches) and how many key items are held.
     */
    fun summary(context: android.content.Context, file: File): String? {
        val data = try {
            file.readBytes()
        } catch (e: Exception) {
            return null
        }
        return summary(context, data)
    }

    fun summary(context: android.content.Context, data: ByteArray): String? {
        val slot = pickBest(parse(data)) ?: return null
        val checks = CheckDb.load(context)
        val switchBase = slot.index * SLOT_SIZE + 0x2A0
        var opened = 0
        for (c in checks) {
            if (isCheckedAt(data, switchBase, c.flag)) opened++
        }
        val items = keyItems(data, slot.index).size
        val verified = if (slot.checksumOk) "" else "  (unverified)"
        return "Checks $opened / ${checks.size}  \u00b7  $items key items$verified"
    }

    private fun isCheckedAt(data: ByteArray, base: Int, flag: Int): Boolean {
        val off = base + (flag shr 3)
        if (off < 0 || off >= data.size) return false
        return ((data[off].toInt() shr (flag and 7)) and 1) == 1
    }
}