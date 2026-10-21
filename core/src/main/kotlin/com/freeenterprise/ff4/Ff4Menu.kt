package com.freeenterprise.ff4

/** One row of an FF4 menu. */
data class Ff4MenuItem(
    val label: CharSequence,
    val value: CharSequence? = null,
    val enabled: Boolean = true,
    /** Extra left padding, in SNES pixels, for rows nested under a heading. */
    val indent: Int = 0,
    val subtitle: CharSequence? = null,
    /** Asset names drawn after the subtitle, e.g. the seed's verification icons. */
    val icons: List<String> = emptyList(),
    /** Text drawn after the icons, so they can sit mid-line. */
    val subtitleAfter: CharSequence? = null,
)

/**
 * Selection behaviour for an FF4 list menu: the cursor moves in a loop, skips
 * disabled rows, and lands on the first selectable row when a layer opens.
 *
 * This is the navigation the gamepad and keyboard drive, and it is deliberately
 * separate from drawing: the platform renders the rows, this decides which one is
 * current. Keeping it here means a controller behaves identically on every
 * platform, and the loop-and-skip rules are testable without a screen.
 *
 * The model is pure: it reports whether the selection moved, and the caller fires
 * whatever callbacks or redraws it needs.
 */
class Ff4MenuModel(items: List<Ff4MenuItem>) {

    var items: List<Ff4MenuItem> = items
        private set

    var selected: Int = firstEnabled()
        private set

    val size: Int get() = items.size

    /** The first selectable row, or 0 when every row is disabled. */
    fun firstEnabled(): Int = items.indexOfFirst { it.enabled }.coerceAtLeast(0)

    /**
     * Move by [delta] rows, wrapping and skipping disabled rows, the way the game
     * menu does. Returns true when the selection actually changed.
     */
    fun moveSelection(delta: Int): Boolean {
        if (items.isEmpty() || delta == 0) return false
        val count = items.size
        var next = selected
        repeat(count) {
            next = Math.floorMod(next + delta, count)
            if (items[next].enabled) {
                selected = next
                return true
            }
        }
        return false
    }

    /** Jump to [index] if that row is selectable. Returns true when it moved. */
    fun select(index: Int): Boolean {
        if (index !in items.indices || !items[index].enabled) return false
        if (index == selected) return false
        selected = index
        return true
    }

    /** The row an "activate" press would fire, or null when nothing is selectable. */
    fun activationTarget(): Int? =
        if (items.getOrNull(selected)?.enabled == true) selected else null

    /**
     * Replace the rows, as happens when a layer opens or is rebuilt, and put the
     * cursor on [remembered] if it is still selectable, otherwise on the first
     * selectable row. This is what makes backing out of a submenu return the
     * cursor to the row that opened it.
     */
    fun reset(newItems: List<Ff4MenuItem>, remembered: Int = -1) {
        items = newItems
        selected = if (remembered in newItems.indices && newItems[remembered].enabled) {
            remembered
        } else {
            firstEnabled()
        }
    }
}
