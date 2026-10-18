package com.dualamp

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import com.dualamp.settings.Prefs
import kotlinx.coroutines.launch

/** Applies Settings > Screen orientation to an activity, and keeps it applied when it changes. */
object Orientation {
    val options = listOf("AUTO", "LANDSCAPE", "PORTRAIT")

    fun label(value: String) = when (value) {
        "LANDSCAPE" -> "Landscape"
        "PORTRAIT" -> "Portrait"
        else -> "Auto (rotate)"
    }

    fun follow(activity: ComponentActivity) {
        activity.lifecycleScope.launch {
            Prefs.orientation.state.collect { apply(activity, it) }
        }
    }

    private fun apply(activity: Activity, value: String) {
        activity.requestedOrientation = when (value) {
            "LANDSCAPE" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "PORTRAIT" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }
}
