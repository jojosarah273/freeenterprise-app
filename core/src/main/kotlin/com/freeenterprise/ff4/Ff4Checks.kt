package com.freeenterprise.ff4

/**
 * The check list and how much of it is done.
 *
 * Each check's [Check.flag] is a bit index into the 512 treasure switches at WRAM
 * $12A0 - the same array the engine's own `stats.f4c` counts, so the interface and
 * the game agree by construction. Bits are least significant first within each byte.
 *
 * The list itself is built by the platform from the generated `checks.json`; this
 * object owns the shape and the counting, which is the part worth sharing.
 */
object Ff4Checks {

    data class Check(val flag: Int, val world: String, val area: String, val detail: String)

    data class Progress(
        val opened: Int,
        val total: Int,
        val byWorld: Map<String, Pair<Int, Int>>,
        /** area, opened, total - most remaining first, as the app lists them. */
        val byArea: List<Triple<String, Int, Int>>,
    )

    /** True when check bit [flag] is set in the switch block at [base] of [wram]. */
    fun isChecked(wram: ByteArray, base: Int, flag: Int): Boolean {
        val offset = base + (flag shr 3)
        if (offset < 0 || offset >= wram.size) return false
        return ((wram[offset].toInt() shr (flag and 7)) and 1) == 1
    }

    /** Counts opened checks against [checks], mirroring the engine's own counter. */
    fun progress(checks: List<Check>, wram: ByteArray, switchBase: Int): Progress {
        var opened = 0
        val worldOpen = HashMap<String, Int>()
        val worldTotal = HashMap<String, Int>()
        val areaOpen = HashMap<String, Int>()
        val areaTotal = HashMap<String, Int>()

        for (check in checks) {
            val on = isChecked(wram, switchBase, check.flag)
            if (on) opened++
            worldTotal[check.world] = (worldTotal[check.world] ?: 0) + 1
            areaTotal[check.area] = (areaTotal[check.area] ?: 0) + 1
            if (on) {
                worldOpen[check.world] = (worldOpen[check.world] ?: 0) + 1
                areaOpen[check.area] = (areaOpen[check.area] ?: 0) + 1
            }
        }

        val byWorld = worldTotal.keys.associateWith { world ->
            (worldOpen[world] ?: 0) to (worldTotal[world] ?: 0)
        }
        // Most remaining first, because the question this list answers is "where should
        // I go next". The app sorts by `(total - opened) * 1000 - total`, which puts the
        // *finished* areas at the top - the opposite of what its own comment says. That
        // is a bug there; porting it would have spread it here.
        val byArea = areaTotal.keys
            .map { area -> Triple(area, areaOpen[area] ?: 0, areaTotal[area] ?: 0) }
            .sortedWith(
                compareByDescending<Triple<String, Int, Int>> { (_, openedHere, total) -> total - openedHere }
                    .thenByDescending { (_, _, total) -> total }
                    .thenBy { (area, _, _) -> area },
            )

        return Progress(opened, checks.size, byWorld, byArea)
    }
}
