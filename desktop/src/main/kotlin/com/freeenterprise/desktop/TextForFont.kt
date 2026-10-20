package com.freeenterprise.desktop

/**
 * The game font carries 99 code points: ASCII and little else. Every non-ASCII
 * character the app's own text uses -- the middle dot in status lines, the tick on
 * a completed check, the arrows in the hint bar -- is absent from it.
 *
 * Android hides this: its text stack silently substitutes a glyph from a system
 * font, so a tick appears in a different typeface, mid-sentence. A single AWT font
 * has no such fallback and draws a missing-glyph box instead.
 *
 * So the desktop substitutes characters the font actually has. The important part
 * is that *measurement and drawing use the same substitution*: the layout is built
 * from advance widths, and if the measurer counted a character the renderer
 * replaced, boxes and right-aligned values would drift.
 *
 * The substitutes are all in the font's ASCII range, and chosen to read similarly:
 * a dash for the middle dot, a star for a completed check, angle brackets for the
 * page arrows.
 *
 * Android is left alone for now, because its fallback does display these
 * characters and the current look has been signed off. Unifying the two -- by
 * moving this mapping into the core, or by putting fallback glyphs in the atlas --
 * is a deliberate change to the Android appearance and should be decided, not
 * slipped in.
 */
object TextForFont {

    private val substitutes = mapOf(
        '\u00b7' to '-',      // middle dot
        '\u2713' to '*',      // check mark
        '\u2717' to 'x',      // ballot X
        '\u25c0' to '<',      // left triangle
        '\u25b6' to '>',      // right triangle
        '\u2014' to '-',      // em dash
        '\u2013' to '-',      // en dash
        '\u2026' to '.',      // ellipsis (one dot; the caller's spacing carries it)
        '\u2018' to '\'', '\u2019' to '\'',   // curly quotes
        '\u201c' to '"', '\u201d' to '"',
    )

    /**
     * Rewrite [text] so every character exists in the game font. Applied by both
     * the measurer and the renderer, so the two cannot disagree.
     */
    fun apply(text: CharSequence): String {
        val s = text.toString()
        var needed = false
        for (ch in s) if (ch.code > 0x7E || ch.code < 0x20) { needed = true; break }
        if (!needed) return s

        val out = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch.code in 0x20..0x7E -> out.append(ch)
                substitutes.containsKey(ch) -> out.append(substitutes.getValue(ch))
                else -> out.append('?')   // visible, and obvious that it needs a mapping
            }
        }
        return out.toString()
    }
}
