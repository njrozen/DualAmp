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
 *  B          back                             Y           item menu
 *  X          play / pause                     Select      next tab (hold: search keyboard)
 *  Start      Settings (hold: visualizer folders)
 *  L1 / R1    previous / next track            R2          play / pause
 *  L2         next visualizer preset
 *  L3         clear the queue (asks first)        R3   show / hide the song info on the top screen
 */
object ControllerBus {

    enum class Nav {
        UP, DOWN, LEFT, RIGHT, SELECT, BACK, MENU, NEXT_CATEGORY, CLEAR_QUEUE, OPEN_SETTINGS,
        OPEN_SEARCH, OPEN_VISUALIZERS, KB_DELETE, KB_SPACE, KB_SYMBOLS, KB_CURSOR_LEFT, KB_CURSOR_RIGHT,
    }

    /** True while the on-screen keyboard is open: X, Y, L1, R1 and L2 edit text instead of their usual jobs. */
    @Volatile var textInput = false

    private val navFlow = MutableSharedFlow<Nav>(extraBufferCapacity = 32)
    val nav: SharedFlow<Nav> get() = navFlow

    /** Returns true when the event was consumed. Call from Activity.dispatchKeyEvent. */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || event.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            handleHoldable(event)
            return true
        }
        val nav = (if (textInput) keyboardNavFor(event.keyCode) else null) ?: navFor(event.keyCode)
        if (nav != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && nav == Nav.KB_SYMBOLS) switchSymbols()
            else if (event.action == KeyEvent.ACTION_DOWN && nav != Nav.KB_SYMBOLS) navFlow.tryEmit(nav)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return isHandled(event.keyCode)
        if (event.repeatCount > 0) return isHandled(event.keyCode)
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_X -> PlayerConnection.togglePlay()
            KeyEvent.KEYCODE_BUTTON_L1 -> PlayerConnection.previous()
            KeyEvent.KEYCODE_BUTTON_R1 -> PlayerConnection.next()
            KeyEvent.KEYCODE_BUTTON_L2 -> VizControl.leftTrigger()
            KeyEvent.KEYCODE_BUTTON_R2 -> triggerPlayPause()
            // Toggle only: the "show the info on any button" rule below would undo the hide.
            KeyEvent.KEYCODE_BUTTON_THUMBR -> { VizControl.toggleOverlay(); return true }
            else -> return false
        }
        VizControl.pokeOverlay()
        return true
    }

    // Select and Start: a short press acts when released; holding for half a second does something else.
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingHold: Runnable? = null
    private var holdFired = false

    private fun handleHoldable(event: KeyEvent) {
        val select = event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                pendingHold?.let(main::removeCallbacks)
                holdFired = false
                pendingHold = Runnable {
                    holdFired = true
                    navFlow.tryEmit(if (select) Nav.OPEN_SEARCH else Nav.OPEN_VISUALIZERS)
                }.also { main.postDelayed(it, HOLD_MS) }
            }
            KeyEvent.ACTION_UP -> {
                pendingHold?.let(main::removeCallbacks)
                pendingHold = null
                if (!holdFired) navFlow.tryEmit(if (select) Nav.NEXT_CATEGORY else Nav.OPEN_SETTINGS)
            }
        }
    }

    private const val HOLD_MS = 500L

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
        if (r > 0.5f && !rightTriggerDown) { triggerPlayPause(); VizControl.pokeOverlay() }
        // On the keyboard, L2 switches letters / numbers and symbols instead of changing the visualizer.
        if (l > 0.5f && !leftTriggerDown) { if (textInput) switchSymbols() else VizControl.leftTrigger() }
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

    private fun navFor(keyCode: Int): Nav? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Nav.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Nav.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Nav.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Nav.RIGHT
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> Nav.SELECT
        KeyEvent.KEYCODE_BUTTON_B -> Nav.BACK
        KeyEvent.KEYCODE_BUTTON_Y -> Nav.MENU
        KeyEvent.KEYCODE_BUTTON_THUMBL -> Nav.CLEAR_QUEUE
        else -> null
    }

    private fun isHandled(keyCode: Int) = keyCode in setOf(
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
    )
}
