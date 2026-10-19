package com.freeenterprise.app

import android.os.Bundle
import android.text.Html
import com.freeenterprise.app.ff4ui.FF4Dialog
import com.freeenterprise.app.ff4ui.FF4Menu
import com.freeenterprise.app.ff4ui.FF4MenuActivity
import com.freeenterprise.app.ff4ui.FF4Theme

/**
 * Preset browser: the website's presets, plus **your own saved flagsets** in a
 * "My flagsets" category at the top, where they can also be deleted.
 *
 * Applying anything here only sets the current flagset; the Generate screen
 * translates any flags that predate the current flag spec and says what changed.
 */
class FF4PresetActivity : FF4MenuActivity() {

    private var categories: List<Presets.Category> = emptyList()
    private var category: Presets.Category? = null

    private val mine = "My flagsets"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        categories = Presets.load(this)
        showCategories()
    }

    private fun savedFlagsets(): List<FlagsetLibrary.Flagset> = FlagsetLibrary.list(this)

    private fun showCategories() {
        category = null
        val names = listOf(mine) + categories.map { it.name }
        setStatus(
            "Presets — pick a category\n" +
                "\"$mine\" holds the flagsets you saved (${savedFlagsets().size}).",
            FF4Theme.WHITE
        )
        setHint("A  Open      B  Back")
        val items = names.mapIndexed { index, name ->
            if (index == 0) {
                FF4Menu.Item(name, "${savedFlagsets().size} saved")
            } else {
                FF4Menu.Item(name, "${categories[index - 1].presets.size}")
            }
        }
        submitMenu("presetcats", items) { i ->
            if (i == 0) {
                showMyFlagsets()
            } else {
                category = categories.getOrNull(i - 1)
                showPresets()
            }
        }
    }

    private fun showPresets() {
        val cat = category ?: return
        setStatus(cat.name, FF4Theme.WHITE)
        setHint("A  Apply      B  Back")
        submitMenu("presets:" + cat.name, cat.presets.map { p ->
            FF4Menu.Item(
                label = p.name,
                subtitle = Html.fromHtml(p.description, Html.FROM_HTML_MODE_COMPACT).toString().take(140),
            )
        }) { i ->
            val preset = cat.presets.getOrNull(i) ?: return@submitMenu
            apply(preset.name, preset.flags)
        }
    }

    // ------------------------------------------------------- your own flagsets

    private fun showMyFlagsets() {
        val sets = savedFlagsets()
        setHint("A  Apply      B  Back")
        if (sets.isEmpty()) {
            setStatus(
                "No saved flagsets yet.\n\n" +
                    "Build a flagset, then use \"Save Flagset\" on the Generate screen —\n" +
                    "or \"Save flags as Flagset\" on a session — and it will appear here.",
                FF4Theme.YELLOW
            )
            submitMenu("myflagsets", listOf(FF4Menu.Item("Back"))) { showCategories() }
            return
        }

        setStatus("Your flagsets — ${sets.size} saved\nPick one to apply it, or delete below.", FF4Theme.WHITE)
        val items = sets.map { FF4Menu.Item(it.name, "ruleset") } + listOf(
            FF4Menu.Item("Delete a flagset…", ""),
            FF4Menu.Item("Back", ""),
        )
        submitMenu("myflagsets", items) { i ->
            when {
                i < sets.size -> apply(sets[i].name, sets[i].flags)
                i == sets.size -> showDeleteFlagsets()
                else -> showCategories()
            }
        }
    }

    private fun showDeleteFlagsets() {
        val sets = savedFlagsets()
        setStatus("Delete which flagset?\nThis only removes the saved ruleset — seeds made from it are untouched.", FF4Theme.YELLOW)
        setHint("A  Delete      B  Back")
        val items = sets.map { FF4Menu.Item(it.name, "delete") } + listOf(FF4Menu.Item("Back"))
        submitMenu("deleteflagsets", items) { i ->
            val target = sets.getOrNull(i)
            if (target == null) {
                showMyFlagsets()
                return@submitMenu
            }
            FF4Dialog.confirm(
                this,
                "Delete flagset",
                "Delete \"${target.name}\"?\nSeeds already made from it are untouched.",
                yes = "Delete",
                no = "Cancel",
                onYes = {
                    FlagsetLibrary.delete(this, target.name)
                    if (FlagSession.presetName() == target.name) FlagSession.setPresetName("")
                    showMyFlagsets()
                },
                onNo = { showDeleteFlagsets() },
            )
        }
    }

    private fun apply(name: String, flags: String) {
        FlagSession.setFlags(flags)
        FlagSession.setPresetName(name)
        setStatus("Applied \"$name\".\nThe Generate screen will show the flags and any translation.", FF4Theme.GREEN)
        finish()
    }

    override fun onBackRequested(): Boolean {
        return when {
            category != null -> { showCategories(); true }
            else -> { finish(); true }
        }
    }
}
