package com.dualamp.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.dualamp.playback.PlayerConnection
import com.dualamp.viz.VizControl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Gamepad handling shared by both screens.
 *
 * Android only sends key events to the focused display, which on a dual-screen handheld is whichever
 * screen was touched last. Both activities forward keys here, and the library keeps its own cursor
 * instead of relying on Compose focus, so the controls work no matter which screen has focus.
 *
 *  D-pad      move the library cursor          A / Enter   open / play
 *  B          back                             Y           item menu (hold: like / add to playlist, song playing)
 *  X          play / pause                     Select      next tab (hold: search keyboard)
 *  Start      Settings (hold: visualizer folders)
 *  L1 / R1    previous / next track            R2          play / pause
 *  L2         next visualizer preset (hold: rewind)    R2 hold: fast forward
 *  L3         change the library view (hold: clear the queue, asks first; tabs layout, double-click: big player)        R3   show / hide the song info (double-click: lyrics on / off)
 */
object ControllerBus {

    enum class Nav {
        UP, DOWN, LEFT, RIGHT, SELECT, BACK, MENU, NOW_PLAYING_MENU, OPEN_PLAYER, NEXT_CATEGORY, CLEAR_QUEUE, CHANGE_VIEW, OPEN_SETTINGS,
        OPEN_SEARCH, OPEN_VISUALIZERS, KB_DELETE, KB_SPACE, KB_SYMBOLS, KB_CURSOR_LEFT, KB_CURSOR_RIGHT,
    }

    /** True while the on-screen keyboard is open: X, Y, L1, R1 and L2 edit text instead of their usual jobs. */
    @Volatile var textInput = false

    private val navFlow = MutableSharedFlow<Nav>(extraBufferCapacity = 32)
    val nav: SharedFlow<Nav> get() = navFlow

    /** Returns true when the event was consumed. Call from Activity.dispatchKeyEvent. */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || event.keyCode == KeyEvent.KEYCODE_BUTTON_START ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL || event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR ||
            (event.keyCode == KeyEvent.KEYCODE_BUTTON_Y && !textInput)) {
            handleHoldable(event)
            return true
        }
        val nav = (if (textInput) keyboardNavFor(event.keyCode) else null) ?: navFor(event.keyCode)
        if (nav != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && nav == Nav.KB_SYMBOLS) switchSymbols()
            else if (event.action == KeyEvent.ACTION_DOWN && nav != Nav.KB_SYMBOLS) navFlow.tryEmit(nav)
            return true
        }
        // L2 / R2: tap or hold (see triggerDown).
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            val right = event.keyCode == KeyEvent.KEYCODE_BUTTON_R2
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) triggerDown(right)
            else if (event.action == KeyEvent.ACTION_UP) triggerUp(right)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return isHandled(event.keyCode)
        if (event.repeatCount > 0) return isHandled(event.keyCode)
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_X -> PlayerConnection.togglePlay()
            KeyEvent.KEYCODE_BUTTON_L1 -> PlayerConnection.previous()
            KeyEvent.KEYCODE_BUTTON_R1 -> PlayerConnection.next()
            else -> return false
        }
        VizControl.pokeOverlay()
        return true
    }

    // Select, Start, L3, R3 and Y: a short press acts when released; holding for half a second does something else.
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingHold: Runnable? = null
    private var holdFired = false

    private fun handleHoldable(event: KeyEvent) {
        val select = event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
        val l3 = event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL
        // R3: show / hide the song info (toggle only: the "show the info on any button" rule would undo the hide);
        // double-click: lyrics on / off.
        val r3 = event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR
        // Y: the item menu; hold: like / add to playlist for the song playing.
        val y = event.keyCode == KeyEvent.KEYCODE_BUTTON_Y
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                pendingHold?.let(main::removeCallbacks)
                holdFired = false
                pendingHold = Runnable {
                    holdFired = true
                    if (r3) holdFired = false // R3 has no hold: it counts as a click
                    else navFlow.tryEmit(if (y) Nav.NOW_PLAYING_MENU else if (l3) Nav.CLEAR_QUEUE else if (select) Nav.OPEN_SEARCH else Nav.OPEN_VISUALIZERS)
                }.also { main.postDelayed(it, HOLD_MS) }
            }
            KeyEvent.ACTION_UP -> {
                pendingHold?.let(main::removeCallbacks)
                pendingHold = null
                if (r3) rightStickClick()
                else if (l3 && !holdFired && l3OpensPlayer) leftStickClick()
                else if (!holdFired) navFlow.tryEmit(if (y) Nav.MENU else if (l3) Nav.CHANGE_VIEW else if (select) Nav.NEXT_CATEGORY else Nav.OPEN_SETTINGS)
            }
        }
    }

    /**
     * Single-screen tabs layout: double-clicking L3 opens the big player (one click still changes the view, a moment
     * later, once no second click came). Set while that layout is on screen.
     */
    @Volatile var l3OpensPlayer = false
    private var l3Single: Runnable? = null
    private var lastL3At = 0L

    private fun leftStickClick() {
        val now = android.os.SystemClock.uptimeMillis()
        l3Single?.let(main::removeCallbacks)
        l3Single = null
        if (now - lastL3At < DOUBLE_CLICK_MS) {
            lastL3At = 0L
            navFlow.tryEmit(Nav.OPEN_PLAYER)
            return
        }
        lastL3At = now
        l3Single = Runnable { l3Single = null; navFlow.tryEmit(Nav.CHANGE_VIEW) }.also { main.postDelayed(it, DOUBLE_CLICK_MS) }
    }

    // R3: one click waits a moment for a second one; two clicks close together turn the lyrics on / off.
    private var r3Single: Runnable? = null
    private var lastR3At = 0L

    private fun rightStickClick() {
        val now = android.os.SystemClock.uptimeMillis()
        r3Single?.let(main::removeCallbacks)
        r3Single = null
        if (now - lastR3At < DOUBLE_CLICK_MS) {
            lastR3At = 0L
            com.dualamp.settings.Prefs.lyricsOn.value = !com.dualamp.settings.Prefs.lyricsOn.value
            return
        }
        lastR3At = now
        r3Single = Runnable { r3Single = null; VizControl.toggleOverlay() }.also { main.postDelayed(it, DOUBLE_CLICK_MS) }
    }

    private const val HOLD_MS = 500L
    private const val DOUBLE_CLICK_MS = 350L

    private var rightTriggerDown = false
    private var lastTriggerToggle = 0L

    /** R2 may arrive as both a key and an axis; act on the first one only. */
    private fun triggerPlayPause() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTriggerToggle < 300) return
        lastTriggerToggle = now
        PlayerConnection.togglePlay()
    }
    private var leftTriggerDown = false
    private var lastSymbolsAt = 0L

    // L2 / R2 tap: L2 the next visualizer (without bringing the song info back; its name pops up top right),
    // R2 play / pause. Held for 0.4 s: rewind (L2) or fast forward (R2) through the song until released.
    // A trigger can arrive as both a key and an axis; the second report of the same press is ignored.
    private val triggerPressed = BooleanArray(2)
    private val triggerSeeking = BooleanArray(2)
    private val triggerHold = arrayOfNulls<Runnable>(2)

    internal fun triggerDown(right: Boolean) {
        val i = if (right) 1 else 0
        if (triggerPressed[i]) return
        triggerPressed[i] = true
        triggerSeeking[i] = false
        val step = object : Runnable {
            override fun run() {
                if (!triggerPressed[i]) return
                triggerSeeking[i] = true
                val pos = PlayerConnection.positionMs
                val dur = PlayerConnection.state.value.durationMs
                val to = (pos + if (right) SEEK_STEP_MS else -SEEK_STEP_MS).coerceIn(0, if (dur > 0) dur - 1000 else Long.MAX_VALUE)
                PlayerConnection.seekTo(to)
                VizControl.pokeOverlay()
                main.postDelayed(this, SEEK_EVERY_MS)
            }
        }
        triggerHold[i] = step
        main.postDelayed(step, TRIGGER_HOLD_MS)
    }

    internal fun triggerUp(right: Boolean) {
        val i = if (right) 1 else 0
        if (!triggerPressed[i]) return
        triggerPressed[i] = false
        triggerHold[i]?.let(main::removeCallbacks)
        if (triggerSeeking[i]) { android.util.Log.d("ControllerBus", "${if (right) "R2" else "L2"} hold: seeked to ${PlayerConnection.positionMs / 1000}s"); return }
        android.util.Log.d("ControllerBus", "${if (right) "R2" else "L2"} tap")
        if (right) { triggerPlayPause(); VizControl.pokeOverlay() } else VizControl.leftTrigger()
    }

    private const val TRIGGER_HOLD_MS = 400L
    private const val SEEK_EVERY_MS = 150L
    private const val SEEK_STEP_MS = 3000L

    /** L2 on the keyboard. It may arrive as both a key and an axis; act on the first one only. */
    private fun switchSymbols() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSymbolsAt < 250) return
        lastSymbolsAt = now
        navFlow.tryEmit(Nav.KB_SYMBOLS)
    }

    /**
     * Analog triggers: some controllers report L2/R2 only as axes. A pull past halfway counts as one
     * press. Call from Activity.dispatchGenericMotionEvent.
     */
    fun handleMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        val r = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        val l = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        if (r > 0.5f && !rightTriggerDown) triggerDown(true)
        if (r < 0.3f && rightTriggerDown) triggerUp(true)
        // On the keyboard, L2 switches letters / numbers and symbols instead of changing the visualizer.
        if (l > 0.5f && !leftTriggerDown) { if (textInput) switchSymbols() else triggerDown(false) }
        if (l < 0.3f && leftTriggerDown && !textInput) triggerUp(false)
        rightTriggerDown = r > 0.3f
        leftTriggerDown = l > 0.3f
        return false
    }

    private fun keyboardNavFor(keyCode: Int): Nav? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_X -> Nav.KB_DELETE
        KeyEvent.KEYCODE_BUTTON_Y -> Nav.KB_SPACE
        KeyEvent.KEYCODE_BUTTON_L2 -> Nav.KB_SYMBOLS
        KeyEvent.KEYCODE_BUTTON_L1 -> Nav.KB_CURSOR_LEFT
        KeyEvent.KEYCODE_BUTTON_R1 -> Nav.KB_CURSOR_RIGHT
        else -> null
    }

    /**
     * How the screen with the library is turned (Surface.ROTATION_*), so the D-pad matches what you see in portrait:
     * held with the left side up (90), pressing the D-pad's left goes up on screen, and so on.
     */
    @Volatile var libraryRotation = 0

    private fun turned(keyCode: Int): Int {
        val order = intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
        val i = order.indexOf(keyCode)
        // The Thor's screens are portrait panels: held normally (landscape) they report ROTATION_90. Turns are
        // counted from there, so landscape needs no change and portrait moves each direction a quarter.
        val turn = (1 - libraryRotation + 4) % 4
        if (i < 0 || turn == 0) return keyCode
        return order[(i + turn) % 4]
    }

    private fun navFor(rawKeyCode: Int): Nav? = when (val keyCode = turned(rawKeyCode)) {
        KeyEvent.KEYCODE_DPAD_UP -> Nav.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Nav.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Nav.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Nav.RIGHT
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> Nav.SELECT
        KeyEvent.KEYCODE_BUTTON_B -> Nav.BACK
        KeyEvent.KEYCODE_BUTTON_Y -> Nav.MENU
        else -> null
    }

    private fun isHandled(keyCode: Int) = keyCode in setOf(
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
    )
}
