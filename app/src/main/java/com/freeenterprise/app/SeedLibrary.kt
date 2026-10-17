package com.freeenterprise.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SeedRecord(
    val time: Long,
    val seed: String,
    val flags: String,
    val binaryFlags: String,
    val verification: List<String>,
    val filePath: String,
    val fileName: String,
    val version: String,
    val seconds: Double,
)

/** Simple JSON-backed history of generated seeds. */
object SeedLibrary {
    private fun file(context: Context): File = File(context.filesDir, "seeds.json")

    fun load(context: Context): List<SeedRecord> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val result = mutableListOf<SeedRecord>()
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val verification = mutableListOf<String>()
                val vArr = o.optJSONArray("verification") ?: JSONArray()
                for (j in 0 until vArr.length()) verification.add(vArr.getString(j))
                result.add(
                    SeedRecord(
                        time = o.optLong("time"),
                        seed = o.optString("seed"),
                        flags = o.optString("flags"),
                        binaryFlags = o.optString("binary_flags"),
                        verification = verification,
                        filePath = o.optString("path"),
                        fileName = o.optString("file"),
                        version = o.optString("version"),
                        seconds = o.optDouble("seconds"),
                    )
                )
            }
        } catch (_: Exception) {
            // corrupt history: start fresh
        }
        return result.sortedByDescending { it.time }
    }

    fun add(context: Context, record: SeedRecord) {
        val list = load(context).toMutableList()
        list.add(0, record)
        persist(context, list)
    }

    fun remove(context: Context, record: SeedRecord) {
        val list = load(context).filter { !(it.time == record.time && it.seed == record.seed) }
        persist(context, list)
    }

    /** Replace a record in place (e.g. after regenerating the ROM file). */
    fun replace(context: Context, old: SeedRecord, new: SeedRecord) {
        val list = load(context).map {
            if (it.time == old.time && it.seed == old.seed) new else it
        }
        persist(context, list)
    }

    private fun persist(context: Context, list: List<SeedRecord>) {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("time", r.time)
            o.put("seed", r.seed)
            o.put("flags", r.flags)
            o.put("binary_flags", r.binaryFlags)
            o.put("verification", JSONArray(r.verification))
            o.put("path", r.filePath)
            o.put("file", r.fileName)
            o.put("version", r.version)
            o.put("seconds", r.seconds)
            arr.put(o)
        }
        file(context).writeText(arr.toString())
    }
}