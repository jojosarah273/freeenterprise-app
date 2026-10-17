package com.freeenterprise.app.ff4ui

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

/**
 * An FF4-style modal: a message window above an options window, driven by the
 * same pointer menu + gamepad handling. Used for confirmations; text entry still
 * uses a system dialog so the on-screen keyboard appears.
 */
class FF4Dialog(
    context: Context,
    private val title: String,
    private val message: String,
    private val options: List<String>,
    private val onChoice: (Int) -> Unit,
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(FF4Theme.BLACK))
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val pad = FF4Theme.contentPadding(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FF4Theme.BLACK)
            setPadding(pad, pad, pad, pad)
        }

        // Message window
        val messageWindow = FF4Window(context)
        val messageView = TextView(context).apply {
            text = if (title.isBlank()) message else "$title\n$message"
            FF4Theme.applyText(context, this)
            setTextColor(FF4Theme.WHITE)
        }
        messageWindow.addView(messageView)
        root.addView(
            messageWindow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // Options window
        val menuWindow = FF4Window(context)
        val menu = FF4Menu(context)
        menu.submit(options.map { FF4Menu.Item(it) }) { index ->
            dismiss()
            onChoice(index)
        }
        menuWindow.addView(
            menu,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            menuWindow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad }
        )

        setContentView(root)
        menu.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            dismiss()
            onChoice(options.size - 1)   // last option is the cancel choice
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        /**

         * The last option is treated as Cancel (used for B/Back).

         */
        fun confirm(
            context: Context,
            title: String,
            message: String,
            yes: String = "Yes",
            no: String = "No",
            onYes: () -> Unit,
            onNo: () -> Unit = {},
        ) {
            FF4Dialog(context, title, message, listOf(yes, no)) { index ->
                if (index == 0) onYes() else onNo()
            }.show()
        }
    }
}