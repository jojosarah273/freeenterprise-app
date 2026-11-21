package com.freeenterprise.app

import android.content.Context
import java.io.File

/**
 * The randomizer engines this build carries.
 *
 * Each engine is a fully self-contained directory - its own FreeEnt, f4c, pyaes,
 * any data it needs (GOLBEZ carries golbez_reference/), and its own compiled
 * flag-editor spec and presets. Nothing crosses between them: the editor shows the
 * selected engine's rows, validation runs against the selected engine's own
 * FlagSet, and the seed records which engine built it.
 *
 * Gale is the production engine and the default. GOLBEZ ships in dev builds to
 * try the multiple-engine flow; production keeps Gale alone until that is
 * deliberately widened.
 */
object Engines {

    class Engine(
        /** Stable id, stored in seed metadata and EditorPrefs. */
        val id: String,
        /** What the Engine row and the picker show. */
        val display: String,
        /** The asset directory bundled in the APK. */
        val assetDir: String,
        /** Where it is extracted in app storage. */
        val dirName: String,
    )

    val gale = Engine(
        id = "gale",
        display = "4.7.0.Gale",
        assetDir = "python_ext",
        dirName = "python_ext",
    )

    val golbez = Engine(
        id = "golbez",
        display = "4.6.0-Golbez",
        assetDir = "engine_golbez",
        dirName = "engine_golbez",
    )

    val all: List<Engine> = listOf(gale, golbez)

    fun byId(id: String): Engine = all.firstOrNull { it.id == id } ?: gale

    fun selected(context: Context): Engine = byId(EditorPrefs.engine(context))

    fun dir(context: Context, id: String): File = File(context.filesDir, byId(id).dirName)

    /**
     * The flag-editor spec for the selected engine. Gale's is an app asset that an
     * in-place engine update may replace; the others live beside their engine.
     */
    fun specJson(context: Context, id: String): String =
        if (id == gale.id) {
            AppContent.read(context, "spec/ui_spec.json")
        } else {
            File(dir(context, id), "assets/ui_spec.json").readText()
        }

    /** Same, for presets. Both engines' files are the site's format. */
    fun presetsJson(context: Context, id: String): String =
        if (id == gale.id) {
            AppContent.read(context, "presets/presets.json")
        } else {
            File(dir(context, id), "assets/presets.json").readText()
        }
}
