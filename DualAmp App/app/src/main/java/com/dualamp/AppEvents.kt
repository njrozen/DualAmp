package com.dualamp

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** App-wide events both screen activities listen to, so the two screens act as one app. */
object AppEvents {
    private val _finishAll = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Both screens close. */
    val finishAll: SharedFlow<Unit> get() = _finishAll

    private val _toBackground = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Both screens step aside so the home screen shows on each display. */
    val toBackground: SharedFlow<Unit> get() = _toBackground

    private val _finishDual = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The two-screen pair closes (single-screen mode was opened). */
    val finishDual: SharedFlow<Unit> get() = _finishDual
    private val _finishSingle = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The single-screen version closes (two-screen mode was opened). */
    val finishSingle: SharedFlow<Unit> get() = _finishSingle

    fun closeDual() { plannedCloseAt = SystemClock.uptimeMillis(); _finishDual.tryEmit(Unit) }
    fun closeSingle() { plannedCloseAt = SystemClock.uptimeMillis(); _finishSingle.tryEmit(Unit) }

    /** When DualAmp last closed screens itself (mode switch, relaunch), not the user. */
    @Volatile private var plannedCloseAt = 0L

    /** A screen was swiped away in Recents: close the other one too, unless DualAmp is swapping screens itself. */
    fun taskRemoved() {
        if (SystemClock.uptimeMillis() - plannedCloseAt > 3000) closeAll()
    }

    private val _lidBlackout = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Lid closed during playback: both screens stay on but black and as dim as possible. */
    val lidBlackout: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _lidBlackout
    fun setLidBlackout(on: Boolean) { _lidBlackout.value = on; lidChangedAt = SystemClock.uptimeMillis() }
    /** The lid is shut (with or without the screen kept on): closing screens then is not the user quitting. */
    @Volatile var lidShut = false
        set(v) { field = v; lidChangedAt = SystemClock.uptimeMillis() }

    /** When the lid last closed or opened (screens can be torn down and rebuilt then; that is not quitting). */
    @Volatile private var lidChangedAt = 0L

    /** Shows a black cover at lowest brightness on [activity] while [lidBlackout] is on. */
    fun followBlackout(activity: androidx.activity.ComponentActivity) {
        val cover = android.view.View(activity).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            visibility = android.view.View.GONE
            isClickable = true // swallow touches while the lid is shut
            // Fallback if the lid opening was missed: a tap brings the screen back.
            setOnClickListener { setLidBlackout(false) }
        }
        activity.addContentView(cover, android.view.ViewGroup.LayoutParams(-1, -1))
        activity.lifecycleScope.launch {
            lidBlackout.collect { on ->
                cover.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
                val w = activity.window
                w.attributes = w.attributes.apply {
                    screenBrightness = if (on) 0f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                if (on) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else if (!com.dualamp.playback.PlayerConnection.state.value.isPlaying)
                    w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    @Volatile private var ownLaunchAt = 0L

    /** Closing either screen (Back, or swiping it away in recents) closes the other one too. */
    fun closeAll() {
        // Quitting the app stops the music too (a planned close, like a relaunch, does not).
        val now = SystemClock.uptimeMillis()
        if (now - plannedCloseAt > 3000 && !_lidBlackout.value && !lidShut && now - lidChangedAt > 10_000) com.dualamp.playback.PlayerConnection.stopForQuit()
        _finishAll.tryEmit(Unit)
    }

    /**
     * Call right before DualAmp itself opens another screen (folder picker, Accessibility settings, ...),
     * so leaving for it is not mistaken for the user pressing Home.
     */
    fun markOwnLaunch() {
        ownLaunchAt = SystemClock.uptimeMillis()
    }

    /** From Activity.onUserLeaveHint: the user pressed Home (or switched apps) on one of the screens. */
    fun userLeft() {
        if (SystemClock.uptimeMillis() - ownLaunchAt > 2000) _toBackground.tryEmit(Unit)
    }

    /** Closes both screens and reopens them, e.g. after the screen layout setting changes. */
    fun relaunch(context: Context) {
        val app = context.applicationContext
        plannedCloseAt = SystemClock.uptimeMillis()
        _finishAll.tryEmit(Unit)
        Handler(Looper.getMainLooper()).postDelayed({
            app.startActivity(Intent(app, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(LauncherActivity.EXTRA_FROM_SETTINGS, true))
        }, 600)
    }
}
