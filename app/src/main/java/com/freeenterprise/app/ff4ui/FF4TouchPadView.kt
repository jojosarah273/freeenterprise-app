package com.freeenterprise.app.ff4ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.freeenterprise.ff4.Ff4TouchTracker
import com.freeenterprise.ff4.Ff4TouchZone

/**
 * The on-screen pad, as one surface.
 *
 * The previous pad was a TextView per button: sliding a thumb across the d-pad did
 * nothing (each view only sees the gesture it started with), holding two buttons
 * depended on Android's motion-event splitting, and the corners between d-pad
 * buttons were dead. This view takes every pointer itself, resolves them against
 * zone geometry, and emits transitions through [Ff4TouchTracker] - whose logic is
 * pure Kotlin in :core and unit tested.
 *
 * Drawing keeps the FF4 window colours: #000083 body, white border, the game font.
 */
class FF4TouchPadView(
    context: Context,
    /** Emulator button ids, pressed and released. */
    private val onButton: (Int, Boolean) -> Unit,
    /** Momentary rows: TRK, FF, SAVE, START. */
    private val onTap: (Int) -> Unit,
) : View(context) {

    companion object {
        /** Tap ids, above the joypad range the emulator uses. */
        const val TAP_TRK = 100
        const val TAP_FF = 101
        const val TAP_SAVE = 102
        const val TAP_START = 103

        // Emulator joypad ids, as EmulatorBridge numbers them.
        private const val B = 0
        private const val Y = 1
        private const val SELECT = 2
        private const val UP = 4
        private const val DOWN = 5
        private const val LEFT = 6
        private const val RIGHT = 7
        private const val A = 8
        private const val X = 9
        private const val L = 10
        private const val R = 11
    }

    /** "small" | "normal" | "large". */
    var sizeName: String = "normal"
        private set
    /** "dim" | "medium" | "bright". */
    var alphaName: String = "medium"
        private set

    var haptics: Boolean = true

    private val dp = resources.displayMetrics.density
    private val font: Typeface = FF4Theme.font(context)

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        color = 0xAAFFFFFF.toInt()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = font
    }

    // zone geometry, built in onSizeChanged
    private var cross: Ff4TouchZone.Cross? = null
    private var face: Ff4TouchZone.Face? = null
    private var rows: List<Ff4TouchZone.Row> = emptyList()

    private var faceButtons: List<Ff4TouchZone.Face.Button> = emptyList()
    private var fastForward = false

    private val taps = setOf(TAP_TRK, TAP_FF, TAP_SAVE, TAP_START)
    private val tracker = Ff4TouchTracker(
        resolve = { x, y -> resolveZone(x, y) },
        isTap = { it in taps },
    )

    init {
        tracker.onButton = { id, pressed ->
            onButton(id, pressed)
            if (haptics && pressed) performHapticFeedbackSafe()
            invalidate()
        }
        tracker.onTap = { id ->
            onTap(id)
            if (haptics) performHapticFeedbackSafe()
            invalidate()
        }
        isFocusable = false
        isFocusableInTouchMode = false
    }

    fun setSize(name: String) { sizeName = name; rebuildZones() }
    fun setAlphaStyle(name: String) { alphaName = name; invalidate() }
    fun setFastForward(active: Boolean) { fastForward = active; invalidate() }

    private fun performHapticFeedbackSafe() {
        try { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) } catch (_: Throwable) {}
    }

    private fun unit(): Float = when (sizeName) {
        "small" -> 44f
        "large" -> 62f
        else -> 52f
    } * dp

    private fun bodyAlpha(): Int = when (alphaName) {
        "dim" -> 0x33000083.toInt()
        "bright" -> 0xB3000083.toInt()
        else -> 0x66000083.toInt()
    }

    private fun rebuildZones() {
        if (width == 0 || height == 0) return
        val u = unit()
        val margin = 12f * dp
        val reach = 1.6f * u
        val centreOffset = margin + 1.5f * u

        val dCx = centreOffset
        val dCy = height - centreOffset
        cross = Ff4TouchZone.Cross(dCx, dCy, reach, 0.42f * u, UP, DOWN, LEFT, RIGHT)

        val fCx = width - centreOffset
        val fCy = height - centreOffset
        faceButtons = listOf(
            Ff4TouchZone.Face.Button(fCx, fCy - u, 0.95f * u, X),
            Ff4TouchZone.Face.Button(fCx - u, fCy, 0.95f * u, Y),
            Ff4TouchZone.Face.Button(fCx + u, fCy, 0.95f * u, A),
            Ff4TouchZone.Face.Button(fCx, fCy + u, 0.95f * u, B),
        )
        face = Ff4TouchZone.Face(faceButtons)

        val rowH = 42f * dp
        val rowY = 48f * dp     // below the PAD toggle, as the old row was
        val gap = 8f * dp
        rows = buildList {
            // console row, top-right: L · R · SELECT · START
            var x = width - margin
            fun putRight(id: Int, w: Float) {
                x -= w
                add(Ff4TouchZone.Row(x, rowY, x + w, rowY + rowH, id))
            }
            putRight(TAP_START, 88f * dp)
            x -= gap
            putRight(SELECT, 88f * dp)
            x -= gap
            putRight(R, 48f * dp)
            x -= gap
            putRight(L, 48f * dp)

            // app row, top-left: TRK · FF · SAVE
            var lx = margin
            fun putLeft(id: Int, w: Float) {
                add(Ff4TouchZone.Row(lx, 10f * dp, lx + w, 10f * dp + rowH, id))
                lx += w + gap
            }
            putLeft(TAP_TRK, 64f * dp)
            putLeft(TAP_FF, 64f * dp)
            putLeft(TAP_SAVE, 72f * dp)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildZones()
    }

    private fun resolveZone(x: Float, y: Float): Int? {
        rows.firstNotNullOfOrNull { it.hit(x, y) }?.let { return it }
        cross?.hit(x, y)?.let { return it }
        return face?.hit(x, y)
    }

    // ------------------------------------------------------------- touch input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                tracker.down(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE ->
                for (i in 0 until event.pointerCount) {
                    tracker.move(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                tracker.up(event.getPointerId(i))
            }
            MotionEvent.ACTION_CANCEL -> tracker.cancel()
            else -> return false
        }
        return true
    }

    override fun onDetachedFromWindow() {
        tracker.cancel()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- drawing

    private val held: Set<Int> get() = tracker.held()

    private fun drawCrossArm(c: Canvas, cx: Float, cy: Float, u: Float, pressed: Boolean, horizontal: Boolean, negative: Boolean) {
        val arm = 0.92f * u
        val half = 0.46f * u
        val rect = if (horizontal) {
            val x0 = if (negative) cx - arm - half else cx + half
            RectF(x0, cy - half, x0 + arm, cy + half)
        } else {
            val y0 = if (negative) cy - arm - half else cy + half
            RectF(cx - half, y0, cx + half, y0 + arm)
        }
        body.color = if (pressed) 0xCC000083.toInt() else bodyAlpha()
        c.drawRoundRect(rect, 6f * dp, 6f * dp, body)
        border.color = if (pressed) 0xFFFFFFFF.toInt() else 0xAAFFFFFF.toInt()
        c.drawRoundRect(rect, 6f * dp, 6f * dp, border)
    }

    private fun drawFaceButton(c: Canvas, b: Ff4TouchZone.Face.Button, u: Float, id: Int, label: String) {
        val pressed = id in held
        body.color = if (pressed) 0xCC000083.toInt() else bodyAlpha()
        c.drawCircle(b.cx, b.cy, 0.46f * u, body)
        border.color = if (pressed) 0xFFFFFFFF.toInt() else 0xAAFFFFFF.toInt()
        c.drawCircle(b.cx, b.cy, 0.46f * u, border)
        text.color = 0xFFFFFFFF.toInt()
        text.textSize = 18f * dp
        c.drawText(label, b.cx, b.cy - (text.ascent() + text.descent()) / 2f, text)
    }

    private fun drawRow(c: Canvas, r: Ff4TouchZone.Row, label: String, active: Boolean) {
        val rect = RectF(r.left, r.top, r.right, r.bottom)
        body.color = if (active) 0xCC000083.toInt() else bodyAlpha()
        c.drawRoundRect(rect, 6f * dp, 6f * dp, body)
        border.color = if (active) 0xFFFFFFFF.toInt() else 0xAAFFFFFF.toInt()
        c.drawRoundRect(rect, 6f * dp, 6f * dp, border)
        text.color = if (active) 0xFFFFFF00.toInt() else 0xFFFFFFFF.toInt()
        text.textSize = 14f * dp
        c.drawText(label, (r.left + r.right) / 2f, (r.top + r.bottom) / 2f - (text.ascent() + text.descent()) / 2f, text)
    }

    override fun onDraw(c: Canvas) {
        val cross = this.cross ?: return
        val u = unit()

        // d-pad arms, drawn under nothing else
        drawCrossArm(c, cross.cx, cross.cy, u, UP in held, horizontal = false, negative = true)
        drawCrossArm(c, cross.cx, cross.cy, u, DOWN in held, horizontal = false, negative = false)
        drawCrossArm(c, cross.cx, cross.cy, u, LEFT in held, horizontal = true, negative = true)
        drawCrossArm(c, cross.cx, cross.cy, u, RIGHT in held, horizontal = true, negative = false)
        // centre square
        body.color = bodyAlpha()
        val half = 0.46f * u
        c.drawRoundRect(RectF(cross.cx - half, cross.cy - half, cross.cx + half, cross.cy + half), 6f * dp, 6f * dp, body)
        border.color = 0xAAFFFFFF.toInt()
        c.drawRoundRect(RectF(cross.cx - half, cross.cy - half, cross.cx + half, cross.cy + half), 6f * dp, 6f * dp, border)

        // direction arrows
        text.color = 0xFFFFFFFF.toInt()
        text.textSize = 18f * dp
        fun arrow(x: Float, y: Float, s: String) = c.drawText(s, x, y - (text.ascent() + text.descent()) / 2f, text)
        arrow(cross.cx, cross.cy - u, "▲")
        arrow(cross.cx, cross.cy + u, "▼")
        arrow(cross.cx - u, cross.cy, "◀")
        arrow(cross.cx + u, cross.cy, "▶")

        // face buttons
        drawFaceButton(c, faceButtons[0], u, X, "X")
        drawFaceButton(c, faceButtons[1], u, Y, "Y")
        drawFaceButton(c, faceButtons[2], u, A, "A")
        drawFaceButton(c, faceButtons[3], u, B, "B")

        // rows
        for (r in rows) {
            when (r.id) {
                L -> drawRow(c, r, "L", L in held)
                R -> drawRow(c, r, "R", R in held)
                SELECT -> drawRow(c, r, "SELECT", SELECT in held)
                TAP_START -> drawRow(c, r, "START", false)
                TAP_TRK -> drawRow(c, r, "TRK", false)
                TAP_FF -> drawRow(c, r, "FF", fastForward)
                TAP_SAVE -> drawRow(c, r, "SAVE", false)
            }
        }
    }
}
