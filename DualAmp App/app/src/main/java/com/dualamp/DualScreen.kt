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
        Prefs.dualScreen.value && secondaryDisplay(context) != null

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
        if (!isDualActive(context)) {
            context.startActivity(
                Intent(context, NowPlayingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
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
