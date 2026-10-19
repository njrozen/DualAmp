package com.dualamp

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.dualamp.input.ControllerBus
import com.dualamp.ui.DualAmpTheme
import com.dualamp.ui.library.LibraryScreen
import kotlinx.coroutines.launch

/** Bottom screen: the library browser. */
class LibraryActivity : ComponentActivity() {
    private var closingForModeSwitch = false
    companion object {
        @Volatile var alive = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycleScope.launch { AppEvents.finishAll.collect { finishAndRemoveTask() } }
        lifecycleScope.launch { AppEvents.finishDual.collect { closingForModeSwitch = true; finishAndRemoveTask() } }
        // Home on the other screen: step aside here too, so both screens show the home menu.
        lifecycleScope.launch { AppEvents.toBackground.collect { moveTaskToBack(true) } }
        Orientation.follow(this)

        // At the top of the library, Back does nothing rather than leaving the bottom screen empty.
        // (Registered first, so the library's own back handling inside Compose takes priority.)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            DualAmpTheme {
                LibraryScreen(Modifier.fillMaxSize().safeDrawingPadding())
            }
        }
        AppEvents.followBlackout(this)
    }

    override fun onResume() {
        super.onResume()
        com.dualamp.input.SleepKeysService.onAppForeground()
        bottomResumed = true
        ControllerBus.libraryRotation = display?.rotation ?: 0
        // Reopened from recents (or its own screen): bring the top screen back too.
        DualScreen.reopenPartner(this, NowPlayingActivity::class.java, DualScreen.topDisplayId(this)) { topResumed }
    }

    override fun onPause() {
        super.onPause()
        bottomResumed = false
    }

    override fun onDestroy() {
        alive = false
        // This screen was closed for good (not just rotated): close the other screen as well.
        if (isFinishing && !isChangingConfigurations && !closingForModeSwitch) AppEvents.closeAll()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AppEvents.userLeft()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        ControllerBus.libraryRotation = display?.rotation ?: 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        ControllerBus.handleKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        ControllerBus.handleMotion(event) || super.dispatchGenericMotionEvent(event)
}
