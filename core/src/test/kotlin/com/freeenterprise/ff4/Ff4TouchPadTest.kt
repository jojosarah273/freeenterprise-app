package com.freeenterprise.ff4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-screen pad's input logic: one surface, many pointers.
 *
 * The old pad was one view per button, so its behaviour lived in Android's
 * motion-event splitting and could not be tested. Every case here is something a
 * player's thumbs actually do.
 */
class Ff4TouchPadTest {

    private val taps = setOf(100, 101)   // TRK, START in this test's vocabulary

    private val zones = listOf(
        // d-pad centred at (100, 100), arm 40, dead centre 10
        Ff4TouchZone.Cross(100f, 100f, 40f, 10f, up = 4, down = 5, left = 6, right = 7),
        // face buttons: A at (210, 120), B at (190, 140), generous radii
        Ff4TouchZone.Face(
            listOf(
                Ff4TouchZone.Face.Button(210f, 120f, 18f, 8),
                Ff4TouchZone.Face.Button(190f, 140f, 18f, 0),
            ),
        ),
        // a console row: SELECT (hold) and START (tap)
        Ff4TouchZone.Row(0f, 0f, 40f, 20f, 2),
        Ff4TouchZone.Row(40f, 0f, 80f, 20f, 100),
    )

    private fun tracker(): Pair<Ff4TouchTracker, MutableList<String>> {
        val events = mutableListOf<String>()
        val t = Ff4TouchTracker(
            resolve = { x, y -> zones.firstNotNullOfOrNull { it.hit(x, y) } },
            isTap = { it in taps },
        )
        t.onButton = { id, pressed -> events.add(if (pressed) "+$id" else "-$id") }
        t.onTap = { id -> events.add("tap$id") }
        return t to events
    }

    @Test
    fun `press and release a direction`() {
        val (t, e) = tracker()
        t.down(0, 100f, 60f)      // up
        t.up(0)
        assertEquals(listOf("+4", "-4"), e)
    }

    @Test
    fun `a thumb rolls around the d-pad without lifting`() {
        val (t, e) = tracker()
        t.down(0, 100f, 60f)      // up
        t.move(0, 140f, 100f)     // right
        t.move(0, 100f, 140f)     // down
        t.move(0, 60f, 100f)      // left
        t.up(0)
        assertEquals(listOf("+4", "-4", "+7", "-7", "+5", "-5", "+6", "-6"), e)
    }

    @Test
    fun `two pointers hold and steer at once`() {
        val (t, e) = tracker()
        t.down(0, 100f, 60f)     // d-pad up
        t.down(1, 190f, 140f)     // B
        t.move(0, 140f, 100f)    // steer right while B stays down
        t.up(1)                   // lift B
        t.up(0)
        assertEquals(listOf("+4", "+0", "-4", "+7", "-0", "-7"), e)
    }

    @Test
    fun `two fingers on one button are reference counted`() {
        val (t, e) = tracker()
        t.down(0, 190f, 140f)    // first finger on B
        t.down(1, 191f, 141f)    // second finger on B
        t.up(0)                   // lifting one must not release the other's press
        assertEquals(listOf("+0"), e)
        t.up(1)
        assertEquals(listOf("+0", "-0"), e)
    }

    @Test
    fun `sliding off a button releases it`() {
        val (t, e) = tracker()
        t.down(0, 190f, 140f)    // B
        t.move(0, 400f, 400f)    // off everything
        assertEquals(listOf("+0", "-0"), e)
    }

    @Test
    fun `sliding between face buttons transfers the press`() {
        val (t, e) = tracker()
        t.down(0, 190f, 140f)    // B
        t.move(0, 210f, 120f)    // onto A
        t.up(0)
        assertEquals(listOf("+0", "-0", "+8", "-8"), e)
    }

    @Test
    fun `the dead centre is neutral`() {
        val (t, e) = tracker()
        t.down(0, 100f, 100f)
        t.move(0, 400f, 400f)
        assertEquals(emptyList<String>(), e)
    }

    @Test
    fun `a tap fires on lift, and only if the finger stayed`() {
        val (t, e) = tracker()
        t.down(0, 60f, 10f)     // START
        t.up(0)
        assertEquals(listOf("tap100"), e)

        val (t2, e2) = tracker()
        t2.down(1, 60f, 10f)     // START, then slide away
        t2.move(1, 400f, 400f)
        t2.up(1)
        assertEquals(listOf<String>(), e2)
    }

    @Test
    fun `cancel drops everything, held state included`() {
        val (t, e) = tracker()
        t.down(0, 100f, 60f)
        t.down(1, 190f, 140f)
        t.cancel()
        assertEquals(listOf("+4", "+0", "-4", "-0"), e)
        assertTrue(t.held().isEmpty())
    }

    @Test
    fun `held reports what is down, for drawing`() {
        val (t, _) = tracker()
        t.down(0, 100f, 60f)
        t.down(1, 190f, 140f)
        assertEquals(setOf(4, 0), t.held())
    }
}
