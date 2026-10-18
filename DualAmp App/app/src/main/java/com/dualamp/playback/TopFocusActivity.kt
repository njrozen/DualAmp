package com.dualamp.playback

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.dualamp.DualScreen

/**
 * Invisible screen opened for a moment on the screen DualAmp uses, the top one with two screens (see [ScreenAudioFocus]):
 * while it has focus, DualAmp asks for audio focus again so the Thor applies that screen's volume. It closes straight away.
 */
open class TopFocusActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        ScreenAudioFocus.recordFromHelper()
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onStop() {
        super.onStop()
        // Never left behind (e.g. if it never got focus).
        if (!isFinishing) finish()
    }

    companion object {
        /** On [displayId]; [single]: in single-screen mode (its own task family, so the right screen's task is used). */
        fun open(context: Context, displayId: Int, single: Boolean) {
            DualScreen.launchOn(context, if (single) SingleFocusActivity::class.java else TopFocusActivity::class.java, displayId)
        }
    }
}

/** The same, for single-screen mode (opened in the single-screen task, on the screen DualAmp is on). */
class SingleFocusActivity : TopFocusActivity()
