package com.dualamp.input

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.launch
import com.dualamp.playback.PlayerConnection

/**
 * Handles the shoulder buttons while the Thor is asleep (lid closed or screen off).
 *
 *  L1: previous track    R1: next track    R2: play / pause
 *
 * Two ways in, because Android treats a press that wakes the device as a "wake key" and never passes
 * it to any app (and the SleepManager app puts the Thor straight back to sleep when the lid is closed):
 *
 *  1. Accessibility key filtering: sees presses while the screen is (briefly) on.
 *  2. The system log: Android logs every key it receives, including the swallowed waking ones
 *     ("interceptKeyBeforeQueueing keycode=103"). With READ_LOGS granted once over adb
 *     (`adb shell pm grant com.dualamp android.permission.READ_LOGS`), this service watches that log
 *     and reacts to the shoulder buttons even with the lid shut.
 *
 * Presses while the screen is on and in normal use go to whatever app is open, so games are unaffected.
 * The user turns this on under Settings > Accessibility > DualAmp.
 */
class SleepKeysService : AccessibilityService() {
    private lateinit var power: PowerManager
    private val main = Handler(Looper.getMainLooper())
    private var screenOff = false
    private var screenOnAt = 0L
    private val lastActionAt = HashMap<Int, Long>()
    @Volatile private var logThread: Thread? = null
    @Volatile private var logProcess: Process? = null
    private var lidJob: kotlinx.coroutines.Job? = null
    private var pauseJob: kotlinx.coroutines.Job? = null
    private var pauseTimer: Runnable? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOff = true
                Intent.ACTION_SCREEN_ON -> {
                    screenOff = false
                    screenOnAt = SystemClock.uptimeMillis()
                }
            }
        }
    }

    override fun onServiceConnected() {
        power = getSystemService(PowerManager::class.java)
        screenOff = !power.isInteractive
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        startLogWatcher()
        startHallSensor()
        // Lid closed but paused for 2 minutes: let the screen sleep (and save battery).
        pauseJob = kotlinx.coroutines.MainScope().launch {
            PlayerConnection.state.collect { s ->
                pauseTimer?.let(main::removeCallbacks)
                if (lidClosed && !s.isPlaying) pauseTimer = Runnable { lidWake?.let { if (it.isHeld) it.release() }; lidWake = null; com.dualamp.AppEvents.setLidBlackout(false) }.also { main.postDelayed(it, 120_000) }
            }
        }
        // Black screen tapped away (lid opening missed): end lid mode too.
        kotlinx.coroutines.MainScope().launch {
            com.dualamp.AppEvents.lidBlackout.collect { on -> if (!on && lidWake != null) onLidOpened() }
        }
        // Settings > Lid-closed buttons switched on later: start watching then.
        lidJob = kotlinx.coroutines.MainScope().launch {
            com.dualamp.settings.Prefs.lidButtons.state.collect { on ->
                if (on && logThread == null) startLogWatcher()
                if (!on) { logProcess?.destroy(); logThread?.interrupt(); logThread = null }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        logProcess?.destroy()
        runCatching { getSystemService(android.hardware.SensorManager::class.java).unregisterListener(hallListener) }
        lidJob?.cancel()
        pauseJob?.cancel()
        onLidOpened()
        logThread?.interrupt()
        super.onDestroy()
    }

    /**
     * True only when the press itself arrived with the screen off (or it is the press that just woke it,
     * within a fraction of a second). Presses after the lid opens or the screen is on in normal use go
     * to the open app; the Thor is only put back to sleep for presses that happened while asleep.
     */
    private fun asleepContext(): Boolean {
        val wokeByThisPress = SystemClock.uptimeMillis() - screenOnAt < WAKE_GRACE_MS
        return screenOff || !power.isInteractive || wokeByThisPress
    }

    /** Runs the action for [keyCode] once per press (both detection paths report the same press). */
    private fun handle(keyCode: Int): Boolean {
        val action: () -> Unit = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> PlayerConnection::previous
            KeyEvent.KEYCODE_BUTTON_R1 -> PlayerConnection::next
            KeyEvent.KEYCODE_BUTTON_R2 -> PlayerConnection::togglePlay
            else -> return false
        }
        if (PlayerConnection.state.value.queue.isEmpty()) return false
        val now = SystemClock.uptimeMillis()
        if (now - (lastActionAt[keyCode] ?: 0L) < DEDUPE_MS) return true
        lastActionAt[keyCode] = now
        Log.d(TAG, "shoulder button $keyCode")
        main.post(action)
        return true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in SHOULDER_KEYS) return false
        if (lidClosed) {
            // Lid shut, screen kept on in black: the buttons control music whatever app is underneath.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handle(event.keyCode)
            return PlayerConnection.state.value.queue.isNotEmpty()
        }
        if (!asleepContext()) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handle(event.keyCode)
        if (event.action == KeyEvent.ACTION_UP) sleepAgain()
        return PlayerConnection.state.value.queue.isNotEmpty()
    }

    /** The press woke the screen; send the device back to sleep. */
    // --- Lid closed while music plays -------------------------------------------------------------
    // The Thor's controller only sends input while the screen is on. So when the lid closes during
    // playback, the screen is turned straight back on (black, lowest brightness) until the lid opens.
    private var lidClosed = false
    private var lidWake: PowerManager.WakeLock? = null

    private fun onLidClosed() {
        // Both the lid sensor and the system log can report the same close.
        if (lidClosed || !PlayerConnection.state.value.isPlaying) return
        lidClosed = true
        @Suppress("DEPRECATION")
        val lock = power.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "DualAmp:lidClosed",
        )
        // A moment after the lid's own sleep, so it does not undo it straight back.
        main.postDelayed({
            if (!lidClosed) return@postDelayed
            lock.acquire(6 * 60 * 60 * 1000L)
            lidWake = lock
            com.dualamp.AppEvents.setLidBlackout(true)
            Log.i(TAG, "Lid closed during playback: keeping the screen on (black) for the controls")
        }, 600)
    }

    private fun onLidOpened() {
        if (!lidClosed && lidWake == null) return
        Log.i(TAG, "Lid opened: screen back to normal")
        lidClosed = false
        lidWake?.let { if (it.isHeld) it.release() }
        lidWake = null
        com.dualamp.AppEvents.setLidBlackout(false)
    }

    private fun sleepAgain() {
        main.postDelayed({ if (power.isInteractive) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }, 150)
    }

    // The Thor's lid magnet sensor (no permission needed; the wake-up variant reports while asleep too).
    // Used for lid open / close, so lid mode does not depend on Android's log-access prompt.
    private val hallListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val v = e.values.firstOrNull() ?: return
            Log.i(TAG, "Lid sensor: $v")
            if (!com.dualamp.settings.Prefs.lidButtons.value) return
            if (v == HALL_CLOSED) onLidClosed() else onLidOpened()
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
    }

    private fun startHallSensor() {
        val sm = getSystemService(android.hardware.SensorManager::class.java)
        val hall = sm.getSensorList(android.hardware.Sensor.TYPE_ALL)
            .filter { it.stringType.contains("hall_effect") }
            .sortedByDescending { it.isWakeUpSensor }
            .firstOrNull()
        if (hall == null) { Log.i(TAG, "No lid sensor"); return }
        sm.registerListener(hallListener, hall, android.hardware.SensorManager.SENSOR_DELAY_NORMAL, main)
    }

    private fun startLogWatcher() {
        if (!com.dualamp.settings.Prefs.lidButtons.value) return
        if (checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "READ_LOGS not granted; lid-closed buttons need: adb shell pm grant $packageName android.permission.READ_LOGS")
            return
        }
        logThread = Thread({
            try {
                // Only new lines (-T 1), only the window manager's key log.
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "brief", "-T", "1", "WindowManager:D", "PowerGroup:I", "InputManager:D", "*:S"))
                logProcess = proc
                Log.i(TAG, "Key log watcher started")
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (LID_CLOSED.containsMatchIn(line)) { main.post { onLidClosed() }; return@forEach }
                        if (LID_OPENED.containsMatchIn(line)) { main.post { onLidOpened() }; return@forEach }
                        val m = KEY_LINE.find(line) ?: return@forEach
                        val code = m.groupValues[1].toIntOrNull() ?: return@forEach
                        if (code !in SHOULDER_KEYS) return@forEach
                        // Logged for both press and release; handle() keeps it to one action.
                        main.post {
                            // With the lid closed and the screen kept on, just act; otherwise sleep again after.
                            if (lidClosed) handle(code) else if (asleepContext() && handle(code)) sleepAgain()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Key log watcher stopped", e)
            }
        }, "DualAmpKeyLog").apply { isDaemon = true; start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "SleepKeys"
        /** A press logged this soon after the screen came on is the press that woke it. */
        private const val WAKE_GRACE_MS = 400L
        /** A press and its release (and both detection paths) count once within this window. */
        private const val DEDUPE_MS = 450L
        /** Lid sensor value with the lid shut (it reads 1 when open). */
        private const val HALL_CLOSED = 0f
        private val SHOULDER_KEYS = setOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2)
        private val KEY_LINE = Regex("""interceptKeyBeforeQueueing keycode=(\d+)""")
        private val LID_CLOSED = Regex("""Sleeping power group.*reason=lid_switch""")
        // "lidOpen=true" covers opening while DualAmp keeps the screen awake (then there is no lid wake-up).
        private val LID_OPENED = Regex("""Waking up.*WAKE_REASON_LID|lidOpen=true""")

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.startsWith(context.packageName + "/") && it.endsWith("SleepKeysService") }
        }
    }
}
