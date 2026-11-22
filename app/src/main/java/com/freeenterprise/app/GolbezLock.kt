package com.freeenterprise.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Runs the Golbez fork's own lock verification on a generated ROM, on the device.
 *
 * The fork defines correctness in `golbez_reference/LOCKED_MANIFEST.json` — the
 * authoritative spec shipped inside the engine bundle: thirteen locked Golbez
 * regions that must match their reference blobs exactly, five "locked vanilla"
 * regions that must stay vanilla, and the map palette selector byte. Its
 * `verify_golbez_lock.py` checks a ROM against that manifest; this is the same
 * check, in Kotlin, so every seed the app builds proves itself on the phone that
 * will play it — no host required.
 *
 * A passing ROM is byte-correct by the fork's own definition. A failing one names
 * the region that differs.
 */
object GolbezLock {

    data class Result(val passed: Boolean, val regions: Int, val detail: String?)

    /** The extracted golbez engine's reference data, or null when not bundled. */
    private fun referenceDir(context: Context): File? {
        val dir = File(Engines.dir(context, Engines.golbez.id), "golbez_reference")
        return if (File(dir, "LOCKED_MANIFEST.json").isFile) dir else null
    }

    /** Verifies [rom] against the manifest; null when this build has no golbez engine. */
    fun verify(context: Context, rom: File): Result? {
        val ref = referenceDir(context) ?: return null
        val manifest = JSONObject(File(ref, "LOCKED_MANIFEST.json").readText())
        val bytes = rom.readBytes()
        var checked = 0

        for (group in listOf("blobs", "locked_vanilla")) {
            val blobs = manifest.optJSONObject(group) ?: continue
            for (name in blobs.keys()) {
                val meta = blobs.getJSONObject(name)
                val data = File(ref, name).readBytes()
                val start = meta.getInt("address")
                if (start + data.size > bytes.size) {
                    return Result(false, checked, "$name runs past the end of the ROM")
                }
                checked++
                if (!bytes.sliceArray(start until start + data.size).contentEquals(data)) {
                    return Result(false, checked, "$name differs at 0x${start.toString(16).uppercase()}")
                }
            }
        }

        // Rule 6 of the fork's spec: the map palette selector byte.
        val selector = manifest.optInt("map_palette_selector_offset", -1)
        if (selector >= 0 && selector < bytes.size) {
            checked++
            if (bytes[selector].toInt() != 0x03) {
                return Result(false, checked, "map palette selector at 0x${selector.toString(16).uppercase()} is not 0x03")
            }
        } else {
            // Older manifests carry the offset only in their prose; the fork's
            // current manifest does not have the field, so apply the documented one.
            val at = 0x0AB307
            if (at < bytes.size) {
                checked++
                if (bytes[at].toInt() != 0x03) {
                    return Result(false, checked, "map palette selector at 0x0AB307 is not 0x03")
                }
            }
        }

        return Result(true, checked, null)
    }
}
