package com.freeenterprise.app

import android.content.Context
import org.json.JSONArray

/**
 * The check list, generated from FE's own treasure database
 * (`FreeEnt/assets/db/treasure.csvdb`). Each entry's `flag` is the bit index
 * into the 512 treasure switches at WRAM $12A0 — the same array FE's own
 * `stats.f4c` counts. Bits are LSB-first within each byte.
 */
object CheckDb {

    data class Check(val flag: Int, val world: String, val area: String, val detail: String)

    private var cached: List<Check>? = null

    fun load(context: Context): List<Check> {
        cached?.let { return it }
        val list = mutableListOf<Check>()
        try {
            val raw = AppContent.read(context, "spec/checks.json")
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Check(
                        flag = o.getInt("f"),
                        world = o.getString("w"),
                        area = o.getString("a"),
                        detail = o.optString("d"),
                    )
                )
            }
        } catch (_: Exception) {
        }
        cached = list
        return list
    }

    /** True when check bit [flag] is set in the live switch block at [base] (WRAM $12A0). */
    fun isChecked(wram: ByteArray, base: Int, flag: Int): Boolean {
        val off = base + (flag shr 3)
        if (off < 0 || off >= wram.size) return false
        return ((wram[off].toInt() shr (flag and 7)) and 1) == 1
    }

    data class Progress(
        val opened: Int,
        val total: Int,
        val byWorld: Map<String, Pair<Int, Int>>,
        val byArea: List<Triple<String, Int, Int>>,   // area, opened, total
    )

    /** Counts opened checks against the database, mirroring FE's own counter. */
    fun progress(context: Context, wram: ByteArray, switchBase: Int): Progress {
        val checks = load(context)
        var opened = 0
        val worldOpen = HashMap<String, Int>()
        val worldTotal = HashMap<String, Int>()
        val areaOpen = HashMap<String, Int>()
        val areaTotal = HashMap<String, Int>()
        for (c in checks) {
            val on = isChecked(wram, switchBase, c.flag)
            if (on) opened++
            worldTotal[c.world] = (worldTotal[c.world] ?: 0) + 1
            areaTotal[c.area] = (areaTotal[c.area] ?: 0) + 1
            if (on) {
                worldOpen[c.world] = (worldOpen[c.world] ?: 0) + 1
                areaOpen[c.area] = (areaOpen[c.area] ?: 0) + 1
            }
        }
        val byWorld = worldTotal.keys.associateWith { w ->
            (worldOpen[w] ?: 0) to (worldTotal[w] ?: 0)
        }
        val byArea = areaTotal.keys
            .map { a -> Triple(a, areaOpen[a] ?: 0, areaTotal[a] ?: 0) }
            .sortedBy { (_, openedCount, total) -> (total - openedCount) * 1000 - total }
        return Progress(opened, checks.size, byWorld, byArea)
    }
}