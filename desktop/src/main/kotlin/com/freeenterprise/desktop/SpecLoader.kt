package com.freeenterprise.desktop

import com.freeenterprise.ff4.Ff4FlagControl
import com.freeenterprise.ff4.Ff4FlagEditor
import com.freeenterprise.ff4.Ff4FlagSection
import org.json.JSONArray
import java.io.File

/**
 * Reads the generated data files into the portable core's model.
 *
 * The core owns the shapes and the rules; this file only translates JSON, because
 * the Android build reads the same files with its own JSON library and both must
 * end up with identical models.
 */
object SpecLoader {

    /** `ui_spec.json`: the flag editor's structure. */
    fun uiSpec(file: File?): List<Ff4FlagSection> {
        if (file == null || !file.isFile) return emptyList()
        return try {
            val sections = mutableListOf<Ff4FlagSection>()
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val title = o.getString("title")
                // The DOORS section is entirely the hidden feature.
                if (!Ff4FlagEditor.DOORS_ENABLED && title.equals("DOORS", ignoreCase = true)) continue
                val controls = controls(o.optJSONArray("controls") ?: JSONArray())
                if (controls.isEmpty()) continue
                sections.add(Ff4FlagSection(title, controls))
            }
            sections
        } catch (t: Throwable) {
            System.err.println("could not read ${file.path}: ${t.message}")
            emptyList()
        }
    }

    private fun controls(array: JSONArray): List<Ff4FlagControl> {
        val out = mutableListOf<Ff4FlagControl>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            if (Ff4FlagEditor.isHidden(o.optString("flag", ""))) continue
            out.add(control(o))
        }
        return out
    }

    private fun control(o: org.json.JSONObject): Ff4FlagControl {
        val subs = o.optJSONArray("subcontrols")
        return Ff4FlagControl(
            flag = o.optString("flag", ""),
            title = o.optString("title", ""),
            type = o.optString("type", "toggle"),
            hard = o.optBoolean("hard", false),
            compact = o.optBoolean("compact", false),
            nullFlag = o.optBoolean("null", false),
            important = o.optBoolean("important", false),
            description = if (o.has("description") && !o.isNull("description")) o.getString("description") else null,
            subcontrols = if (subs != null) controls(subs) else emptyList(),
        )
    }

    data class Preset(val name: String, val flags: String, val description: String)
    data class PresetCategory(val name: String, val presets: List<Preset>)

    /** `presets.json`: the same presets the website serves. */
    fun presets(file: File?): List<PresetCategory> {
        if (file == null || !file.isFile) return emptyList()
        return try {
            val out = mutableListOf<PresetCategory>()
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                if (!o.has("presets")) continue
                val list = mutableListOf<Preset>()
                val ps = o.getJSONArray("presets")
                for (j in 0 until ps.length()) {
                    val p = ps.getJSONObject(j)
                    val flags = p.getString("flags")
                    // Presets using the hidden feature are left out, as in the app.
                    if (Ff4FlagEditor.usesHiddenFeature(flags)) continue
                    list.add(Preset(p.getString("name"), flags, p.optString("description")))
                }
                if (list.isNotEmpty()) out.add(PresetCategory(o.getString("category"), list))
            }
            out
        } catch (t: Throwable) {
            System.err.println("could not read ${file.path}: ${t.message}")
            emptyList()
        }
    }
}

/**
 * Named flagsets, stored as `flagsets.json` beside the seeds so a ruleset travels
 * with the library - the same file and shape the Android build uses.
 */
class FlagsetStore(private val dir: File) {

    data class Flagset(val name: String, val flags: String, val created: Long)

    private val file: File get() = File(dir, "flagsets.json")

    fun list(): List<Flagset> {
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Flagset(o.optString("name"), o.optString("flags"), o.optLong("created"))
            }.sortedByDescending { it.created }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Saves, replacing any set with the same name (case-insensitive). */
    fun save(name: String, flags: String) {
        val cleaned = name.trim().ifBlank { "Flagset" }
        val others = list().filterNot { it.name.equals(cleaned, ignoreCase = true) }
        write(listOf(Flagset(cleaned, flags, System.currentTimeMillis())) + others)
    }

    fun delete(name: String) = write(list().filterNot { it.name == name })

    private fun write(sets: List<Flagset>) {
        try {
            dir.mkdirs()
            val array = JSONArray()
            for (set in sets) {
                array.put(
                    org.json.JSONObject().apply {
                        put("name", set.name)
                        put("flags", set.flags)
                        put("created", set.created)
                    },
                )
            }
            file.writeText(array.toString(2))
        } catch (t: Throwable) {
            System.err.println("could not write ${file.path}: ${t.message}")
        }
    }
}
