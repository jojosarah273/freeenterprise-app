package com.freeenterprise.ff4

/**
 * The rows of every menu, in one place.
 *
 * These lists used to exist twice - once in the Android activities, once in the
 * desktop client - and drifted: a row gained a value on one platform the other never
 * had ("Rename Seed"), and another changed order and values ("Manage Save"). Defining
 * them here means a row cannot exist differently in two places.
 *
 * A row is label, value, enabled. The *action* a row triggers stays with each
 * platform, because it is genuinely different: Android starts an activity or a
 * document picker, the desktop opens a screen or a file chooser. But the order, the
 * wording and the values are shared, so screens read the same everywhere.
 *
 * Values that a platform must supply differently are parameters: the tracker's
 * shortcut key, and whether the platform has update and settings screens at all.
 */
data class Ff4Row(
    val label: String,
    val value: String = "",
    val enabled: Boolean = true,
)

object Ff4Rows {

    /** How many seeds the library has, for the main menu's value. */
    fun mainMenu(
        seedCount: Int,
        appVersion: String,
        /** Android offers Check for updates and Settings; the desktop has its own. */
        includeUpdateAndSettings: Boolean,
    ): List<Ff4Row> = buildList {
        add(Ff4Row("Play Free Enterprise", "$seedCount seed(s)"))
        add(Ff4Row("Generate Seed"))
        if (includeUpdateAndSettings) {
            add(Ff4Row("Check for updates", "v$appVersion"))
            add(Ff4Row("Settings"))
        }
        add(Ff4Row("Exit"))
    }

    /** The actions for one seed. */
    fun sessionActions(hasSave: Boolean): List<Ff4Row> = listOf(
        Ff4Row("Play Seed"),
        Ff4Row("Manage Save", if (hasSave) "present" else "none"),
        Ff4Row("New seed with these flags", "same ruleset"),
        Ff4Row("Save flags as Flagset"),
        // No value: the file name can be long (FF4FE.<seed>.<binary flags>).
        Ff4Row("Rename Seed"),
        Ff4Row("Delete Seed"),
        Ff4Row("Back"),
    )

    /** Manage Save, in the app's order: import, export, delete. */
    fun manageSave(hasSave: Boolean): List<Ff4Row> = listOf(
        Ff4Row("Import save (.srm)", if (hasSave) "replace" else ""),
        Ff4Row("Export save (.srm)", enabled = hasSave),
        Ff4Row("Delete save", enabled = hasSave),
        Ff4Row("Back"),
    )

    /** The Generate screen. */
    fun generate(
        presetName: String,
        flagsSet: Boolean,
        seedName: String,
        valid: Boolean,
    ): List<Ff4Row> = listOf(
        Ff4Row("Choose Preset", presetName),
        Ff4Row("Choose Flags", if (flagsSet) "" else "none set"),
        Ff4Row("Seed", seedName.ifBlank { "random" }),
        Ff4Row("Generate Seed", if (valid) "" else "fix errors"),
        Ff4Row("Back"),
    )

    /** The screen shown after a seed is generated. */
    fun completed(): List<Ff4Row> = listOf(
        Ff4Row("Play Free Enterprise", "open seeds"),
        Ff4Row("Generate Again"),
        Ff4Row("Back"),
    )

    /**
     * The in-game menu. [trackerKey] is the platform's shortcut name - "L2" on a
     * pad-led app, "Tab" on a keyboard-led one.
     */
    fun pauseMenu(
        hasState: Boolean,
        fastForward: Boolean,
        trackerKey: String,
    ): List<Ff4Row> = listOf(
        Ff4Row("Resume"),
        Ff4Row("Save State"),
        Ff4Row("Load State", if (hasState) "load" else "none", enabled = hasState),
        Ff4Row("Fast-forward", if (fastForward) "on" else "off"),
        Ff4Row("Checks tracker", trackerKey),
        Ff4Row("Reset Core"),
        Ff4Row("Exit to sessions"),
    )
}
