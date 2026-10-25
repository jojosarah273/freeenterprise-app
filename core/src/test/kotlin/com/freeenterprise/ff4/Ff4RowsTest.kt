package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared row definitions. These assertions are the contract with both clients:
 * labels, values, order and enabled state are what Android 0.1.25 shows, so a change
 * here changes both platforms or neither.
 */
class Ff4RowsTest {

    private fun rows(list: List<Ff4Row>) = list.map { it.label to it.value }

    @Test
    fun `main menu, Android`() {
        assertEquals(
            listOf(
                "Play Free Enterprise" to "3 seed(s)",
                "Generate Seed" to "",
                "Check for updates" to "v0.1.25",
                "Settings" to "",
                "Exit" to "",
            ),
            rows(Ff4Rows.mainMenu(3, "0.1.25", includeUpdateAndSettings = true)),
        )
    }

    @Test
    fun `main menu, desktop - no update or settings rows`() {
        assertEquals(
            listOf("Play Free Enterprise" to "1 seed(s)", "Generate Seed" to "", "Exit" to ""),
            rows(Ff4Rows.mainMenu(1, "0.1.25", includeUpdateAndSettings = false)),
        )
    }

    @Test
    fun `session actions carry values only where the app does`() {
        assertEquals(
            listOf(
                "Play Seed" to "",
                "Manage Save" to "present",
                "New seed with these flags" to "same ruleset",
                "Save flags as Flagset" to "",
                "Rename Seed" to "",          // never the file name
                "Delete Seed" to "",
                "Back" to "",
            ),
            rows(Ff4Rows.sessionActions(hasSave = true)),
        )
        assertEquals("none", Ff4Rows.sessionActions(hasSave = false)[1].value)
    }

    @Test
    fun `manage save is import, export, delete`() {
        assertEquals(
            listOf(
                "Import save (.srm)" to "replace",
                "Export save (.srm)" to "",
                "Delete save" to "",
                "Back" to "",
            ),
            rows(Ff4Rows.manageSave(hasSave = true)),
        )
    }

    @Test
    fun `without a save, export and delete are disabled`() {
        val rows = Ff4Rows.manageSave(hasSave = false)
        assertEquals("", rows[0].value)
        assertEquals(false, rows[1].enabled)
        assertEquals(false, rows[2].enabled)
    }

    @Test
    fun `generate screen`() {
        assertEquals(
            listOf(
                "Choose Preset" to "My Preset",
                "Choose Flags" to "",
                "Seed" to "RANDOM1",
                "Generate Seed" to "",
                "Back" to "",
            ),
            rows(Ff4Rows.generate("My Preset", true, "RANDOM1", valid = true)),
        )
        assertEquals("none set", Ff4Rows.generate("", false, "", true)[1].value)
        assertEquals("random", Ff4Rows.generate("", false, "", true)[2].value)
        assertEquals("fix errors", Ff4Rows.generate("", false, "", valid = false)[3].value)
    }

    @Test
    fun `completion screen`() {
        assertEquals(
            listOf(
                "Play Free Enterprise" to "open seeds",
                "Generate Again" to "",
                "Back" to "",
            ),
            rows(Ff4Rows.completed()),
        )
    }

    @Test
    fun `pause menu, with and without a state`() {
        assertEquals(
            listOf(
                "Resume" to "",
                "Save State" to "",
                "Load State" to "load",
                "Fast-forward" to "off",
                "Checks tracker" to "L2",
                "Reset Core" to "",
                "Exit to sessions" to "",
            ),
            rows(Ff4Rows.pauseMenu(hasState = true, fastForward = false, trackerKey = "L2")),
        )
        val none = Ff4Rows.pauseMenu(hasState = false, fastForward = true, trackerKey = "Tab")
        assertEquals("none", none[2].value)
        assertEquals(false, none[2].enabled)
        assertEquals("on", none[3].value)
        assertEquals("Tab", none[4].value)
    }
}
