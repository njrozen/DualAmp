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
                Intent.ACTION_SCREEN_OFF -> { screenOff = true; if (lidClosed) rewake() }
                Intent.ACTION_SCREEN_ON -> {
                    screenOff = false
                    screenOnAt = SystemClock.uptimeMillis()
                }
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        power = getSystemService(PowerManager::class.java)
        screenOff = !power.isInteractive
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        hasLidSensor = startHallSensor()
        startLogWatcher()
        registerDebugHooks()
        // Lid closed and paused for 10 minutes: let the screen sleep (and save battery).
        pauseJob = kotlinx.coroutines.MainScope().launch {
            PlayerConnection.state.collect { schedulePausedSleep() }
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
        if (instance === this) instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(debugReceiver) }
        logProcess?.destroy()
        runCatching { getSystemService(android.hardware.SensorManager::class.java).unregisterListener(hallListener) }
        lidJob?.cancel()
        pauseJob?.cancel()
        onLidOpened()
        logThread?.interrupt()
        removeDockDisplay()
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
        // Lid shut, screen kept off: the buttons are read from the log, and go to no app in the moment the Thor is awake.
        if (lidAsleep && (event.keyCode in SHOULDER_KEYS || event.keyCode == KeyEvent.KEYCODE_BUTTON_L2)) return true
        // Lid shut: L2 / R2 work as in the app (tap: next visualizer / play-pause; hold: rewind / fast forward).
        if (lidClosed && (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2)) {
            if (PlayerConnection.state.value.queue.isEmpty()) return false
            val right = event.keyCode == KeyEvent.KEYCODE_BUTTON_R2
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) ControllerBus.triggerDown(right)
            else if (event.action == KeyEvent.ACTION_UP) ControllerBus.triggerUp(right)
            return true
        }
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

    /**
     * Lid shut while SleepManager's closed-lid protection is on: it turns the screen off whenever it comes on, so DualAmp
     * does not keep it on. Presses still wake the Thor for a moment and Android logs them; they are read from the log
     * ([onLoggedKey]): L1 / R1 skip, R2 plays / pauses, holding R2 / L2 seeks by how long it was held.
     */
    private var lidAsleep = false

    // SleepManager (com.med.sleepmanager) with its closed-lid protection on turns the screen off whenever it comes on with
    // the lid shut, except while a dock or TV is connected: any display whose name has "external" in it. While the lid
    // is shut with music, DualAmp adds a tiny display of that kind (nothing is shown on it), so the screen can stay on
    // for the buttons. It is removed when the lid opens.
    private var dock: android.hardware.display.VirtualDisplay? = null
    private var dockReader: android.media.ImageReader? = null

    private fun addDockDisplay(force: Boolean = false) {
        if (dock != null || (!force && !sleepManagerBlocks(this))) return
        runCatching {
            val reader = android.media.ImageReader.newInstance(64, 64, android.graphics.PixelFormat.RGBA_8888, 2)
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            dock = dm.createVirtualDisplay(DOCK_NAME, 64, 64, 160, reader.surface,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            dockReader = reader
            Log.i(TAG, "Lid closed: SleepManager dock display added (${dock?.display?.displayId})")
        }.onFailure { Log.w(TAG, "Could not add the dock display", it) }
    }

    private fun removeDockDisplay() {
        dock?.release(); dock = null
        dockReader?.close(); dockReader = null
    }

    private fun onLidClosed() {
        // Both the lid sensor and the system log can report the same close.
        // Also while paused (R2 starts the music again), as long as there is something to play.
        if (lidClosed || PlayerConnection.state.value.queue.isEmpty()) return
        lidClosed = true
        addDockDisplay()
        rewakes = 0
        com.dualamp.AppEvents.lidShut = true
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
            Log.i(TAG, "Lid closed: keeping the screen on (black) for the controls")
            com.dualamp.playback.ScreenAudioFocus.onLidClosed()
            schedulePausedSleep()
        }, 600)
    }

    private fun onLidOpened() {
        if (lidAsleep) {
            lidAsleep = false
            loggedDownAt.clear()
            Log.i(TAG, "Lid opened")
            com.dualamp.AppEvents.lidShut = false
            com.dualamp.playback.ScreenAudioFocus.onLidOpened()
            return
        }
        // Lid mode already ended (paused for a while): still put the volume back.
        if (!lidClosed && lidWake == null) { com.dualamp.playback.ScreenAudioFocus.onLidOpened(onlyIfChanged = true); return }
        Log.i(TAG, "Lid opened: screen back to normal")
        removeDockDisplay()
        lidClosed = false
        com.dualamp.AppEvents.lidShut = false
        lidWake?.let { if (it.isHeld) it.release() }
        lidWake = null
        com.dualamp.AppEvents.setLidBlackout(false)
        pauseTimer?.let(main::removeCallbacks)
        com.dualamp.playback.ScreenAudioFocus.onLidOpened()
    }

    private fun switchToLidAsleep() {
        if (lidAsleep) return
        removeDockDisplay()
        Log.i(TAG, "Lid closed, SleepManager keeps the screen off: buttons from the system log")
        lidClosed = false
        lidWake?.let { if (it.isHeld) it.release() }
        lidWake = null
        pauseTimer?.let(main::removeCallbacks)
        com.dualamp.AppEvents.setLidBlackout(false)
        lidAsleep = true
        com.dualamp.AppEvents.lidShut = true
        com.dualamp.playback.ScreenAudioFocus.onLidClosed(screenOff = true)
    }

    /** The Thor put the screen back to sleep with the lid shut: on again, or the controller stops sending (a few tries). */
    private var rewakes = 0
    private fun rewake() {
        // SleepManager's closed-lid protection turned it off: stop fighting it and read the buttons from the log.
        // (With the dock display SleepManager leaves the screen alone; only without it is there no point fighting.)
        if (sleepManagerBlocks(this) && dock == null) { switchToLidAsleep(); return }
        if (rewakes >= 5) return
        rewakes++
        main.postDelayed({
            if (!lidClosed) return@postDelayed
            lidWake?.let { if (it.isHeld) it.release() }
            @Suppress("DEPRECATION")
            val lock = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "DualAmp:lidClosed")
            lock.acquire(6 * 60 * 60 * 1000L)
            lidWake = lock
            Log.i(TAG, "Screen went off with the lid shut: on again ($rewakes)")
        }, 500)
    }

    /** Lid shut and paused for [PAUSED_SLEEP_MS]: let the screen sleep (R2 then no longer works until the lid opens). */
    private fun schedulePausedSleep() {
        pauseTimer?.let(main::removeCallbacks)
        pauseTimer = null
        if (!lidClosed || PlayerConnection.state.value.isPlaying) return
        pauseTimer = Runnable {
            Log.i(TAG, "Paused with the lid shut for a while: letting the screen sleep")
            lidClosed = false
            lidWake?.let { if (it.isHeld) it.release() }
            lidWake = null
            com.dualamp.AppEvents.setLidBlackout(false)
        }.also { main.postDelayed(it, PAUSED_SLEEP_MS) }
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

    private fun startHallSensor(): Boolean {
        val sm = getSystemService(android.hardware.SensorManager::class.java)
        val hall = sm.getSensorList(android.hardware.Sensor.TYPE_ALL)
            .filter { it.stringType.contains("hall_effect") }
            .sortedByDescending { it.isWakeUpSensor }
            .firstOrNull()
        if (hall == null) { Log.i(TAG, "No lid sensor"); return false }
        sm.registerListener(hallListener, hall, android.hardware.SensorManager.SENSOR_DELAY_NORMAL, main)
        return true
    }

    /** The log watcher gets system lines: Android only gives them after "Allow" on its log-access prompt. */
    @Volatile private var logAccess = false

    /** A DualAmp screen came to the front: if log access was not given yet, ask again (the prompt shows over DualAmp). */
    private fun retryLogAccess() {
        if (logAccess || !com.dualamp.settings.Prefs.lidButtons.value) return
        if (SystemClock.uptimeMillis() - logStartedAt < 15_000) return
        logProcess?.destroy(); logThread?.interrupt(); logThread = null
        startLogWatcher()
    }
    private var logStartedAt = 0L
    // A moment after DualAmp's screens settle (its own helper screens would cover the prompt otherwise).
    private val retryRunnable = Runnable { retryLogAccess() }

    /**
     * With a lid sensor (the Thor) the system-log watcher is not used: the Thor's controller stops sending once the
     * screen is off, so the log only ever shows the press that wakes it, and Android would ask for log access each start.
     */
    private var hasLidSensor = false

    private fun startLogWatcher() {
        if (!com.dualamp.settings.Prefs.lidButtons.value || hasLidSensor) return
        if (checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "READ_LOGS not granted; lid-closed buttons need: adb shell pm grant $packageName android.permission.READ_LOGS")
            return
        }
        logStartedAt = SystemClock.uptimeMillis()
        logThread = Thread({
            try {
                // Only new lines (-T 1), only the window manager's key log.
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "epoch", "-T", "1", "WindowManager:D", "PowerGroup:I", "InputManager:D", "*:S"))
                logProcess = proc
                Log.i(TAG, "Key log watcher started")
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // Any line at all is from the system (the filter only lets system tags through): access was allowed.
                        if (!logAccess) { logAccess = true; Log.i(TAG, "System log access allowed") }
                        if (LID_CLOSED.containsMatchIn(line)) { main.post { onLidClosed() }; return@forEach }
                        if (LID_OPENED.containsMatchIn(line)) { main.post { onLidOpened() }; return@forEach }
                        val m = KEY_LINE.find(line) ?: return@forEach
                        val code = m.groupValues[1].toIntOrNull() ?: return@forEach
                        if (code !in SHOULDER_KEYS && code != KeyEvent.KEYCODE_BUTTON_L2) return@forEach
                        // "-v epoch": the line starts with the time it was logged, in seconds.
                        val at = line.trim().substringBefore(' ').toDoubleOrNull()?.let { (it * 1000).toLong() } ?: System.currentTimeMillis()
                        main.post { onLoggedKey(code, at) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Key log watcher stopped", e)
            }
        }, "DualAmpKeyLog").apply { isDaemon = true; start() }
    }

    // Test builds only: "adb shell am broadcast -a com.dualamp.debug.LID --ez closed true" stands in for the lid sensor.
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.dualamp.debug.LID" -> if (intent.getBooleanExtra("closed", false)) onLidClosed() else onLidOpened()
                "com.dualamp.debug.DOCK" -> if (intent.getBooleanExtra("on", false)) addDockDisplay(force = true) else removeDockDisplay()
            }
        }
    }

    private fun registerDebugHooks() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        registerReceiver(debugReceiver, IntentFilter().apply { addAction("com.dualamp.debug.LID"); addAction("com.dualamp.debug.DOCK") }, RECEIVER_EXPORTED)
    }

    // A press shows in the log twice: when it goes down and when it comes up (with the time of each).
    private val loggedDownAt = HashMap<Int, Long>()

    private fun onLoggedKey(code: Int, atMs: Long) {
        val down = loggedDownAt.remove(code)
        if (down == null || atMs - down > 15_000 || atMs < down) {
            loggedDownAt[code] = atMs
            onLoggedPress(code)
        } else {
            onLoggedRelease(code, atMs - down)
        }
    }

    private fun onLoggedPress(code: Int) {
        when {
            // Screen kept on with the lid shut: the accessibility keys do it (same press, kept to one by [handle]).
            lidClosed -> if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) handle(code)
            // Triggers wait for the release (tap or hold).
            lidAsleep -> if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) handle(code)
            code != KeyEvent.KEYCODE_BUTTON_L2 && asleepContext() && handle(code) -> sleepAgain()
        }
    }

    private fun onLoggedRelease(code: Int, heldMs: Long) {
        if (!lidAsleep || PlayerConnection.state.value.queue.isEmpty()) return
        val right = code == KeyEvent.KEYCODE_BUTTON_R2
        if (code != KeyEvent.KEYCODE_BUTTON_R2 && code != KeyEvent.KEYCODE_BUTTON_L2) return
        if (heldMs < TRIGGER_HOLD_MS) {
            if (right) handle(code)
            return
        }
        // As in the app: 3 s every 150 ms of holding, after the first 0.4 s.
        val steps = (heldMs - TRIGGER_HOLD_MS) / 150 + 1
        val pos = PlayerConnection.positionMs
        val dur = PlayerConnection.state.value.durationMs
        val to = (pos + (if (right) 1 else -1) * steps * 3000).coerceIn(0, if (dur > 0) dur - 1000 else Long.MAX_VALUE)
        Log.d(TAG, "trigger held ${heldMs}ms -> seek ${to / 1000}s")
        PlayerConnection.seekTo(to)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "SleepKeys"
        /** A press logged this soon after the screen came on is the press that woke it. */
        private const val WAKE_GRACE_MS = 400L
        /** A press and its release (and both detection paths) count once within this window. */
        private const val DEDUPE_MS = 450L
        /** Has "external" for SleepManager, and "virtual" so DualAmp never takes it for the bottom screen. */
        const val DOCK_NAME = "DualAmp virtual external (lid buttons)"
        private const val TRIGGER_HOLD_MS = 400L
        private const val PAUSED_SLEEP_MS = 10 * 60 * 1000L
        /** Lid sensor value with the lid shut (it reads 1 when open). */
        private const val HALL_CLOSED = 0f
        private val SHOULDER_KEYS = setOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2)
        private val KEY_LINE = Regex("""interceptKeyBeforeQueueing keycode=(\d+)""")
        private val LID_CLOSED = Regex("""Sleeping power group.*reason=lid_switch""")
        // "lidOpen=true" covers opening while DualAmp keeps the screen awake (then there is no lid wake-up).
        private val LID_OPENED = Regex("""Waking up.*WAKE_REASON_LID|lidOpen=true""")

        /**
         * SleepManager (com.med.sleepmanager) with its "AYN Thor closed-lid protection" on turns the screen off whenever
         * it comes on with the lid shut, so the controller stops sending and the lid-closed buttons cannot work.
         * Seen as SleepManager being a device admin (it needs that for the protection).
         */
        fun sleepManagerBlocks(context: Context): Boolean = runCatching {
            context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                ?.activeAdmins.orEmpty().any { it.packageName == "com.med.sleepmanager" }
        }.getOrDefault(false)

        @Volatile private var instance: SleepKeysService? = null

        /** From DualAmp's screens when they come to the front. */
        fun onAppForeground() { instance?.let { s -> s.main.removeCallbacks(s.retryRunnable); s.main.postDelayed(s.retryRunnable, 2500) } }

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.startsWith(context.packageName + "/") && it.endsWith("SleepKeysService") }
        }
    }
}
