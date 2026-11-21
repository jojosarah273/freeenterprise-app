package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real spoiler from the 4.6.0-GOLBEZ engine (seed TESTGOLBEZ, Kmain), generated
 * through fe_bridge with that engine's own tree. The section format is the same as
 * 4.7's - same dot-run padding, same "(not available)" marker - so one parser serves
 * every engine. This fixture is verbatim from that run; if a fork changes its spoiler
 * format, this is the test that says so.
 */
class Ff4SpoilerGolbezTest {

    private val section = """
    KEY ITEM LOCATIONS (and Pass if Pkey)
    
    Package ........... Starting item
    SandRuby .......... Super Cannon destruction item
    Baron Key ......... Dwarf Castle/Luca item
    TwinHarp .......... Pan trade item (Spoon slot)
    Earth Crystal ..... Sealed Cave item
    Magma Key ......... Mt. Ordeals item
    Tower Key ......... Zot item
    Hook .............. Found Yang item (Pan slot)
    Luca Key .......... Baron Inn item
    Darkness Crystal .. Cave Magnes item
    Rat Tail .......... Antlion Nest item
    Pan ............... Town of Monsters chest item
    Crystal ........... Defend Fabul reward item
    Legend Sword ...... Rat Tail trade item
    Adamant ........... Lower Bab-il item (Tower Key slot)
    Spoon ............. Baron Castle item
    Pink Tail ......... Edward/Toroia item
    Pass .............. (not available)
    
    --------------------------------------------------------------------------------
""".trimIndent()

    @Test
    fun `the Golbez engine key items parse like 4-7`() {
        val items = Ff4Spoiler.keyItemLocations(section)
        // 18 entries, Pass being "(not available)" - the 4.6 Kmain layout.
        assertEquals(18, items.size)
        assertEquals("Package" to "Starting item", items[0])
        assertEquals("Darkness Crystal" to "Cave Magnes item", items[9])
        assertEquals("Legend Sword" to "Rat Tail trade item", items[13])
        assertEquals("Pass" to "(not available)", items[17])
    }

    @Test
    fun `nothing from the Golbez section is lost`() {
        val items = Ff4Spoiler.keyItemLocations(section)
        assertTrue(items.all { it.first.isNotBlank() && it.second.isNotBlank() })
    }
}
