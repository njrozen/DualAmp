package com.dualamp

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import com.dualamp.settings.Prefs

/**
 * Finds the two built-in screens and opens one activity on each.
 *
 * On the Ayn Thor the 6" top panel is the default display and the 3.92" bottom panel is a second,
 * public display. "Swap screens" in settings flips the roles for devices wired the other way round.
 */
object DualScreen {

    fun secondaryDisplay(context: Context): Display? {
        val dm = context.getSystemService(DisplayManager::class.java)
        return dm.displays.firstOrNull { d ->
            d.displayId != Display.DEFAULT_DISPLAY &&
                (d.flags and Display.FLAG_PRIVATE) == 0 &&
                !d.name.contains("virtual", ignoreCase = true) &&
                !d.name.contains("overlay", ignoreCase = true)
        }
    }

    /** True when the now-playing and library screens should be separate activities. */
    fun isDualActive(context: Context): Boolean =
        Prefs.screens.value == "BOTH" && secondaryDisplay(context) != null

    val screenOptions = listOf("BOTH", "TOP", "BOTTOM")
    fun screensLabel(value: String) = when (value) {
        "TOP" -> "Top Screen"
        "BOTTOM" -> "Bottom Screen"
        else -> "Both Screens"
    }

    /**
     * The screens DualAmp is actually using right now (BOTH, TOP or BOTTOM), set when its screen opens. It can
     * differ from Settings: opened on the bottom screen (e.g. as a Cocoon companion app), it runs there alone.
     */
     var running: String? = null

    /** Opens DualAmp on one screen only (now playing and the library together). */
    fun launchSingle(context: Context, displayId: Int) {
        AppEvents.closeDual()
        launchOn(context, SingleScreenActivity::class.java, displayId)
    }

    fun topDisplayId(context: Context): Int {
        val second = secondaryDisplay(context) ?: return Display.DEFAULT_DISPLAY
        return if (Prefs.swapScreens.value) second.displayId else Display.DEFAULT_DISPLAY
    }

    fun bottomDisplayId(context: Context): Int {
        val second = secondaryDisplay(context) ?: return Display.DEFAULT_DISPLAY
        return if (Prefs.swapScreens.value) Display.DEFAULT_DISPLAY else second.displayId
    }

    /** Opens now-playing on the top screen and the library on the bottom one (or one combined screen). */
    fun launchAll(context: Context) {
        if (secondaryDisplay(context) == null) {
            context.startActivity(
                Intent(context, NowPlayingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        when (Prefs.screens.value) {
            "TOP" -> { launchSingle(context, topDisplayId(context)); return }
            "BOTTOM" -> { launchSingle(context, bottomDisplayId(context)); return }
        }
        AppEvents.closeSingle()
        launchOn(context, NowPlayingActivity::class.java, topDisplayId(context))
        // Launched last so the bottom screen starts with input focus for the gamepad.
        launchOn(context, LibraryActivity::class.java, bottomDisplayId(context))
    }

    fun launchOn(context: Context, cls: Class<*>, displayId: Int) {
        AppEvents.markOwnLaunch()
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        val intent = Intent(context, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent, options.toBundle())
        } catch (e: SecurityException) {
            // Display refused the launch; fall back to wherever the system puts it.
            context.startActivity(intent)
        }
    }
}

/** Whether each screen of the two-screen pair is currently in front (resumed). */
@Volatile var topResumed = false
@Volatile var bottomResumed = false
private var lastPartnerLaunch = 0L

/**
 * When one screen of the pair comes back to the front and the other is not, reopens the other on its
 * display, so leaving DualAmp and returning to either screen always brings both back.
 */
fun DualScreen.reopenPartner(context: Context, partner: Class<*>, displayId: Int, partnerResumed: () -> Boolean) {
    // Give the other screen a moment: when both come back together it resumes on its own.
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        val now = android.os.SystemClock.uptimeMillis()
        if (partnerResumed() || now - lastPartnerLaunch < 1500) return@postDelayed
        if (!isDualActive(context)) return@postDelayed
        lastPartnerLaunch = now
        launchOn(context, partner, displayId)
    }, 400)
}
