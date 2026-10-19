package com.freeenterprise.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.freeenterprise.app.emulator.EmulatorBridge
import com.freeenterprise.app.emulator.EmulatorSession
import com.freeenterprise.app.ff4ui.FF4Activity
import com.freeenterprise.app.ff4ui.FF4Menu
import com.freeenterprise.app.ff4ui.FF4Theme
import com.freeenterprise.app.ff4ui.FF4Window
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app playback with the bundled Snes9x core.
 *
 * Chrome:
 *  - on-screen pad is OFF by default; a low-opacity PAD button (top corner)
 *    toggles it, and the choice is remembered
 *  - Select / MENU opens the FF4 pause window: Resume, Save State, Load State,
 *    Reset Core, Exit (save states live beside the ROM as `<name>.state`)
 *  - R toggles fast-forward; tapping the picture also opens the pause window
 *  - battery save (.srm) is written automatically
 */
class FF4PlayerActivity : FF4Activity(), SurfaceHolder.Callback {

    companion object {
        private const val REGION_WRAM = 1
        private const val TRACKER_PAGES = 2
    }


    private lateinit var surfaceView: SurfaceView
    private lateinit var root: FrameLayout
    private lateinit var padLayer: FrameLayout
    private lateinit var overlayLayer: FrameLayout
    private var padControls: View? = null
    private var padToggle: View? = null

    private var session: EmulatorSession? = null
    private var rom: File? = null

    private var paused = false
    private var pauseIndex = 0
    private var trackerOpen = false
    private var trackerPage = 0
    private var lastWram: ByteArray? = null
    private var firstFrameSeen = false
    private var fastForward = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null

    private val heldKeys = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val romPath = intent.getStringExtra("rom") ?: run { finish(); return }
        val file = File(romPath)
        if (!file.exists()) { finish(); return }
        rom = file

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@FF4PlayerActivity)
            // Touch does nothing unless the on-screen pad is enabled (see below)
            isFocusable = false
            isFocusableInTouchMode = false
        }

        root = FrameLayout(this)
        root.addView(surfaceView, matchParent())
        padLayer = FrameLayout(this).apply {
            isClickable = false
            // never let a d-pad press walk focus onto the on-screen buttons
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        root.addView(padLayer, matchParent())
        overlayLayer = FrameLayout(this).apply { isClickable = false }
        root.addView(overlayLayer, matchParent())
        setContentView(root)

        buildPadToggle()
        applyControlsVisibility()
    }

    // ------------------------------------------------------------ lifecycle

    override fun surfaceCreated(holder: SurfaceHolder) {
        startSession(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopSession()
    }

    override fun onPause() {
        super.onPause()
        session?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (!paused) session?.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSession()
    }

    private fun startSession(surface: android.view.Surface) {
        if (session != null) return
        val file = rom ?: return
        val save = RomLibrary.srmFile(file)
        session = EmulatorSession(
            context = applicationContext,
            corePath = applicationInfo.nativeLibraryDir + "/libretro.so",
            systemDir = filesDir.absolutePath,
            romPath = file.absolutePath,
            savePath = save.absolutePath,
            surface = surface,
            onFirstFrame = { runOnUiThread { onPlaybackStarted() } },
            onError = { msg -> runOnUiThread { showError(msg) } },
        ).also { it.start() }

        firstFrameSeen = false
        val watchdog = Runnable {
            if (!firstFrameSeen && !isFinishing) {
                val why = EmulatorBridge.nativeLastError()
                showError(
                    "No video after 8 seconds." +
                        (if (why.isNullOrBlank()) "" else "\nCore: $why") +
                        "\nROM: ${file.name}"
                )
            }
        }
        watchdogRunnable = watchdog
        mainHandler.postDelayed(watchdog, 8000)
    }

    private fun stopSession() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
        session?.stop()
        session = null
    }

    private fun onPlaybackStarted() {
        firstFrameSeen = true
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    // -------------------------------------------------- on-screen pad (toggle)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun buildPadToggle() {
        val toggle = TextView(this).apply {
            text = "PAD"
            typeface = FF4Theme.font(this@FF4PlayerActivity)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(14).toFloat())
            setTextColor(FF4Theme.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val d = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(0x33000083)
                setStroke(dp(1), 0x88FFFFFF.toInt())
            }
            background = d
            alpha = 0.35f
            setOnClickListener { toggleControls() }
        }
        padLayer.addView(toggle, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            // Top-right corner. The pad's console row (L · R · SELECT · START) is
            // anchored below this so it cannot cover the toggle — that was the bug
            // in 0.1.4, where the row painted over the button that hides the pad.
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(10); marginEnd = dp(10)
        })
        padToggle = toggle
    }

    private fun toggleControls() {
        val on = !EditorPrefs.onscreenControls(this)
        EditorPrefs.setOnscreenControls(this, on)
        applyControlsVisibility()
        Toast.makeText(this, "On-screen controls ${if (on) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
    }

    private fun applyControlsVisibility() {
        padControls?.let { padLayer.removeView(it) }
        padControls = null
        if (EditorPrefs.onscreenControls(this)) {
            padControls = buildPad().also { padLayer.addView(it, matchParent()) }
        }
        // belt and braces: the toggle must always be the topmost thing in the layer
        padToggle?.bringToFront()
    }

    /**
     * A real controller was used, so the on-screen pad is not wanted. Hides it and
     * remembers the choice as off — the PAD button (top-centre, always visible)
     * brings it back.
     */
    private fun hideTouchControlsForController() {
        if (!EditorPrefs.onscreenControls(this)) return
        EditorPrefs.setOnscreenControls(this, false)
        applyControlsVisibility()
    }

    /** Physical gamepad keys: d-pad, BUTTON_A..BUTTON_MODE, and BUTTON_1..BUTTON_16. */
    private fun isGamepadKey(keyCode: Int): Boolean = when (keyCode) {
        in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER -> true
        in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE -> true
        in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16 -> true
        else -> false
    }

    private fun buildPad(): View {
        val holder = FrameLayout(this).apply { isClickable = false }
        val unit = dp(52)

        // D-pad, bottom-left
        val dpad = FrameLayout(this)
        fun padBtn(label: String, id: Int, left: Int, top: Int): TextView {
            val b = button(label)
            b.setOnTouchListener(holdListener(id))
            dpad.addView(b, FrameLayout.LayoutParams(unit, unit).apply {
                leftMargin = left; topMargin = top
            })
            return b
        }
        padBtn("▲", EmulatorBridge.UP, unit, 0)
        padBtn("◀", EmulatorBridge.LEFT, 0, unit)
        padBtn("▶", EmulatorBridge.RIGHT, unit * 2, unit)
        padBtn("▼", EmulatorBridge.DOWN, unit, unit * 2)
        holder.addView(dpad, FrameLayout.LayoutParams(unit * 3, unit * 3).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            marginStart = dp(16); bottomMargin = dp(16)
        })

        // Face buttons, bottom-right
        val face = FrameLayout(this)
        fun faceBtn(label: String, id: Int, left: Int, top: Int) {
            val b = button(label)
            b.setOnTouchListener(holdListener(id))
            face.addView(b, FrameLayout.LayoutParams(unit, unit).apply {
                leftMargin = left; topMargin = top
            })
        }
        faceBtn("X", EmulatorBridge.X, unit, 0)          // top
        faceBtn("Y", EmulatorBridge.Y, 0, unit)          // left
        faceBtn("A", EmulatorBridge.A, unit * 2, unit)   // right  (confirm)
        faceBtn("B", EmulatorBridge.B, unit, unit * 2)   // bottom (cancel)
        holder.addView(face, FrameLayout.LayoutParams(unit * 3, unit * 3).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = dp(16); bottomMargin = dp(16)
        })

        // Top-left: our own functions (FF4 ignores L/R, and Start opens our menu)
        val appRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ffButton = button("FF").apply { setOnClickListener { toggleFastForward() } }
        val save = button("SAVE").apply { setOnClickListener { quickSave() } }
        val trk = button("TRK").apply { setOnClickListener { toggleTracker() } }
        appRow.addView(trk, LinearLayout.LayoutParams(dp(64), dp(42)))
        appRow.addView(ffButton, LinearLayout.LayoutParams(dp(64), dp(42)).apply { marginStart = dp(8) })
        appRow.addView(save, LinearLayout.LayoutParams(dp(72), dp(42)).apply { marginStart = dp(8) })
        holder.addView(appRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = dp(16); topMargin = dp(10)
        })

        // Top-right, *below the PAD toggle* so the two never overlap: the console's
        // buttons — SELECT goes to the game, START opens our menu (mirroring the
        // physical Start button).
        val sysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lb = button("L").apply { setOnTouchListener(holdListener(EmulatorBridge.L)) }
        val rb = button("R").apply { setOnTouchListener(holdListener(EmulatorBridge.R)) }
        val sel = button("SELECT").apply { setOnTouchListener(holdListener(EmulatorBridge.SELECT)) }
        val start = button("START").apply { setOnClickListener { togglePause() } }
        sysRow.addView(lb, LinearLayout.LayoutParams(dp(48), dp(42)))
        sysRow.addView(rb, LinearLayout.LayoutParams(dp(48), dp(42)).apply { marginStart = dp(8) })
        sysRow.addView(sel, LinearLayout.LayoutParams(dp(88), dp(42)).apply { marginStart = dp(8) })
        sysRow.addView(start, LinearLayout.LayoutParams(dp(88), dp(42)).apply { marginStart = dp(8) })
        holder.addView(sysRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = dp(16); topMargin = dp(48)
        })

        updateFastForwardButton()
        return holder
    }

    private var ffButton: TextView? = null

    private fun button(label: String): TextView = TextView(this).apply {
        text = label
        typeface = FF4Theme.font(this@FF4PlayerActivity)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(18).toFloat())
        setTextColor(FF4Theme.WHITE)
        gravity = Gravity.CENTER
        val d = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(0x66000083)
            setStroke(dp(2), FF4Theme.WHITE)
        }
        background = d
        alpha = 0.55f
    }

    private fun holdListener(id: Int) = View.OnTouchListener { v, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { v.alpha = 0.95f; session?.setUp(id); true }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> { v.alpha = 0.55f; session?.setDown(id); true }
            else -> false
        }
    }

    // -------------------------------------------------------------- overlays

    /**
     * Swap the overlay content safely.
     *
     * The bug this fixes: previously a menu that held focus was removed from the
     * view tree and replaced, leaving focus on a *detached* view — key events
     * then went nowhere, so overlays became unescapable and the hidden menu
     * could still be moved around. Always drop focus first, then hand it to the
     * new content (or the overlay itself when the content is not focusable).
     */
    private fun setOverlay(window: View, focusTarget: View?) {
        currentFocus?.clearFocus()
        overlayLayer.removeAllViews()
        overlayLayer.setBackgroundColor(0xCC000000.toInt())
        overlayLayer.isClickable = true
        overlayLayer.isFocusable = true
        overlayLayer.isFocusableInTouchMode = true
        overlayLayer.addView(window, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply { marginStart = dp(48); marginEnd = dp(48) })
        (focusTarget ?: overlayLayer).requestFocus()
    }

    private fun clearOverlay() {
        currentFocus?.clearFocus()
        overlayLayer.removeAllViews()
        overlayLayer.setOnClickListener(null)
        overlayLayer.setBackgroundColor(0x00000000)
        overlayLayer.isClickable = false
        overlayLayer.isFocusable = false
    }

    /** Touch affordances exist only while the on-screen pad is enabled. */
    private fun touchControlsEnabled(): Boolean = EditorPrefs.onscreenControls(this)

    private fun togglePause() {
        if (overlayLayer.childCount > 0) closePause() else openPause()
    }

    private fun openPause() {
        paused = true
        session?.pause()
        val state = rom?.let { RomLibrary.stateFile(it) }
        val stateInfo = when {
            state == null -> ""
            state.exists() -> "State: ${state.name}  ·  ${state.length() / 1024}K  ·  " +
                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(state.lastModified()))
            else -> "No save state yet."
        }
        val info = TextView(this).apply {
            text = stateInfo
            FF4Theme.applyText(this@FF4PlayerActivity, this, FF4Theme.textSmallPx(this@FF4PlayerActivity))
            setTextColor(FF4Theme.YELLOW)
            setPadding(0, 0, 0, dp(6))
        }
        val menu = FF4Menu(this)
        menu.submit(
            listOf(
                FF4Menu.Item("Resume"),
                FF4Menu.Item("Save State"),
                FF4Menu.Item("Load State", if (state?.exists() == true) "load" else "none",
                    enabled = state?.exists() == true),
                FF4Menu.Item("Fast-forward", if (fastForward) "on" else "off"),
                FF4Menu.Item("Checks tracker", "L2"),
                FF4Menu.Item("Reset Core"),
                FF4Menu.Item("Exit to sessions"),
            ),
            pauseIndex,
        ) { index ->
            pauseIndex = index
            when (index) {
                0 -> closePause()
                1 -> { saveState(); closePause() }   // save, then close (as Load does)
                2 -> loadState()
                3 -> { toggleFastForward(); openPause() }   // reopen to refresh the label
                4 -> { closePause(); toggleTracker() }
                5 -> confirm(
                    "Reset core",
                    "Reset the emulator?\nUnsaved progress since the last save state is lost.",
                    "Reset"
                ) { closePause(); restartSession() }
                6 -> confirm(
                    "Exit to sessions",
                    "Leave the game?\nYour battery save is written to FF4_FE first.",
                    "Exit"
                ) { finish() }
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(menu)
        }
        val window = FF4Window(this).apply { addView(column) }
        setOverlay(window, menu)
        window.setOnClickListener { }        // don't let taps fall through
    }

    private fun confirm(title: String, message: String, yes: String, onYes: () -> Unit) {
        com.freeenterprise.app.ff4ui.FF4Dialog.confirm(
            this, title, message, yes = yes, no = "Cancel",
            onYes = onYes,
            onNo = { },
        )
    }

    private fun closePause() {
        clearOverlay()
        paused = false
        session?.resume()
    }

    private fun showError(message: String) {
        val menu = FF4Menu(this)
        menu.submit(
            listOf(
                FF4Menu.Item("Retry"),
                FF4Menu.Item("Exit"),
            )
        ) { index ->
            when (index) {
                0 -> { closePause(); restartSession() }
                1 -> finish()
            }
        }
        val text = TextView(this).apply {
            text = message
            FF4Theme.applyText(this@FF4PlayerActivity, this, FF4Theme.textSmallPx(this@FF4PlayerActivity))
            setTextColor(FF4Theme.PINK)
            setPadding(0, 0, 0, dp(10))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text)
            addView(menu)
        }
        val window = FF4Window(this).apply { addView(column) }
        paused = true
        session?.pause()
        setOverlay(window, menu)
    }

    // ------------------------------------------------------------- tracker

    /**
     * Select shows the tracker: which of FE's checks are done (treasure
     * switches at WRAM $12A0, the same array FE's own stats.f4c counts) and
     * which key items the party holds. Read from live WRAM, not the save file,
     * so it reflects the current game state.
     */
    private fun toggleTracker() {
        if (trackerOpen) {
            closeTracker()
            return
        }
        trackerPage = 0                       // always open on the key-item page
        readAndShowTracker()
    }

    private fun readAndShowTracker() {
        val session = session ?: return
        // Bank $7E, field game state $1000-$17FF.
        session.readMemory(REGION_WRAM, 0x1000, 0x800) { data ->
            lastWram = data
            showTracker(buildTrackerText(data))
        }
    }

    private fun turnTrackerPage(delta: Int) {
        trackerPage = (trackerPage + delta + TRACKER_PAGES) % TRACKER_PAGES
        val data = lastWram ?: run { readAndShowTracker(); return }
        showTracker(buildTrackerText(data))
    }

    private fun buildTrackerText(wram: ByteArray?): String {
        if (wram == null || wram.isEmpty()) {
            return "No game state available yet.\nStart or load a game first."
        }
        return if (trackerPage == 0) keyItemPage(wram) else checksPage(wram)
    }

    /**
     * Page 1 — key item checks.
     *
     * Lists the *locations* only, with a mark for the ones already checked; it
     * deliberately does not say which item sits where, so the page can be used
     * without spoiling the seed.
     */
    private fun keyItemPage(wram: ByteArray): String {
        val sb = StringBuilder()
        val held = Ff4Save.keyItems(wram, 0).map { normalise(it) }.toSet()
        // Seed metadata is the normal source; a spoiler file beside the ROM is
        // honoured too, so one downloaded from the website also works.
        val meta = rom?.let { RomLibrary.readMeta(it) }
        val locations = meta?.keyItems.orEmpty().ifEmpty {
            rom?.let { Spoiler.keyItemLocations(RomLibrary.spoilerFile(it)) }.orEmpty()
        }

        sb.append("PAGE 1 / $TRACKER_PAGES   KEY ITEM CHECKS\n\n")
        if (locations.isEmpty()) {
            sb.append("This seed has no key-item data saved.\n")
            sb.append("(Seeds generated before 0.1.18 did not record it — regenerate\n")
            sb.append(" the seed, or drop a .spoiler.txt beside the ROM.)\n\n")
        } else {
            var checked = 0
            for ((name, place) in locations) {
                val isChecked = held.contains(normalise(name))
                if (isChecked) checked++
                sb.append(" %s %s\n".format(if (isChecked) "\u2713" else "\u00b7", place.take(38)))
            }
            sb.append("\n %d / %d checked\n".format(checked, locations.size))
            sb.append(" \u2713 = you already have that key item.\n")
            sb.append(" Where each item is stays in the spoiler.\n")
        }
        return sb.toString()
    }

    /** Page 2 — the checks summary. */
    private fun checksPage(wram: ByteArray): String {
        val sb = StringBuilder()
        val progress = CheckDb.progress(this, wram, 0x2A0)
        sb.append("PAGE 2 / $TRACKER_PAGES   CHECKS\n\n")
        sb.append("TOTAL   %d / %d\n".format(progress.opened, progress.total))
        for (world in listOf("Overworld", "Underworld", "Moon")) {
            val (o, t) = progress.byWorld[world] ?: continue
            sb.append("  %-11s %3d / %3d\n".format(world, o, t))
        }
        sb.append("\nBY AREA  (most remaining first)\n")
        for ((area, o, t) in progress.byArea.take(14)) {
            sb.append("  %-22s %3d / %3d\n".format(area.take(22), o, t))
        }
        return sb.toString()
    }

    /** Loose name match between the spoiler and the inventory item names. */
    private fun normalise(name: String): String {
        val n = name.lowercase().replace(" ", "").replace("_", "")
        return when (n) {
            "darknesscrystal" -> "darkcrystal"
            else -> n
        }
    }

    private fun showTracker(text: String) {
        trackerOpen = true
        paused = true
        session?.pause()
        val view = TextView(this).apply {
            this.text = text
            FF4Theme.applyText(this@FF4PlayerActivity, this, FF4Theme.textSmallPx(this@FF4PlayerActivity))
            setTextColor(FF4Theme.WHITE)
        }
        val hint = TextView(this).apply {
            this.text = "\u25c0 \u25b6  page      L2 / B  close"
            FF4Theme.applyText(this@FF4PlayerActivity, this, FF4Theme.textSmallPx(this@FF4PlayerActivity))
            setTextColor(FF4Theme.YELLOW)
            setPadding(0, dp(8), 0, 0)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(view)
            addView(hint)
        }
        val window = FF4Window(this).apply { addView(column) }
        setOverlay(window, null)
        // tapping the backdrop closes it, but only when touch controls are on
        if (touchControlsEnabled()) overlayLayer.setOnClickListener { closeTracker() }
    }

    private fun closeTracker() {
        trackerOpen = false
        clearOverlay()
        paused = false
        session?.resume()
    }

    // ---------------------------------------------------------- save states

    private fun saveState() {
        val file = rom ?: return
        val state = RomLibrary.stateFile(file)
        session?.saveState(state.absolutePath) { ok ->
            Toast.makeText(
                this,
                if (ok) "Saved state to ${state.name}" else "Save state failed: ${EmulatorBridge.nativeLastError()}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun loadState() {
        val file = rom ?: return
        val state = RomLibrary.stateFile(file)
        if (!state.exists()) {
            Toast.makeText(this, "No save state yet.", Toast.LENGTH_SHORT).show()
            return
        }
        session?.loadState(state.absolutePath) { ok ->
            Toast.makeText(
                this,
                if (ok) "Loaded state" else "Load state failed: ${EmulatorBridge.nativeLastError()}",
                Toast.LENGTH_SHORT
            ).show()
            if (ok) closePause()
        }
    }

    // ------------------------------------------------------------ fast forward

    /** L button / SAVE: flush the battery save to the session's .srm. */
    private fun quickSave() {
        session?.flushSave()
        Toast.makeText(this, "Saved to FF4_FE", Toast.LENGTH_SHORT).show()
    }

    private fun toggleFastForward() {
        fastForward = !fastForward
        session?.setFastForward(fastForward)
        updateFastForwardButton()   // the FF button turns yellow while active
    }

    private fun updateFastForwardButton() {
        ffButton?.let {
            it.alpha = if (fastForward) 0.95f else 0.55f
            it.setTextColor(if (fastForward) FF4Theme.YELLOW else FF4Theme.WHITE)
        }
    }

    private fun restartSession() {
        val file = rom ?: return
        overlayLayer.removeAllViews()
        overlayLayer.isClickable = false
        overlayLayer.setBackgroundColor(0x00000000)
        paused = false
        fastForward = false
        updateFastForwardButton()
        stopSession()
        startSession(surfaceView.holder.surface)
    }

    // ----------------------------------------------------------------- input

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0 && heldKeys.contains(keyCode)) return true

        // A physical controller is in play — drop the on-screen pad (no-op once off)
        if (isGamepadKey(keyCode)) hideTouchControlsForController()

        if (trackerOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { closeTracker(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { turnTrackerPage(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { turnTrackerPage(1); return true }
            }
        }

        // App buttons.
        //   Start            = our QoL menu (FF4's own field menu is the top face
        //                      button; the game's Start only pauses in combat,
        //                      which our menu does anyway)
        //   L2 trigger       = checks tracker
        //   R2 trigger       = fast-forward toggle
        //   L1/R1, Select    = NOT ours — forwarded to the game (FE uses L+R to
        //                      flee, and Select for its own tracker)
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                togglePause()
                return true
            }
            // L2/R2 (the triggers) are ours; L1/R1 (the shoulder buttons) are
            // forwarded to the game, because Free Enterprise uses L+R to flee.
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                toggleTracker()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {     // toggle, not hold
                toggleFastForward()
                return true
            }
            // D-pad centre / Enter used to forward START to the game. The game's
            // only use of Start is pausing in combat, which our menu does, so it
            // opens our menu instead (never double-open while paused).
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (paused || trackerOpen) return super.onKeyDown(keyCode, event)
                togglePause()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                confirm(
                    "Exit to sessions",
                    "Leave the game?\nYour battery save is written to FF4_FE first.",
                    "Exit"
                ) { finish() }
                return true
            }
        }

        // While an overlay is up, swallow everything the overlay did not use.
        // Otherwise unhandled keys (notably LEFT/RIGHT, which FF4Menu ignores)
        // fall through to Android's focus navigation and move focus off the menu
        // onto a background view — the "lose focus in the in-game menu" bug.
        if (paused || trackerOpen) return true

        val id = joypadId(keyCode) ?: return super.onKeyDown(keyCode, event)
        heldKeys.add(keyCode)
        session?.setUp(id)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val id = joypadId(keyCode) ?: return super.onKeyUp(keyCode, event)
        heldKeys.remove(keyCode)
        session?.setDown(id)
        return true
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        val fromController = event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        if (fromController) hideTouchControlsForController()

        if (paused) return super.onGenericMotionEvent(event)
        val x = event.getAxisValue(android.view.MotionEvent.AXIS_X)
        val y = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
        val hatX = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        ) {
            session?.let { s ->
                s.setUpOrDown(EmulatorBridge.LEFT, x < -0.5f || hatX < -0.5f)
                s.setUpOrDown(EmulatorBridge.RIGHT, x > 0.5f || hatX > 0.5f)
                s.setUpOrDown(EmulatorBridge.UP, y < -0.5f || hatY < -0.5f)
                s.setUpOrDown(EmulatorBridge.DOWN, y > 0.5f || hatY > 0.5f)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun joypadId(keyCode: Int): Int? = when (keyCode) {
        // Swapped so the pad's lower button confirms, matching how FF4's menus read.
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_1 -> EmulatorBridge.A
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_2 -> EmulatorBridge.B
        // Positional match: the pad's top button is FF4's field menu (SNES X).
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_4 -> EmulatorBridge.X
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_3 -> EmulatorBridge.Y
        KeyEvent.KEYCODE_DPAD_UP -> EmulatorBridge.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> EmulatorBridge.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> EmulatorBridge.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> EmulatorBridge.RIGHT
        // Select belongs to the game in Free Enterprise
        KeyEvent.KEYCODE_BUTTON_SELECT -> EmulatorBridge.SELECT
        // Shoulder buttons go to the game: FE uses L+R to run from battle
        KeyEvent.KEYCODE_BUTTON_L1 -> EmulatorBridge.L
        KeyEvent.KEYCODE_BUTTON_R1 -> EmulatorBridge.R
        // NOTE: Start is deliberately NOT forwarded — it opens our system menu,
        // and the game's only use of it (pause in combat) is covered by that.
        else -> null
    }
}