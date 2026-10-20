package com.dualamp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.dualamp.input.ControllerBus
import com.dualamp.library.LibraryRepository
import com.dualamp.playback.PlayerConnection
import com.dualamp.ui.DualAmpTheme
import com.dualamp.ui.NowPlayingScreen
import com.dualamp.ui.library.LibraryScreen
import kotlinx.coroutines.launch

/**
 * Top screen. With a second display it shows only now-playing + visualizer; on a single-screen
 * device it also hosts the library below (or beside, in landscape).
 */
open class NowPlayingActivity : ComponentActivity() {
    /** True for [SingleScreenActivity]: everything on one screen, wherever it was opened. */
    protected open val singleScreen = false
    /** Set when this screen closes because the other mode (single / dual) was opened. */
    private var closingForModeSwitch = false
    private var dual = false


    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LibraryRepository.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        dual = !singleScreen && DualScreen.isDualActive(this)
        if (dual) {
            // The visualizer gets the whole top screen.
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        lifecycleScope.launch { AppEvents.finishAll.collect { finishAndRemoveTask() } }
        lifecycleScope.launch {
            (if (singleScreen) AppEvents.finishSingle else AppEvents.finishDual).collect {
                closingForModeSwitch = true
                finishAndRemoveTask()
            }
        }
        // Home on the other screen: step aside here too, so both screens show the home menu.
        lifecycleScope.launch { AppEvents.toBackground.collect { moveTaskToBack(true) } }
        Orientation.follow(this, single = singleScreen)
        lifecycleScope.launch {
            // Keep the screen awake while music plays, like a dedicated player.
            PlayerConnection.state.collect { s ->
                if (s.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        if (checkSelfPermission(LibraryRepository.permission) != PackageManager.PERMISSION_GRANTED) {
            AppEvents.markOwnLaunch()
            permissions.launch(arrayOf(LibraryRepository.permission, Manifest.permission.POST_NOTIFICATIONS))
        }

        setContent {
            DualAmpTheme {
                if (dual) {
                    NowPlayingScreen(Modifier.fillMaxSize())
                } else {
                    // Settings > Swap sides (single-screen): library first (left / top), player second.
                    val swap = com.dualamp.settings.Prefs.singleSwap.state.collectAsState().value
                    val layout = com.dualamp.settings.Prefs.singleLayout.state.collectAsState().value
                    if (layout == "TABS") TabsLayout() else
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (maxWidth > maxHeight) {
                            Row(Modifier.fillMaxSize()) {
                                if (swap) LibraryScreen(Modifier.weight(0.5f), showMiniPlayer = false)
                                NowPlayingScreen(Modifier.weight(0.5f))
                                if (!swap) LibraryScreen(Modifier.weight(0.5f), showMiniPlayer = false)
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                if (swap) LibraryScreen(Modifier.weight(0.58f), showMiniPlayer = false)
                                NowPlayingScreen(Modifier.weight(0.42f))
                                if (!swap) LibraryScreen(Modifier.weight(0.58f), showMiniPlayer = false)
                            }
                        }
                    }
                }
            }
        }
        AppEvents.followBlackout(this)
    }

    override fun onResume() {
        super.onResume()
        com.dualamp.input.SleepKeysService.onAppForeground()
        DualScreen.running = when {
            dual -> "BOTH"
            DualScreen.secondaryDisplay(this) != null && display?.displayId == DualScreen.bottomDisplayId(this) -> "BOTTOM"
            else -> "TOP"
        }
        if (!dual) ControllerBus.libraryRotation = display?.rotation ?: 0
        com.dualamp.playback.ScreenAudioFocus.onScreenResumed(dual, display?.displayId ?: 0)
        if (!dual) return
        topResumed = true
        // Reopened from recents (or its own screen): bring the bottom screen back too.
        DualScreen.reopenPartner(this, LibraryActivity::class.java, DualScreen.bottomDisplayId(this)) { bottomResumed }
    }

    override fun onPause() {
        super.onPause()
        com.dualamp.playback.ScreenAudioFocus.onScreenPaused()
        if (dual) topResumed = false
    }

    override fun onDestroy() {
        // This screen was closed for good (not just rotated): close the other screen as well.
        if (isFinishing && !isChangingConfigurations && !closingForModeSwitch) AppEvents.closeAll()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        PlayerConnection.savePosition()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AppEvents.userLeft()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Single-screen: this screen also holds the library.
        if (!dual) ControllerBus.libraryRotation = display?.rotation ?: 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        ControllerBus.handleKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        ControllerBus.handleMotion(event) || super.dispatchGenericMotionEvent(event)
}

/**
 * Single-screen tabs layout (Settings > Screens > Single-screen layout: Tabs), like Spotify: the library fills the
 * screen, opening on Your Library, with its tabs at the top or bottom (Settings > Screens > Tab bar) and the player bar
 * above the bottom. The player bar opens the big player over everything; its back arrow (or B, or Back) closes it.
 */
@androidx.compose.runtime.Composable
private fun TabsLayout() {
    // Double-click L3 opens the big player while this layout is up.
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.dualamp.input.ControllerBus.l3OpensPlayer = true
        onDispose { com.dualamp.input.ControllerBus.l3OpensPlayer = false }
    }
    val open = com.dualamp.ui.library.BigPlayer.open.collectAsState().value
    val bottom = com.dualamp.settings.Prefs.tabsPosition.state.collectAsState().value == "BOTTOM"
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        LibraryScreen(Modifier.fillMaxSize(), showMiniPlayer = true, singleScreen = true, tabsAtBottom = bottom,
            onOpenPlayer = { com.dualamp.ui.library.BigPlayer.open.value = true })
        androidx.compose.animation.AnimatedVisibility(
            visible = open,
            enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
        ) {
            // Covers the library, so its tabs and buttons cannot be pressed until the big player closes.
            NowPlayingScreen(Modifier.fillMaxSize(), onClose = { com.dualamp.ui.library.BigPlayer.open.value = false })
        }
    }
    androidx.activity.compose.BackHandler(enabled = open) { com.dualamp.ui.library.BigPlayer.open.value = false }
}

/**
 * DualAmp on one screen (now playing above the library), used when it is opened on the bottom screen,
 * e.g. as a Cocoon companion app. A separate activity so it never collides with the two-screen pair.
 */
class SingleScreenActivity : NowPlayingActivity() {
    override val singleScreen = true
}
