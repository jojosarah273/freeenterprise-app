package com.freeenterprise.app

import android.os.Bundle
import android.text.Html
import com.freeenterprise.app.ff4ui.FF4Menu
import com.freeenterprise.app.ff4ui.FF4MenuActivity
import com.freeenterprise.app.ff4ui.FF4Theme

/** Preset browser (the website's presets), applied into the current flagset. */
class FF4PresetActivity : FF4MenuActivity() {

    private var categories: List<Presets.Category> = emptyList()
    private var category: Presets.Category? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        categories = Presets.load(this)
        showCategories()
    }

    private fun showCategories() {
        category = null
        setStatus("Presets — pick a category", FF4Theme.WHITE)
        setHint("A  Open      B  Back")
        submitMenu("presetcats", categories.map { FF4Menu.Item(it.name, "${it.presets.size}") }) { i ->
            category = categories.getOrNull(i)
            showPresets()
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
            val p = cat.presets.getOrNull(i) ?: return@submitMenu
            FlagSession.setFlags(p.flags)
            FlagSession.setPresetName(p.name)
            setStatus("Preset applied: ${p.name}", FF4Theme.GREEN)
            finish()
        }
    }

    override fun onBackRequested(): Boolean {
        if (category != null) {
            showCategories()
            return true
        }
        finish()
        return true
    }
}