package com.freeenterprise.app.ff4ui

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Base activity for FF4-styled screens: black background, immersive, landscape. */
abstract class FF4Activity : AppCompatActivity() {

    /**
     * Remembers which row was chosen in each menu layer, so backing out of a
     * submenu returns the cursor to the item that opened it.
     */
    protected val selectionMemory = HashMap<String, Int>()

    protected fun rememberSelection(key: String, index: Int) {
        selectionMemory[key] = index
    }

    protected fun rememberedSelection(key: String, fallback: Int = 0): Int =
        selectionMemory[key] ?: fallback

    protected fun forgetSelection(key: String? = null) {
        if (key == null) selectionMemory.clear() else selectionMemory.remove(key)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(FF4Theme.BLACK))
        applyImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}