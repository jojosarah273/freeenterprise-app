package com.freeenterprise.app

import android.content.Context
import org.json.JSONArray

/** Preset library (the same presets.json the website serves). */
object Presets {
    data class Preset(val name: String, val flags: String, val description: String)
    data class Category(val name: String, val presets: List<Preset>)

    private var cached: List<Category>? = null

    fun load(context: Context): List<Category> {
        cached?.let { return it }
        val result = mutableListOf<Category>()
        try {
            val raw = context.assets.open("presets/presets.json")
                .use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.has("presets")) continue
                val list = mutableListOf<Preset>()
                val ps = o.getJSONArray("presets")
                for (j in 0 until ps.length()) {
                    val p = ps.getJSONObject(j)
                    val flags = p.getString("flags")
                    // Skip presets that use doors/entrances randomization while
                    // that feature is hidden in the editor (see UiSpec).
                    if (!UiSpec.DOORS_ENABLED &&
                        Regex("(doors|entrances)rando", RegexOption.IGNORE_CASE).containsMatchIn(flags)
                    ) {
                        continue
                    }
                    list.add(
                        Preset(
                            name = p.getString("name"),
                            flags = flags,
                            description = p.optString("description"),
                        )
                    )
                }
                if (list.isEmpty()) continue
                result.add(Category(o.getString("category"), list))
            }
        } catch (_: Exception) {
        }
        cached = result
        return result
    }
}