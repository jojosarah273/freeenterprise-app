package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flag editor's arithmetic. These are the rules the website uses, so getting
 * them wrong produces flagsets that are subtly not the ones the player chose.
 */
class Ff4FlagEditorTest {

    private val spec = listOf(
        Ff4FlagSection(
            "KEY ITEMS",
            listOf(
                Ff4FlagControl("@keyitems", "Key items", type = "container", subcontrols = listOf(
                    Ff4FlagControl("Kmain", "Randomized"),
                    Ff4FlagControl("Knofree", "No free Toroia item"),
                )),
                Ff4FlagControl("Kvanilla", "Vanilla"),
            ),
        ),
        Ff4FlagSection(
            "CHARACTERS",
            listOf(
                Ff4FlagControl("@chars", "Characters", type = "container", subcontrols = listOf(
                    Ff4FlagControl("Cvanilla", "Vanilla"),
                    Ff4FlagControl("Cwild", "Wild"),
                )),
            ),
        ),
    )

    @Test
    fun `enabling a child auto-enables its container`() {
        val editor = Ff4FlagEditor(spec)
        editor.toggle("Kmain")

        assertTrue("the section container is enabled", editor.isEnabled("@keyitems"))
        assertTrue(editor.isEnabled("Kmain"))
    }

    @Test
    fun `containers never appear in the flag list`() {
        val editor = Ff4FlagEditor(spec)
        editor.toggle("Kmain")
        editor.toggle("Cwild")

        assertEquals(listOf("Kmain", "Cwild"), editor.rebuildFlagList())
        // The containers are tracked internally - they are what keeps a section open -
        // but they never reach the flag list handed to the engine.
        assertFalse(editor.rebuildFlagList().any { it.startsWith("@") })
    }

    @Test
    fun `the flag list follows the spec order, not the click order`() {
        val editor = Ff4FlagEditor(spec)
        editor.toggle("Cwild")
        editor.toggle("Kmain")

        assertEquals("Kmain Cwild", editor.flagsText())
    }

    @Test
    fun `toggling off removes the flag`() {
        val editor = Ff4FlagEditor(spec)
        editor.toggle("Kmain")
        editor.toggle("Kmain")

        assertEquals(emptyList<String>(), editor.rebuildFlagList())
    }

    @Test
    fun `a select keeps exactly one option`() {
        val editor = Ff4FlagEditor(spec)
        editor.chooseExclusive(listOf("Cvanilla", "Cwild"), "Cwild")
        assertTrue(editor.isEnabled("Cwild"))
        assertFalse(editor.isEnabled("Cvanilla"))

        editor.chooseExclusive(listOf("Cvanilla", "Cwild"), "Cvanilla")
        assertTrue(editor.isEnabled("Cvanilla"))
        assertFalse(editor.isEnabled("Cwild"))
    }

    @Test
    fun `applying a preset adds its flags`() {
        val editor = Ff4FlagEditor(spec)
        editor.addAll(listOf("Kmain", "Cwild"))

        assertEquals("Kmain Cwild", editor.flagsText())
    }

    @Test
    fun `clearing empties the set`() {
        val editor = Ff4FlagEditor(spec)
        editor.addAll(listOf("Kmain", "Cwild"))
        editor.clear()

        assertEquals("", editor.flagsText())
    }

    @Test
    fun `doors and entrances randomization are hidden`() {
        assertTrue(Ff4FlagEditor.isHidden("-doorsrando:basic"))
        assertTrue(Ff4FlagEditor.isHidden("@entrancesrando"))
        assertFalse(Ff4FlagEditor.isHidden("Kmain"))
        assertTrue(Ff4FlagEditor.usesHiddenFeature("Kmain -doorsrando:basic"))
        assertFalse(Ff4FlagEditor.usesHiddenFeature("Kmain Cvanilla"))
    }
}
