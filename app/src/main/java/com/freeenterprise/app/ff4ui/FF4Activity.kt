package com.freeenterprise.app.ff4ui

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.RoundedCorner
import androidx.annotation.RequiresApi
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

    /**
     * Whether this screen keeps a small gap between its windows and the physical
     * screen edge. Menus do; the emulator surface does not, because the game is
     * meant to fill the display.
     */
    protected open val keepsScreenEdgeBuffer: Boolean = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        applyImmersive()
        if (keepsScreenEdgeBuffer) applyEdgeBuffer()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    /**
     * Inset the content root from the screen edge so a rounded display corner
     * cannot cut through a window border.
     *
     * The layouts already carry a 6dp inset, but that is smaller than the corner
     * radius of most phones (roughly 10-24dp), so the corner of the box was still
     * clipped by the physical arc. Match the device's own radius where it reports
     * one, and keep a small floor and ceiling around it.
     */
    protected fun applyEdgeBuffer() {
        val root = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? View ?: return
        val inset = maxOf(dp(8), minOf(deviceCornerRadiusPx(), dp(24)))
        if (root.paddingLeft != inset) root.setPadding(inset, inset, inset, inset)
    }

    private fun deviceCornerRadiusPx(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) reportedCornerRadiusPx() else 0

    /** The display's largest corner radius in px; 0 when the device does not say. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun reportedCornerRadiusPx(): Int {
        val insets = window?.decorView?.rootWindowInsets ?: return 0
        var largest = 0
        for (position in intArrayOf(
            RoundedCorner.POSITION_TOP_LEFT,
            RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_LEFT,
            RoundedCorner.POSITION_BOTTOM_RIGHT,
        )) {
            val radius = insets.getRoundedCorner(position)?.radius ?: 0
            if (radius > largest) largest = radius
        }
        return largest
    }

    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}