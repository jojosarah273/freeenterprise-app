package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The spoiler's key-item section. The case that matters is the short dot run: the
 * padding shrinks as names grow, and the parser used to stop there.
 */
class Ff4SpoilerTest {

    private val section = """
        HEADER
        KEY ITEM LOCATIONS (and Pass if Pkey)

        Package ........... Starting item
        Earth Crystal ..... Cave Magnes item
        Darkness Crystal .. Sealed Cave item
        Pink Tail ......... (not available)
        Pass .............. (not available)

        --------------------------------------------------------------------------------

        QUEST REWARDS

        Starting item .................................. Package
    """.trimIndent()

    @Test
    fun `every entry is read, including the one with only two dots`() {
        val items = Ff4Spoiler.keyItemLocations(section)
        assertEquals(5, items.size)
        assertEquals("Package" to "Starting item", items[0])
        assertEquals("Darkness Crystal" to "Sealed Cave item", items[2])
    }

    @Test
    fun `the section ends at the next block, not at the first blank line`() {
        val items = Ff4Spoiler.keyItemLocations(section)
        assertEquals("Pass" to "(not available)", items.last())
    }

    @Test
    fun `placeholders are dropped from what the tracker shows`() {
        val usable = Ff4Spoiler.usableKeyItemLocations(section)
        assertEquals(3, usable.size)
        assertEquals(listOf("Starting item", "Cave Magnes item", "Sealed Cave item"), usable.map { it.second })
    }

    @Test
    fun `a spoiler without the section yields nothing`() {
        assertEquals(0, Ff4Spoiler.keyItemLocations("VERSION: v4.7.0.Gale\n\nSEED: X\n").size)
    }
}
