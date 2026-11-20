package com.freeenterprise.ff4

/**
 * Multi-touch gamepad logic, without Android in it.
 *
 * The old on-screen pad was one view per button, which puts correctness in the
 * platform's hands: whether two buttons can be held at once depends on motion-event
 * splitting, and sliding a thumb across the d-pad does nothing, because each view
 * only ever sees the gesture it started with. Every real phone pad works the other
 * way - one surface, many pointers - so that logic lives here, where it can be tested
 * without a device:
 *
 *  * every pointer is tracked separately, so holding B while steering works by
 *    construction rather than by layout luck;
 *  * a pointer that moves is re-resolved, so a thumb can roll around the d-pad or
 *    from A to B without lifting;
 *  * two fingers on the same button are reference-counted, so lifting one does not
 *    drop the other's press;
 *  * taps fire on lift, and only if the finger is still in the zone it came down in,
 *    which is what "I didn't mean to press that" undoing feels like on a phone.
 */

/** A zone a pointer can be in. Ids are the caller's - here they are libretro button ids. */
sealed class Ff4TouchZone {

    /** Which id a point resolves to, or null when it hits nothing. */
    abstract fun hit(x: Float, y: Float): Int?

    /**
     * Four directions around a centre: the whole square is live, so there are no dead
     * corners at the diagonals like separate buttons have. [dead] is the neutral area
     * around the centre - the "no direction" zone.
     */
    class Cross(
        val cx: Float,
        val cy: Float,
        val arm: Float,
        val dead: Float,
        val up: Int,
        val down: Int,
        val left: Int,
        val right: Int,
    ) : Ff4TouchZone() {
        override fun hit(x: Float, y: Float): Int? {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy > (arm * 2) * (arm * 2)) return null
            if (dx * dx + dy * dy < dead * dead) return null
            return if (dx * dx > dy * dy) {
                if (dx < 0) left else right
            } else {
                if (dy < 0) up else down
            }
        }
    }

    /** Round buttons - a d-pad of circles. Nearest centre wins. */
    class Face(
        val buttons: List<Button>,
    ) : Ff4TouchZone() {
        data class Button(val cx: Float, val cy: Float, val radius: Float, val id: Int)

        override fun hit(x: Float, y: Float): Int? {
            var best: Button? = null
            var bestDist = Float.MAX_VALUE
            for (b in buttons) {
                val d = (x - b.cx) * (x - b.cx) + (y - b.cy) * (y - b.cy)
                if (d <= b.radius * b.radius && d < bestDist) {
                    best = b
                    bestDist = d
                }
            }
            return best?.id
        }
    }

    /** A rectangular strip - console rows (L · R · SELECT · START) and app rows. */
    class Row(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val id: Int,
    ) : Ff4TouchZone() {
        override fun hit(x: Float, y: Float): Int? =
            if (x in left..right && y in top..bottom) id else null
    }
}

/**
 * Tracks pointers across a touch surface and emits button transitions.
 *
 * [resolve] maps a point to a zone id (null = nothing there); [isTap] separates
 * momentary rows (menu-like: TRK, FF, SAVE, START) from held buttons (d-pad, face,
 * L/R/SELECT). Taps do not press on touch-down - they fire on lift, in place.
 */
class Ff4TouchTracker(
    private val resolve: (Float, Float) -> Int?,
    private val isTap: (Int) -> Boolean,
) {
    var onButton: (id: Int, pressed: Boolean) -> Unit = { _, _ -> }
    var onTap: (id: Int) -> Unit = { }

    /** What each pointer is over right now. */
    private val zoneOf = HashMap<Int, Int?>()
    /** What each pointer touched down on, for tap cancellation. */
    private val downZone = HashMap<Int, Int?>()
    /** Held button id -> the pointers keeping it down. */
    private val owners = HashMap<Int, MutableSet<Int>>()

    /** The held buttons, for drawing a pressed state. */
    fun held(): Set<Int> = owners.keys.toSet()

    fun down(pointer: Int, x: Float, y: Float) {
        val zone = resolve(x, y)
        zoneOf[pointer] = zone
        downZone[pointer] = zone
        press(pointer, zone)
    }

    fun move(pointer: Int, x: Float, y: Float) {
        val previous = zoneOf[pointer]
        val zone = resolve(x, y)
        if (zone == previous) return
        zoneOf[pointer] = zone
        release(pointer, previous)
        press(pointer, zone)
    }

    fun up(pointer: Int) {
        val zone = zoneOf.remove(pointer)
        val cameDownOn = downZone.remove(pointer)
        release(pointer, zone)
        // A tap counts only if the finger is still where it landed.
        if (zone != null && zone == cameDownOn && isTap(zone)) onTap(zone)
    }

    /** The surface is gone (or a system gesture stole the pointers): drop everything. */
    fun cancel() {
        for (pointer in zoneOf.keys.toList()) release(pointer, zoneOf[pointer])
        zoneOf.clear()
        downZone.clear()
        owners.values.forEach { it.clear() }
        owners.clear()
    }

    private fun press(pointer: Int, zone: Int?) {
        if (zone == null || isTap(zone)) return
        val set = owners.getOrPut(zone) { mutableSetOf() }
        if (set.add(pointer) && set.size == 1) onButton(zone, true)
    }

    private fun release(pointer: Int, zone: Int?) {
        if (zone == null || isTap(zone)) return
        val set = owners[zone] ?: return
        set.remove(pointer)
        if (set.isEmpty()) {
            owners.remove(zone)
            onButton(zone, false)
        }
    }
}
