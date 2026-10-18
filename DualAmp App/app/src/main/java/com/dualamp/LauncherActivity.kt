package com.dualamp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Display

/**
 * Invisible entry point. Opened on the top screen: the screens chosen in Settings (both, or everything on
 * the top or bottom one). Opened on the bottom screen (or by Cocoon as a companion app there), or on a
 * device with one screen: DualAmp runs single-screen on that display.
 */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val here = display?.displayId ?: Display.DEFAULT_DISPLAY
        // Reopened after a change in Settings: follow the settings, wherever this opened.
        val fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        val openedOnBottom = !fromSettings && DualScreen.secondaryDisplay(this) != null && here != DualScreen.topDisplayId(this)
        // A phone or any device with one screen: the single-screen player.
        val oneScreen = DualScreen.secondaryDisplay(this) == null
        if (openedOnBottom || oneScreen) {
            AppEvents.closeDual()
            AppEvents.markOwnLaunch()
            // Started plainly (no display given), so a companion app host such as Cocoon keeps it.
            startActivity(Intent(this, SingleScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            DualScreen.launchAll(this)
        }
        finish()
    }

    companion object {
        const val EXTRA_FROM_SETTINGS = "from_settings"
    }
}
