package com.freeenterprise.app.ff4ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import com.freeenterprise.app.BuildConfig
import com.freeenterprise.app.R

/** Base for FF4 screens with a status window, a scrolling menu and a hint bar. */
abstract class FF4MenuActivity : FF4Activity() {

    protected lateinit var menu: FF4Menu
    protected lateinit var hint: TextView
    private lateinit var statusWindow: FF4Window
    protected lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ff4_menu)

        menu = findViewById(R.id.ff4Menu)
        hint = findViewById(R.id.ff4Hint)
        statusWindow = findViewById(R.id.ff4StatusWindow)
        statusText = findViewById(R.id.ff4StatusText)

        FF4Theme.applyText(this, hint, FF4Theme.textSmallPx(this))
        FF4Theme.applyText(this, statusText, FF4Theme.textSmallPx(this))

        // Small version indicator at the far right of the hint box.
        findViewById<TextView>(R.id.ff4Version)?.let { v ->
            v.text = "v" + BuildConfig.VERSION_NAME
            FF4Theme.applyText(this, v, FF4Theme.textSmallPx(this))
        }
        menu.requestFocus()
    }

    protected fun setStatus(text: CharSequence?, color: Int = FF4Theme.WHITE) {
        if (text.isNullOrBlank()) {
            statusWindow.visibility = View.GONE
            return
        }
        statusWindow.visibility = View.VISIBLE
        statusText.text = text
        statusText.setTextColor(color)
    }

    protected fun setHint(text: String) {
        hint.text = text
    }

    /**
     * Populate the menu, restoring the cursor to the row that previously opened
     * this layer (keyed by [key]) and taking focus.
     */
    protected fun submitMenu(key: String, items: List<FF4Menu.Item>, onActivate: (Int) -> Unit) {
        val initial = rememberedSelection(key)
        menu.submit(items, initial) { index ->
            selectionMemory[key] = index      // row that leads deeper / was toggled
            onActivate(index)
        }
        menu.requestFocus()
    }



    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (onBackRequested()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Return true if handled; default finishes the screen. */
    protected open fun onBackRequested(): Boolean {
        finish()
        return true
    }
}