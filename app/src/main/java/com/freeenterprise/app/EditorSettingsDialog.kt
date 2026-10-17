package com.freeenterprise.app

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Settings pane for the editor (and main screen) colors. Applies live. */
object EditorSettingsDialog {

    fun show(activity: AppCompatActivity, onChanged: () -> Unit) {
        val ctx = activity
        fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(4))
        }

        // ------------------------------------------------------ background theme
        column.addView(label(ctx, "Background"))
        val themeGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val themeButtons = listOf(
            Triple("Light", EditorPrefs.THEME_LIGHT, RadioButton(ctx)),
            Triple("Dark", EditorPrefs.THEME_DARK, RadioButton(ctx)),
            Triple("System", EditorPrefs.THEME_SYSTEM, RadioButton(ctx)),
        )
        val selectedTheme = EditorPrefs.theme(ctx)
        themeButtons.forEach { (text, value, rb) ->
            rb.text = text
            rb.isChecked = value == selectedTheme
            rb.tag = value
            themeGroup.addView(rb)
        }
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val rb = themeGroup.findViewById<RadioButton>(checkedId)
            val value = rb?.tag as? Int ?: return@setOnCheckedChangeListener
            EditorPrefs.setTheme(ctx, value)

            // keep text legible when switching to a dark background
            if (value == EditorPrefs.THEME_DARK || (value == EditorPrefs.THEME_SYSTEM && EditorPrefs.isDark(ctx))) {
                // only force light text if the current swatch is a dark color
                val lum = luminance(EditorPrefs.textColor(ctx))
                if (lum < 0.5f) EditorPrefs.setTextColor(ctx, Color.WHITE)
            }
            onChanged()
        }
        column.addView(themeGroup)

        // ------------------------------------------------------ text color row
        column.addView(label(ctx, "Text color"))
        val textRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        renderSwatches(ctx, textRow, EditorPrefs.TEXT_SWATCHES, EditorPrefs.textColor(ctx)) { color ->
            EditorPrefs.setTextColor(ctx, color)
            onChanged()
        }
        column.addView(textRow)

        // ------------------------------------------------------ accent color row
        column.addView(label(ctx, "Accent color  (✓ , selected, section accents)"))
        val accentRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        renderSwatches(ctx, accentRow, EditorPrefs.ACCENT_SWATCHES, EditorPrefs.accentColor(ctx)) { color ->
            EditorPrefs.setAccentColor(ctx, color)
            onChanged()
        }
        column.addView(accentRow)

        // ------------------------------------------------------ font size
        column.addView(label(ctx, "Font size"))
        val fontGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(
            "Small" to 0.9f,
            "Normal" to 1.0f,
            "Large" to 1.25f,
        ).forEach { (text, scale) ->
            val rb = RadioButton(ctx)
            rb.text = text
            rb.isChecked = kotlin.math.abs(EditorPrefs.fontSize(ctx) - scale) < 0.01f
            rb.tag = scale
            fontGroup.addView(rb)
        }
        fontGroup.setOnCheckedChangeListener { _, checkedId ->
            val rb = fontGroup.findViewById<RadioButton>(checkedId)
            val scale = rb?.tag as? Float ?: return@setOnCheckedChangeListener
            EditorPrefs.setFontSize(ctx, scale)
            onChanged()
        }
        column.addView(fontGroup)

        val reset = Button(ctx).apply {
            text = "Restore defaults"
            setOnClickListener {
                EditorPrefs.reset(ctx)
                onChanged()
            }
        }
        column.addView(reset, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        AlertDialog.Builder(ctx)
            .setTitle("Settings")
            .setView(column)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun label(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTypeface(null, Typeface.BOLD)
            setTextColor(EditorPrefs.textColor(ctx))
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 2))
        }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun renderSwatches(
        ctx: android.content.Context,
        row: LinearLayout,
        entries: List<Pair<String, Int>>,
        selected: Int,
        onClick: (Int) -> Unit,
    ) {
        row.removeAllViews()
        entries.forEach { (name, color) ->
            val swatch = View(ctx).apply {
                val d = GradientDrawable()
                d.shape = GradientDrawable.OVAL
                d.setColor(color)
                d.setStroke(dp(ctx, 3), if (color == selected) 0xFF000000.toInt() else Color.TRANSPARENT)
                background = d
                isClickable = true
                contentDescription = name
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)).apply {
                    marginEnd = dp(ctx, 10)
                }
                setOnClickListener {
                    onClick(color)
                    renderSwatches(ctx, row, entries, color, onClick) // refresh selection ring
                }
            }
            row.addView(swatch)
        }
    }

    private fun luminance(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
}