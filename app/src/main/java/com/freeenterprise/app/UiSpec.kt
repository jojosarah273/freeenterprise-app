package com.freeenterprise.app

import org.json.JSONArray
import org.json.JSONObject

/** One control from the site's compiled ui_spec.json. */
class UiControl(
    val flag: String,
    val title: String,
    val type: String,          // "toggle", "select", "separator"
    val hard: Boolean,
    val compact: Boolean,
    val nullFlag: Boolean,
    val important: Boolean,
    val description: String?,  // HTML, rendered as text
    val subcontrols: List<UiControl>,
) {
    val isContainer: Boolean get() = flag.startsWith("@")
    val isSelect: Boolean get() = type == "select"
    val isSeparator: Boolean get() = type == "separator"
}

class UiSection(val title: String, val controls: List<UiControl>)

/**
 * Parses assets/spec/ui_spec.json (extracted from the site's compiled uispec.js).
 *
 * Doors/entrances randomization ships in the engine (4.7.0.Gale) but is not
 * offered in our menus yet. Flip [DOORS_ENABLED] to true to light it up — the
 * spec, engine and assets are already in place. See DESIGN_FF4_UI.md §11.
 */
object UiSpec {
    /** Doors & entrances randomization are bundled but hidden for now. */
    const val DOORS_ENABLED = false

    /** Matches -doorsrando:*, -entrancesrando:*, @doorsrando, @entrancesrando. */
    private val DOORS_RE = Regex("^-?@?(doors|entrances)rando", RegexOption.IGNORE_CASE)

    fun isHidden(flag: String): Boolean = !DOORS_ENABLED && DOORS_RE.containsMatchIn(flag)

    fun parse(raw: String): List<UiSection> {
        val sections = mutableListOf<UiSection>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.getString("title")
            // The DOORS section is entirely doors/entrances randomization.
            if (!DOORS_ENABLED && title.equals("DOORS", ignoreCase = true)) continue
            val controls = parseControls(o.optJSONArray("controls") ?: JSONArray())
            if (controls.isEmpty()) continue
            sections.add(UiSection(title = title, controls = controls))
        }
        return sections
    }

    private fun parseControls(arr: JSONArray): List<UiControl> {
        val out = mutableListOf<UiControl>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (isHidden(o.optString("flag", ""))) continue
            out.add(parseControl(o))
        }
        return out
    }

    private fun parseControl(o: JSONObject): UiControl {
        val subs = o.optJSONArray("subcontrols")
        return UiControl(
            flag = o.optString("flag", ""),
            title = o.optString("title", ""),
            type = o.optString("type", "toggle"),
            hard = o.optBoolean("hard", false),
            compact = o.optBoolean("compact", false),
            nullFlag = o.optBoolean("null", false),
            important = o.optBoolean("important", false),
            description = if (o.has("description") && !o.isNull("description")) o.getString("description") else null,
            subcontrols = if (subs != null) parseControls(subs) else emptyList(),
        )
    }
}