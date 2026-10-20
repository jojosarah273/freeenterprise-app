package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key-item table and the flag decoding. This is the mapping the tracker's whole
 * first page rests on, and it is shared by three builds, so it is pinned here.
 */
class Ff4KeyItemsTest {

    @Test
    fun `there are seventeen items, indexed in engine order`() {
        assertEquals(17, Ff4KeyItems.count)
        assertEquals((0..16).toList(), Ff4KeyItems.entries.map { it.index })
    }

    @Test
    fun `the four borrowed item ids are in the table`() {
        assertEquals(0x19, Ff4KeyItems.entryFor("Legend Sword")!!.itemCode)
        assertEquals(0x3E, Ff4KeyItems.entryFor("Spoon")!!.itemCode)
        assertEquals(0xC8, Ff4KeyItems.entryFor("Crystal")!!.itemCode)
        assertEquals(0xFC, Ff4KeyItems.entryFor("Hook")!!.itemCode)
    }

    @Test
    fun `spoiler names resolve, including the darkness alias`() {
        assertEquals(10, Ff4KeyItems.entryFor("Darkness Crystal")!!.index)
        assertEquals(10, Ff4KeyItems.entryFor("dark crystal")!!.index)
        assertEquals(4, Ff4KeyItems.entryFor("TwinHarp")!!.index)
        assertNull("Pass is not one of the engine's tracked items", Ff4KeyItems.entryFor("Pass"))
    }

    @Test
    fun `a found bit is read least significant first`() {
        val wram = ByteArray(0x800)

        wram[Ff4KeyItems.FOUND_OFFSET] = 0x04              // bit 2
        assertTrue(Ff4KeyItems.isFound(wram, 2))
        assertEquals(1, Ff4KeyItems.foundCount(wram))

        wram[Ff4KeyItems.FOUND_OFFSET + 2] = 0x01          // bit 16
        assertTrue(Ff4KeyItems.isFound(wram, 16))
        assertEquals(2, Ff4KeyItems.foundCount(wram))
    }

    @Test
    fun `a short buffer cannot be over-read`() {
        val wram = ByteArray(4)
        assertEquals(0, Ff4KeyItems.foundCount(wram))
        assertEquals(0, Ff4KeyItems.switchesSet(wram))
    }

    @Test
    fun `switches count the bits the checks page counts`() {
        val wram = ByteArray(0x800)
        wram[Ff4KeyItems.SWITCHES_OFFSET] = 0xFF.toByte()
        wram[Ff4KeyItems.SWITCHES_OFFSET + 1] = 0x01
        assertEquals(9, Ff4KeyItems.switchesSet(wram))
    }
}
