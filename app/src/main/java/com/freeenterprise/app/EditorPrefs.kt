package com.freeenterprise.app

import android.content.Context
import android.content.SharedPreferences

/** Editor/screen appearance settings (SharedPreferences-backed). */
object EditorPrefs {
    private const val PREF = "editor_settings"
    private const val KEY_THEME = "theme"          // LIGHT / DARK / SYSTEM
    private const val KEY_TEXT = "text_color"       // Int
    private const val KEY_ACCENT = "accent_color"   // Int
    private const val KEY_FONT = "font_scale"       // Float
    private const val KEY_UI_MODE = "ui_mode"       // "ff4" | "classic"
    private const val KEY_ONSCREEN = "onscreen_controls"

    const val UI_FF4 = "ff4"
    const val UI_CLASSIC = "classic"

    const val THEME_LIGHT = 0
    const val THEME_DARK = 1
    const val THEME_SYSTEM = 2

    // Site-like palette defaults.
    const val DEFAULT_TEXT = 0xFF23234A.toInt()     // dark blue (site headings)
    const val DEFAULT_ACCENT = 0xFF2E7D32.toInt()  // green (✓)

    val TEXT_SWATCHES = listOf(
        "Dark blue" to 0xFF23234A.toInt(),
        "Black" to 0xFF000000.toInt(),
        "White" to 0xFFFFFFFF.toInt(),
        "Cream" to 0xFFFFF8DC.toInt(),
        "Silver" to 0xFFE8E8E8.toInt(),
    )
    val ACCENT_SWATCHES = listOf(
        "Green" to 0xFF2E7D32.toInt(),
        "Teal" to 0xFF00695C.toInt(),
        "Gold" to 0xFFB8860B.toInt(),
        "Orange" to 0xFFC05000.toInt(),
        "Blue" to 0xFF1E5AA8.toInt(),
        "Magenta" to 0xFF8E24AA.toInt(),
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun theme(context: Context): Int = prefs(context).getInt(KEY_THEME, THEME_LIGHT)

    fun isDark(context: Context): Boolean {
        return when (theme(context)) {
            THEME_DARK -> true
            THEME_SYSTEM -> (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
    }

    fun textColor(context: Context): Int = prefs(context).getInt(KEY_TEXT, DEFAULT_TEXT)

    fun accentColor(context: Context): Int = prefs(context).getInt(KEY_ACCENT, DEFAULT_ACCENT)

    fun fontSize(context: Context): Float = prefs(context).getFloat(KEY_FONT, 1.0f)

    /** On-screen gamepad in the player — off by default. */
    fun onscreenControls(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONSCREEN, false)

    fun setOnscreenControls(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONSCREEN, on).apply()
    }

    fun uiMode(context: Context): String =
        prefs(context).getString(KEY_UI_MODE, UI_FF4) ?: UI_FF4

    fun setUiMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_UI_MODE, mode).apply()
    }

    fun background(context: Context): Int =
        if (isDark(context)) 0xFF101418.toInt() else 0xFFF7F4EE.toInt()

    fun panel(context: Context): Int =
        if (isDark(context)) 0xFF1A2029.toInt() else 0xFFEFF1F7.toInt()

    fun secondaryText(context: Context): Int =
        if (isDark(context)) 0xFF9AA4B2.toInt() else 0xFF666666.toInt()

    fun dangerText(): Int = 0xFFAA3333.toInt()

    fun setTheme(context: Context, t: Int) { prefs(context).edit().putInt(KEY_THEME, t).apply() }
    fun setTextColor(context: Context, c: Int) { prefs(context).edit().putInt(KEY_TEXT, c).apply() }
    fun setAccentColor(context: Context, c: Int) { prefs(context).edit().putInt(KEY_ACCENT, c).apply() }
    fun setFontSize(context: Context, f: Float) { prefs(context).edit().putFloat(KEY_FONT, f).apply() }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Sanity: text on the current background shouldn't be dark-on-dark or light-on-light. */
    fun saneTextColor(context: Context): Int {
        var c = textColor(context)
        val dark = isDark(context)
        val lightText = luminance(c) > 0.5f
        if (dark && !lightText) return 0xFFFFFFFF.toInt()
        if (!dark && lightText && c != 0xFFFFFFFF.toInt()) c = DEFAULT_TEXT
        return c
    }

    private fun luminance(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
}