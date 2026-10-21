package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check counter, against the same switch array the engine counts. */
class Ff4ChecksTest {

    private val base = Ff4KeyItems.SWITCHES_OFFSET

    private fun checks() = listOf(
        Ff4Checks.Check(0, "Overworld", "Baron Town", "grass, top"),
        Ff4Checks.Check(1, "Overworld", "Baron Town", "grass, left"),
        Ff4Checks.Check(8, "Underworld", "Dwarf Castle", "chest"),
        Ff4Checks.Check(9, "Moon", "Lunar Path", "chest"),
    )

    @Test
    fun `bits are read least significant first`() {
        val wram = ByteArray(0x800)
        wram[base] = 0x01
        assertTrue(Ff4Checks.isChecked(wram, base, 0))
        assertFalse(Ff4Checks.isChecked(wram, base, 1))

        wram[base + 1] = 0x01
        assertTrue("bit 8 is the first bit of the second byte", Ff4Checks.isChecked(wram, base, 8))
    }

    @Test
    fun `progress counts by area and world, most remaining first`() {
        val wram = ByteArray(0x800)
        wram[base] = 0x03.toByte()   // both Baron Town checks done

        val progress = Ff4Checks.progress(checks(), wram, base)

        assertEquals(2, progress.opened)
        assertEquals(4, progress.total)
        assertEquals(2 to 2, progress.byWorld["Overworld"])
        assertEquals(0 to 1, progress.byWorld["Underworld"])
        assertEquals("an area with nothing left sorts last", "Dwarf Castle", progress.byArea.first().first)
    }

    @Test
    fun `a short buffer is not over-read`() {
        val progress = Ff4Checks.progress(checks(), ByteArray(4), base)
        assertEquals(0, progress.opened)
        assertEquals(4, progress.total)
    }
}
