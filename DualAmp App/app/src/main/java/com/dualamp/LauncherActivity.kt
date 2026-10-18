package com.dualamp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Display

/**
 * Invisible entry point. Opened on the top screen: one activity per screen. Opened on the bottom
 * screen (or by Cocoon as a companion app there), or on a device with one screen: DualAmp runs
 * single-screen on that display.
 */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val here = display?.displayId ?: Display.DEFAULT_DISPLAY
        val openedOnBottom = DualScreen.isDualActive(this) && here != DualScreen.topDisplayId(this)
        // A phone or any device with one screen: the single-screen player.
        val oneScreen = DualScreen.secondaryDisplay(this) == null
        if (openedOnBottom || oneScreen) {
            AppEvents.closeDual()
            AppEvents.markOwnLaunch()
            startActivity(Intent(this, SingleScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            AppEvents.closeSingle()
            DualScreen.launchAll(this)
        }
        finish()
    }
}
