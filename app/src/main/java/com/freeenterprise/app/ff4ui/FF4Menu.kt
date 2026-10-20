package com.freeenterprise.app.ff4ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.freeenterprise.ff4.Ff4MenuItem
import com.freeenterprise.ff4.Ff4MenuModel

/**
 * FF4-style menu: a vertical list of rows with a blinking ">" cursor, driven by
 * gamepad/keyboard (D-pad + A/Enter) and touch. Selected rows use the brighter
 * blue fill; values are shown right-aligned in the game's yellow.
 */
class FF4Menu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    data class Item(
        val label: CharSequence,          // CharSequence so icons can be embedded
        val value: CharSequence? = null,
        val enabled: Boolean = true,
        val indent: Int = 0,
        val subtitle: CharSequence? = null,
    )

    private class RowViews(
        val root: LinearLayout,
        val cursor: ImageView,
        val label: TextView,
        val subtitle: TextView?,
        val value: TextView,
    )

    private val rows = mutableListOf<RowViews>()
    private var items: List<Item> = emptyList()
    private var onActivate: ((Int) -> Unit)? = null

    /**
     * Which row is current, and the rules for moving it, live in the portable core
     * so a gamepad behaves identically on every platform. This view renders the
     * rows; the model decides the selection.
     */
    private val model = Ff4MenuModel(emptyList())

    var selected: Int = 0
        private set

    var onSelectionChanged: ((Int) -> Unit)? = null

    private var pointerBitmap: Bitmap? = null

    init {
        orientation = VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun submit(items: List<Item>, initialSelected: Int = -1, onActivate: (Int) -> Unit) {
        this.items = items
        this.onActivate = onActivate
        rebuild()
        model.reset(items.map { it.toCoreItem() }, initialSelected)
        selected = model.selected
        updateRowStyles()
        ensureSelectionVisible()
        // Make sure the pointer and focus land here when a menu is (re)shown,
        // e.g. after backing out of a deeper layer.
        requestFocus()
    }

    fun moveSelection(delta: Int) {
        if (!model.moveSelection(delta)) return
        selected = model.selected
        updateRowStyles()
        onSelectionChanged?.invoke(selected)
        ensureSelectionVisible()
    }

    fun activate() {
        model.activationTarget()?.let { onActivate?.invoke(it) }
    }

    fun select(index: Int) {
        if (!model.select(index)) return
        selected = model.selected
        updateRowStyles()
        onSelectionChanged?.invoke(selected)
        ensureSelectionVisible()
    }

    /** Map to the portable model's item, so navigation rules are shared. */
    private fun Item.toCoreItem() = Ff4MenuItem(
        label = label,
        value = value,
        enabled = enabled,
        indent = indent,
        subtitle = subtitle,
    )

    /** Scroll the nearest ancestor ScrollView so the selected row is visible. */
    private fun ensureSelectionVisible() {
        val row = rows.getOrNull(selected)?.root ?: return
        var parent: android.view.ViewParent? = this.parent
        while (parent != null) {
            if (parent is android.widget.ScrollView) {
                val target = (row.top - (parent.height - row.height) / 2)
                    .coerceAtLeast(0)
                parent.smoothScrollTo(0, target)
                return
            }
            parent = parent.parent
        }
    }

    /** The game's own POINTER.png, scaled to our pixel grid, nearest-neighbour. */
    private fun pointer(): Bitmap {
        pointerBitmap?.let { return it }
        val unit = FF4Theme.unit(context)
        val src = try {
            context.assets.open("ff4/pointer.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val w = (16 * unit).toInt().coerceAtLeast(1)
        val h = (13 * unit).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, false)
        pointerBitmap = scaled
        return scaled
    }

    private fun rebuild() {
        removeAllViews()
        rows.clear()
        val unit = FF4Theme.unit(context)
        for ((i, item) in items.withIndex()) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (unit * (2 + item.indent * 3)).toInt(), (unit * 2).toInt(),
                    (unit * 2).toInt(), (unit * 2).toInt()
                )
            }
            val cursor = ImageView(context).apply {
                setImageBitmap(pointer())
                visibility = if (i == selected) View.VISIBLE else View.INVISIBLE
                layoutParams = LayoutParams(
                    (16 * unit).toInt(), (13 * unit).toInt()
                ).apply { marginEnd = unit.toInt() }
                setColorFilter(null)
            }
            val label = TextView(context).apply {
                text = item.label
                FF4Theme.applyText(context, this)
                setTextColor(if (item.enabled) FF4Theme.WHITE else FF4Theme.GREY)
            }
            val labelColumn = LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            labelColumn.addView(label)
            var subtitle: TextView? = null
            if (!item.subtitle.isNullOrBlank()) {
                subtitle = TextView(context).apply {
                    text = item.subtitle
                    FF4Theme.applyText(context, this, FF4Theme.textSmallPx(context))
                    setTextColor(FF4Theme.GREY)
                }
                labelColumn.addView(subtitle)
            }
            val value = TextView(context).apply {
                text = item.value ?: ""
                FF4Theme.applyText(context, this)
                setTextColor(if (item.enabled) FF4Theme.YELLOW else FF4Theme.GREY)
            }
            row.addView(cursor)
            row.addView(labelColumn)
            row.addView(value)
            row.setOnClickListener {
                requestFocus()
                select(i)
                activate()
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            rows.add(RowViews(row, cursor, label, subtitle, value))
        }
    }

    private fun updateRowStyles() {
        for ((i, row) in rows.withIndex()) {
            val sel = i == selected
            row.root.setBackgroundColor(if (sel) FF4Theme.BLUE_SELECTED else Color.TRANSPARENT)
            row.label.setTextColor(if (!items[i].enabled) FF4Theme.GREY else FF4Theme.WHITE)
        }
        updateCursor()
    }

    private fun updateCursor() {
        for ((i, row) in rows.withIndex()) {
            // Static pointer — no blinking.
            row.cursor.visibility = if (i == selected) View.VISIBLE else View.INVISIBLE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { moveSelection(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveSelection(1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_SPACE -> { activate(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }
}