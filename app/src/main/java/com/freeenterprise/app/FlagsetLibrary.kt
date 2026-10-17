package com.freeenterprise.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Named flagsets — a ruleset you can re-apply later and roll into a brand new
 * seed. Stored as `flagsets.json` in FF4_FE so it travels with your library.
 */
object FlagsetLibrary {

    data class Flagset(val name: String, val flags: String, val created: Long)

    private const val FILE_NAME = "flagsets.json"

    private fun file(context: Context): File =
        File(RomLibrary.ensureFolder(context), FILE_NAME)

    fun list(context: Context): List<Flagset> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Flagset(
                            name = o.optString("name"),
                            flags = o.optString("flags"),
                            created = o.optLong("created"),
                        )
                    )
                }
            }.sortedByDescending { it.created }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, name: String, flags: String) {
        val cleaned = name.trim().ifBlank { "Flagset" }
        val existing = list(context).filterNot { it.name.equals(cleaned, ignoreCase = true) }
        val all = listOf(Flagset(cleaned, flags, System.currentTimeMillis())) + existing
        write(context, all)
    }

    fun delete(context: Context, name: String) {
        write(context, list(context).filterNot { it.name == name })
    }

    private fun write(context: Context, sets: List<Flagset>) {
        try {
            val arr = JSONArray()
            for (s in sets) {
                arr.put(
                    JSONObject().apply {
                        put("name", s.name)
                        put("flags", s.flags)
                        put("created", s.created)
                    }
                )
            }
            file(context).writeText(arr.toString(2))
        } catch (_: Exception) {
        }
    }
}