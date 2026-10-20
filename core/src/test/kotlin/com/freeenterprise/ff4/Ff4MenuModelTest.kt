package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation rules a controller drives. These are behavioural, not cosmetic:
 * wrap-around, skipping disabled rows, and returning the cursor to the row that
 * opened a submenu are the things a player feels immediately if they break.
 */
class Ff4MenuModelTest {

    private fun item(label: String, enabled: Boolean = true) = Ff4MenuItem(label, enabled = enabled)

    private fun model(vararg items: Ff4MenuItem) = Ff4MenuModel(items.toList())

    @Test
    fun `the cursor opens on the first selectable row`() {
        assertEquals(1, model(item("a", false), item("b"), item("c")).selected)
    }

    @Test
    fun `the cursor wraps in both directions`() {
        val menu = model(item("a"), item("b"), item("c"))

        menu.moveSelection(1)
        menu.moveSelection(1)
        assertEquals(2, menu.selected)
        menu.moveSelection(1)
        assertEquals("past the last row comes the first", 0, menu.selected)

        menu.moveSelection(-1)
        assertEquals("before the first row comes the last", 2, menu.selected)
    }

    @Test
    fun `disabled rows are skipped in both directions`() {
        val menu = model(item("a"), item("b", enabled = false), item("c"))

        menu.moveSelection(1)
        assertEquals("over the disabled row", 2, menu.selected)

        menu.moveSelection(1)
        assertEquals(0, menu.selected)

        menu.moveSelection(-1)
        assertEquals("back over the disabled row without landing on it", 2, menu.selected)
    }

    @Test
    fun `a menu with nothing selectable stays put and reports no move`() {
        val menu = model(item("a", enabled = false), item("b", enabled = false))

        assertFalse(menu.moveSelection(1))
        assertFalse(menu.moveSelection(-1))
        assertEquals(0, menu.selected)
        assertNull(menu.activationTarget())
    }

    @Test
    fun `select refuses disabled rows and out of range indexes`() {
        val menu = model(item("a"), item("b", enabled = false))

        assertFalse(menu.select(1))
        assertFalse(menu.select(7))
        assertFalse(menu.select(-1))
        assertEquals(0, menu.selected)
    }

    @Test
    fun `select reports no movement when already there`() {
        val menu = model(item("a"), item("b"))
        assertFalse("already on row 0", menu.select(0))
    }

    @Test
    fun `activate targets the selected row only when it is selectable`() {
        val menu = model(item("a"), item("b"))
        assertEquals(0, menu.activationTarget())

        menu.moveSelection(1)
        assertEquals(1, menu.activationTarget())
    }

    @Test
    fun `reset returns the cursor to the remembered row`() {
        val menu = model(item("a"), item("b"), item("c"))
        menu.reset(menu.items, remembered = 2)
        assertEquals(2, menu.selected)
    }

    @Test
    fun `reset falls back to the first selectable row when the remembered one is gone`() {
        val menu = model(item("a"), item("b"), item("c"))

        // Remembered row 2, but the new layer is shorter.
        menu.reset(listOf(item("x"), item("y")), remembered = 2)
        assertEquals(0, menu.selected)

        // Remembered row is present but disabled.
        menu.reset(listOf(item("x"), item("y", false), item("z")), remembered = 1)
        assertEquals(0, menu.selected)
    }

    @Test
    fun `an empty menu is inert`() {
        val menu = Ff4MenuModel(emptyList())
        assertFalse(menu.moveSelection(1))
        assertNull(menu.activationTarget())
        assertEquals(0, menu.size)
    }
}
