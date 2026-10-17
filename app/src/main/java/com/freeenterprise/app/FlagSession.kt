package com.freeenterprise.app

/**
 * The flagset being built *within the current app session*. Deliberately NOT
 * persisted: every app launch starts from a clean slate, and a completed
 * generation clears it so the next seed starts fresh. It only lives long enough
 * for you to move between Generate / Presets / Flag editor and see your work.
 */
object FlagSession {
    private var flags: String = ""
    private var seed: String = ""
    private var preset: String = ""

    fun flags(): String = flags
    fun setFlags(value: String) { flags = value }

    fun seed(): String = seed
    fun setSeed(value: String) { seed = value }

    fun presetName(): String = preset
    fun setPresetName(value: String) { preset = value }

    fun clear() {
        flags = ""
        seed = ""
        preset = ""
    }
}