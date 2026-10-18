package com.dualamp

import android.app.Activity
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.dualamp.settings.Prefs
import kotlinx.coroutines.launch

/**
 * Applies Settings > Screen orientation to an activity, and keeps it applied when it changes.
 *
 * Android only turns the main (top) display with the sensor. A DualAmp screen on the bottom display copies the
 * top display's rotation, so both screens always face the same way (also when held to landscape or portrait,
 * either way up); in single-screen mode on the bottom (nothing of DualAmp's on top) it reads the motion sensor
 * itself, when the system's auto-rotate is on.
 *
 * The Thor's screens are portrait panels: held normally (landscape) they are at ROTATION_90.
 */
object Orientation {
    val options = listOf("AUTO", "LANDSCAPE", "PORTRAIT")

    fun label(value: String) = when (value) {
        "LANDSCAPE" -> "Landscape"
        "PORTRAIT" -> "Portrait"
        else -> "Auto (rotate)"
    }

    fun follow(activity: ComponentActivity, single: Boolean = false) {
        val bottom = BottomRotation(activity, single)
        activity.lifecycle.addObserver(bottom)
        activity.lifecycleScope.launch {
            Prefs.orientation.state.collect { value ->
                apply(activity, value)
                bottom.allowed = when (value) {
                    "LANDSCAPE" -> setOf(Surface.ROTATION_90, Surface.ROTATION_270)
                    "PORTRAIT" -> setOf(Surface.ROTATION_0, Surface.ROTATION_180)
                    else -> setOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)
                }
                bottom.enabled = (activity.display?.displayId ?: Display.DEFAULT_DISPLAY) != Display.DEFAULT_DISPLAY
            }
        }
    }

    private fun apply(activity: Activity, value: String) {
        activity.requestedOrientation = when (value) {
            "LANDSCAPE" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "PORTRAIT" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    /** The orientation request that puts a Thor screen at [rotation] (Surface.ROTATION_*). */
    private fun forRotation(rotation: Int) = when (rotation) {
        Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    }

    /**
     * Turns a bottom-screen activity: like the top display (two screens), or from the sensor (single-screen).
     * A sensor quarter turn needs the device held within 30° of it, so it does not flip back and forth.
     */
    private class BottomRotation(private val activity: Activity, private val single: Boolean) : DefaultLifecycleObserver {
        /** The rotations Settings > Screen orientation allows (single-screen: the sensor picks among them). */
        var allowed = emptySet<Int>()
        var enabled = false
            set(v) { field = v; current = -1; update() }
        private var resumed = false
        private var current = -1
        private val displays = activity.getSystemService(DisplayManager::class.java)
        private val main = Handler(Looper.getMainLooper())

        private fun turnTo(rotation: Int) {
            if (rotation == current) return
            current = rotation
            activity.requestedOrientation = forRotation(rotation)
        }

        private val topListener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) displays.getDisplay(displayId)?.let { turnTo(it.rotation) }
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }

        private val sensor = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN || !autoRotateOn()) return
                // Degrees from the panel's natural (portrait) position; 90 means its left side is up. The
                // screen turns the other way to stay upright.
                val quarter = ((degrees + 45) / 90) % 4
                val off = minOf((degrees - quarter * 90 + 360) % 360, (quarter * 90 - degrees + 360) % 360)
                val rotation = (4 - quarter) % 4
                if (off <= 30 && rotation in allowed) turnTo(rotation)
            }
        }

        private fun autoRotateOn() =
            Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1

        private fun update() {
            displays.unregisterDisplayListener(topListener)
            sensor.disable()
            if (!enabled) { current = -1; return }
            if (!resumed) return
            if (single) {
                if (sensor.canDetectOrientation()) sensor.enable()
            } else {
                displays.registerDisplayListener(topListener, main)
                displays.getDisplay(Display.DEFAULT_DISPLAY)?.let { turnTo(it.rotation) }
            }
        }

        override fun onResume(owner: LifecycleOwner) { resumed = true; update() }
        override fun onPause(owner: LifecycleOwner) { resumed = false; update() }
    }
}
