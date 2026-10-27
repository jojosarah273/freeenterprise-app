package com.freeenterprise.ff4

/**
 * The rows of every menu, in one place.
 *
 * These lists used to exist twice - once in the Android activities, once in the
 * desktop client - and drifted: a row gained a value on one platform the other never
 * had ("Rename Seed"), and another changed order and values ("Manage Save"). Defining
 * them here means a row cannot exist differently in two places.
 *
 * A row is label, value, enabled and *action*. The action is an identity, not an
 * implementation: "this row plays a seed" is the same on both platforms, which then
 * do it their own way (Android starts an activity, the desktop opens a screen).
 *
 * Sharing the action matters as much as sharing the wording. Both platforms used to
 * dispatch on row position (`when (i) { 2 -> ... }`), so the moment one platform
 * offered a different set of rows, pressing Exit checked for updates. With actions,
 * a row's position is decoration and reordering cannot change what it does.
 *
 * Values that a platform must supply differently are parameters: the tracker's
 * shortcut key, and whether the platform has update and settings screens at all.
 */
/** What a row does. Platforms switch on this; never on a row's index. */
enum class Ff4Action {
    // Main menu
    PLAY_SEEDS, GENERATE_SEED, CHECK_UPDATES, OPEN_SETTINGS, EXIT_APP,
    // One seed's actions
    PLAY_SEED, MANAGE_SAVE, REGENERATE_SAME_FLAGS, SAVE_FLAGSET, RENAME_SEED, DELETE_SEED,
    // Manage Save
    IMPORT_SAVE, EXPORT_SAVE, DELETE_SAVE,
    // Generate
    CHOOSE_PRESET, CHOOSE_FLAGS, EDIT_SEED_NAME, RUN_GENERATION,
    // After generating
    OPEN_SEED_LIBRARY, GENERATE_AGAIN,
    // In-game menu
    RESUME, SAVE_STATE, LOAD_STATE, TOGGLE_FAST_FORWARD, OPEN_TRACKER, RESET_CORE,
    EXIT_TO_SESSIONS,
    // Shared by several screens
    BACK,
}

data class Ff4Row(
    val label: String,
    val value: String = "",
    val enabled: Boolean = true,
    val action: Ff4Action,
)

object Ff4Rows {

    /** How many seeds the library has, for the main menu's value. */
    fun mainMenu(
        seedCount: Int,
        appVersion: String,
        /**
         * The two optional rows are separate flags: Android has both, the desktop
         * offers updates (it can update engine and app) but no Settings screen.
         *
         * They must not be one boolean. With a single flag the desktop had no
         * "Check for updates" row at all, and its activation still mapped index 2 to
         * updates - so choosing Exit checked for updates instead of quitting.
         */
        includeUpdates: Boolean = true,
        includeSettings: Boolean = true,
    ): List<Ff4Row> = buildList {
        add(Ff4Row("Play Free Enterprise", "$seedCount seed(s)", action = Ff4Action.PLAY_SEEDS))
        add(Ff4Row("Generate Seed", action = Ff4Action.GENERATE_SEED))
        if (includeUpdates) {
            add(Ff4Row("Check for updates", "v$appVersion", action = Ff4Action.CHECK_UPDATES))
        }
        if (includeSettings) add(Ff4Row("Settings", action = Ff4Action.OPEN_SETTINGS))
        add(Ff4Row("Exit", action = Ff4Action.EXIT_APP))
    }

    /** The actions for one seed. */
    fun sessionActions(hasSave: Boolean): List<Ff4Row> = listOf(
        Ff4Row("Play Seed", action = Ff4Action.PLAY_SEED),
        Ff4Row("Manage Save", if (hasSave) "present" else "none", action = Ff4Action.MANAGE_SAVE),
        Ff4Row("New seed with these flags", "same ruleset", action = Ff4Action.REGENERATE_SAME_FLAGS),
        Ff4Row("Save flags as Flagset", action = Ff4Action.SAVE_FLAGSET),
        // No value: the file name can be long (FF4FE.<seed>.<binary flags>).
        Ff4Row("Rename Seed", action = Ff4Action.RENAME_SEED),
        Ff4Row("Delete Seed", action = Ff4Action.DELETE_SEED),
        Ff4Row("Back", action = Ff4Action.BACK),
    )

    /** Manage Save, in the app's order: import, export, delete. */
    fun manageSave(hasSave: Boolean): List<Ff4Row> = listOf(
        Ff4Row("Import save (.srm)", if (hasSave) "replace" else "", action = Ff4Action.IMPORT_SAVE),
        Ff4Row("Export save (.srm)", enabled = hasSave, action = Ff4Action.EXPORT_SAVE),
        Ff4Row("Delete save", enabled = hasSave, action = Ff4Action.DELETE_SAVE),
        Ff4Row("Back", action = Ff4Action.BACK),
    )

    /** The Generate screen. */
    fun generate(
        presetName: String,
        flagsSet: Boolean,
        seedName: String,
        valid: Boolean,
    ): List<Ff4Row> = listOf(
        Ff4Row("Choose Preset", presetName, action = Ff4Action.CHOOSE_PRESET),
        Ff4Row("Choose Flags", if (flagsSet) "" else "none set", action = Ff4Action.CHOOSE_FLAGS),
        Ff4Row("Seed", seedName.ifBlank { "random" }, action = Ff4Action.EDIT_SEED_NAME),
        Ff4Row("Generate Seed", if (valid) "" else "fix errors", action = Ff4Action.RUN_GENERATION),
        Ff4Row("Back", action = Ff4Action.BACK),
    )

    /** The screen shown after a seed is generated. */
    fun completed(): List<Ff4Row> = listOf(
        Ff4Row("Play Free Enterprise", "open seeds", action = Ff4Action.OPEN_SEED_LIBRARY),
        Ff4Row("Generate Again", action = Ff4Action.GENERATE_AGAIN),
        Ff4Row("Back", action = Ff4Action.BACK),
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
        Ff4Row("Resume", action = Ff4Action.RESUME),
        Ff4Row("Save State", action = Ff4Action.SAVE_STATE),
        Ff4Row("Load State", if (hasState) "load" else "none", enabled = hasState, action = Ff4Action.LOAD_STATE),
        Ff4Row("Fast-forward", if (fastForward) "on" else "off", action = Ff4Action.TOGGLE_FAST_FORWARD),
        Ff4Row("Checks tracker", trackerKey, action = Ff4Action.OPEN_TRACKER),
        Ff4Row("Reset Core", action = Ff4Action.RESET_CORE),
        Ff4Row("Exit to sessions", action = Ff4Action.EXIT_TO_SESSIONS),
    )
}
