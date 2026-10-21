package com.freeenterprise.desktop

import com.freeenterprise.ff4.Ff4Spoiler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The metadata the app stores beside each seed, written with the same file name and
 * the same keys so a library folder can be shared between the two:
 *
 *   <name>.smc          the ROM
 *   <name>.json         this
 *   <name>.srm          battery save
 *   <name>.state        emulator state
 *   <name>.spoiler.txt  the private spoiler (locations for the tracker)
 *
 * The desktop only needs a subset, but it writes what it knows rather than a
 * private format, so nothing has to be converted if a folder moves between them.
 */
object SeedMeta {

    data class Meta(
        val name: String,
        val seed: String,
        val flags: String,
        val binaryFlags: String,
        val verification: List<String>,
        val version: String,
        val seconds: Double,
        val created: Long,
        val keyItems: List<Pair<String, String>>,
    )

    /** `<name>.json`, beside the ROM. */
    fun fileFor(rom: File): File = File(rom.parentFile, rom.nameWithoutExtension + ".json")

    fun write(rom: File, meta: Meta, spoilerText: String) {
        val json = JSONObject().apply {
            put("name", meta.name)
            put("seed", meta.seed)
            put("flags", meta.flags)
            put("binary_flags", meta.binaryFlags)
            put("verification", JSONArray().apply { meta.verification.forEach { put(it) } })
            put("version", meta.version)
            put("seconds", meta.seconds)
            put("created", meta.created)
            put("last_played", meta.created)
            put("save_name", rom.nameWithoutExtension + ".srm")
            if (meta.keyItems.isNotEmpty()) {
                put("key_items", JSONArray().apply {
                    for ((name, location) in meta.keyItems) {
                        put(JSONObject().apply { put("name", name); put("location", location) })
                    }
                })
            }
        }
        runCatching { fileFor(rom).writeText(json.toString(2)) }
        if (spoilerText.isNotBlank()) {
            runCatching { File(rom.parentFile, rom.nameWithoutExtension + ".spoiler.txt").writeText(spoilerText) }
        }
    }

    /** Reads the key-item locations a seed recorded; empty when there is no metadata. */
    fun keyItems(rom: File): List<Pair<String, String>> {
        val file = fileFor(rom)
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONObject(file.readText()).optJSONArray("key_items") ?: return emptyList()
            (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                entry.optString("name") to entry.optString("location")
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** The parts of the metadata the session list shows. */
    data class Summary(
        val name: String,
        val seed: String,
        val flags: String,
        val verification: List<String>,
        val lastPlayed: Long,
    )

    fun summary(rom: File): Summary {
        val file = fileFor(rom)
        if (!file.isFile) {
            return Summary(rom.nameWithoutExtension, "", "", emptyList(), rom.lastModified())
        }
        return try {
            val o = JSONObject(file.readText())
            val verification = o.optJSONArray("verification")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
            val seed = o.optString("seed")
            val recorded = o.optString("name")
            Summary(
                // Older seeds recorded the file name as their name; the seed is the
                // identity, so prefer it when the recorded name looks like a file name.
                name = when {
                    recorded.isNotBlank() && !recorded.startsWith("FF4FE") -> recorded
                    seed.isNotBlank() -> seed
                    else -> rom.nameWithoutExtension
                },
                seed = seed,
                flags = o.optString("flags"),
                verification = verification,
                lastPlayed = o.optLong("last_played", o.optLong("created", rom.lastModified())),
            )
        } catch (t: Throwable) {
            Summary(rom.nameWithoutExtension, "", "", emptyList(), rom.lastModified())
        }
    }

    /** Record that a seed was played, as the app does. */
    fun touchLastPlayed(rom: File) {
        val file = fileFor(rom)
        if (!file.isFile) return
        runCatching {
            val o = JSONObject(file.readText())
            o.put("last_played", System.currentTimeMillis())
            file.writeText(o.toString(2))
        }
    }

    /** The key items this seed has been seen to hold. */
    fun seenItems(rom: File): Set<String> {
        val file = fileFor(rom)
        if (!file.isFile) return emptySet()
        return try {
            val array = JSONObject(file.readText()).optJSONArray("seen_key_items") ?: return emptySet()
            (0 until array.length()).map { array.optString(it) }.toSet()
        } catch (t: Throwable) {
            emptySet()
        }
    }

    /** Adds [items] to the seed's remembered set; returns true when it changed. */
    fun addSeenItems(rom: File, items: Collection<String>): Boolean {
        val file = fileFor(rom)
        if (!file.isFile || items.isEmpty()) return false
        return try {
            val json = JSONObject(file.readText())
            val existing = json.optJSONArray("seen_key_items")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                ?.toMutableSet() ?: mutableSetOf()
            val before = existing.size
            existing.addAll(items)
            if (existing.size == before) return false
            json.put("seen_key_items", JSONArray().apply { existing.forEach { put(it) } })
            file.writeText(json.toString(2))
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** The flags string the seed was generated with, if it was recorded. */
    fun flags(rom: File): String {
        val file = fileFor(rom)
        if (!file.isFile) return ""
        return try {
            JSONObject(file.readText()).optString("flags")
        } catch (t: Throwable) {
            ""
        }
    }
}
