package com.freeenterprise.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against missing-glyph boxes on screen.
 *
 * The game font has 99 code points and no fallback, so any character it lacks is
 * drawn as a box unless it is substituted first. These are the strings the
 * interface actually contains, including the punctuation that used to show up as a
 * box on the desktop.
 */
class TextForFontTest {

    private val interfaceStrings = listOf(
        "Free Enterprise 4.7.0.Gale",
        "3 seed(s) ready",
        "A  Select      B  Exit",
        "Shuffling treasures  \u00b7  45%",       // middle dot, as the overlay writes it
        "\u2713 Starting item",                    // check mark, as the tracker writes it
        "\u00b7 Antlion Nest item",
        "\u25c0 \u25b6  page      L2 / B  close",  // page arrows in the tracker hint
        "Flags: Kmain Fvanilla \u2014 valid",      // em dash in the flag editor
        "\u201cquoted\u201d and \u2018single\u2019",
    )

    @Test
    fun `every character the interface draws exists in the font`() {
        for (original in interfaceStrings) {
            for (ch in TextForFont.apply(original)) {
                assertTrue(
                    "U+%04X (%s) is not in the font, from: %s".format(ch.code, ch, original),
                    Assets.baseFont.canDisplay(ch),
                )
            }
        }
    }

    @Test
    fun `plain ascii is left untouched`() {
        assertEquals("Free Enterprise 4.7.0.Gale", TextForFont.apply("Free Enterprise 4.7.0.Gale"))
    }

    @Test
    fun `an unmapped character becomes a visible question mark, never a box`() {
        assertEquals("?", TextForFont.apply("\u4e2d"))   // a CJK character, surely absent
    }

    @Test
    fun `the substitutions read as intended`() {
        assertEquals("-", TextForFont.apply("\u00b7"))
        assertEquals("*", TextForFont.apply("\u2713"))
        assertEquals("< >", TextForFont.apply("\u25c0 \u25b6"))
    }
}
