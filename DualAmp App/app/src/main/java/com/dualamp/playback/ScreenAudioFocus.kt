package com.dualamp.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import com.dualamp.DualScreen

/**
 * Audio focus, handled by DualAmp itself so the Thor uses the right screen's volume.
 *
 * On the Ayn Thor each screen has its own volume. The system decides which one an app gets from the screen
 * whose app had focus when the app asked for audio focus (GameAssistant's MultiDisplayVolumeControl, using
 * AudioService.getCurrAudioFocusInfo): an app with a window on the bottom screen gets the bottom volume
 * unless that record says the top screen.
 *
 *  - Single-screen mode: the record is the screen DualAmp is on, so it gets that screen's volume as is.
 *  - Both screens: the library is on the bottom screen, so playback started from there would get the bottom
 *    volume. So after taking audio focus, DualAmp opens an invisible screen on the top display for a moment
 *    ([TopFocusActivity]) and asks for audio focus again while it has focus: the record then says the top
 *    screen, and the top screen's volume applies.
 *
 * Elsewhere this behaves like normal audio focus: other apps pause DualAmp, calls pause it and resume it,
 * notifications duck it.
 */
object ScreenAudioFocus {
    private const val TAG = "ScreenAudioFocus"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private lateinit var audio: AudioManager
    private var player: Player? = null
    private var current: AudioFocusRequest? = null
    private var resumeOnGain = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun attach(context: Context, player: Player) {
        app = context.applicationContext
        audio = app.getSystemService(AudioManager::class.java)
        this.player = player
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && current == null) acquire(pointAtScreen = true)
            }
        })
    }

    fun release() {
        current?.let(audio::abandonAudioFocusRequest)
        current = null
        player = null
    }

    /**
     * Asks for audio focus with a fresh request (the old one, if any, is let go after the new one is granted,
     * so there is no gap another app could step into). [pointAtScreen]: in dual-screen mode, then re-record it
     * from the top screen.
     */
    private fun acquire(pointAtScreen: Boolean) {
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change -> onChange(request, change) }, main)
            .build()
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            Log.i(TAG, "Audio focus refused")
            player?.pause()
            return
        }
        val old = current
        current = request
        old?.let(audio::abandonAudioFocusRequest)
        recordedMode = shownMode()
        Log.i(TAG, "Audio focus taken (mode=$recordedMode, pointAtScreen=$pointAtScreen)")
        if (pointAtScreen) pointAtShownScreen()
    }

    /** "dual" (both screens) or "single:<display>", from the DualAmp screen that resumed last. */
    private var shown: String? = null
    private var shownResumed = false
    /** The mode the current audio focus was recorded in. */
    private var recordedMode: String? = null
    private var pending = false

    private fun shownMode() = shown ?: if (DualScreen.isDualActive(app)) "dual" else "single:0"

    /**
     * Opens the invisible [TopFocusActivity] on the screen DualAmp uses (the top one in dual mode), so the next
     * audio-focus request is recorded for it. Only while DualAmp is on screen: never over another app.
     */
    private fun pointAtShownScreen() {
        val mode = shownMode()
        val visible = if (mode == "dual") com.dualamp.topResumed && com.dualamp.bottomResumed else shownResumed
        if (!visible) { pending = true; return }
        pending = false
        val display = if (mode == "dual") DualScreen.topDisplayId(app) else mode.removePrefix("single:").toInt()
        TopFocusActivity.open(app, display, single = mode != "dual")
    }

    /** A DualAmp screen resumed: [dual] the two-screen top screen, or the single-screen one on [displayId]. */
    fun onScreenResumed(dual: Boolean, displayId: Int) {
        shown = if (dual) "dual" else "single:$displayId"
        shownResumed = true
        Log.i(TAG, "Screen resumed ($shown, held=${current != null}, recorded=$recordedMode, pending=$pending)")
        if (current == null || lidTopVolume) return
        // Moved between modes or screens since the record (or it was put off): record again for the screen now in use.
        if (pending || recordedMode != shown) main.postDelayed({ if (current != null) acquire(pointAtScreen = true) }, 600)
    }

    fun onScreenPaused() { shownResumed = false }

    /** DualAmp plays at the bottom screen's volume now (the Thor lists those apps in sys.audio.uids). */
    fun usesBottomVolume(): Boolean {
        val uids = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "sys.audio.uids") as String
        }.getOrNull() ?: return false
        val me = android.os.Process.myUid().toString()
        return uids.split(',').any { it.trim() == me }
    }

    // --- Lid closed --------------------------------------------------------------------------------------------
    // Closing the lid turns the screens off and DualAmp turns one back on (black) for the buttons, which moves
    // Android's focus, and the Thor then re-assigns DualAmp's volume to whichever screen got it. So the record is
    // made again for the screen DualAmp uses, after the lid closes and after it opens.
    //
    // Only the Thor itself can change the bottom screen's volume (Android refuses other apps). So with the lid shut
    // and DualAmp on the bottom screen's volume, it plays at the top screen's volume set to the same loudness for
    // as long as the lid is shut, so the volume buttons control it; opening the lid goes back to the bottom volume.

    private var lidTopVolume = false
    private var savedMainVolume = -1
    private const val SECONDARY_LEVEL = "secondary_screen_volume_level"
    private const val SECONDARY_MAX = 15

    /** [screenOff]: the screen stays off with the lid shut (SleepManager): only the bottom-volume switch needs doing then. */
    fun onLidClosed(screenOff: Boolean = false) {
        main.postDelayed({
            if (current == null) return@postDelayed
            if (usesBottomVolume()) {
                val level = android.provider.Settings.System.getInt(app.contentResolver, SECONDARY_LEVEL, -1)
                if (level >= 0) {
                    savedMainVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level.toFloat() * max / SECONDARY_MAX), 0)
                }
                lidTopVolume = true
                Log.i(TAG, "Lid closed: top screen's volume at the bottom's level ($level / $SECONDARY_MAX)")
                // Screen off: the helper turns it on for a moment to get the focus.
                recordFor(DualScreen.topDisplayId(app), single = false, wake = screenOff)
            } else if (!screenOff) {
                recordFor(shownDisplay(), single = shownMode() != "dual")
            }
        }, 900)
    }

    /** [onlyIfChanged]: only undo the top-screen volume from [onLidClosed], if it was used. */
    fun onLidOpened(onlyIfChanged: Boolean = false) {
        if (onlyIfChanged && !lidTopVolume) return
        main.postDelayed({
            if (lidTopVolume) {
                lidTopVolume = false
                if (savedMainVolume >= 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedMainVolume, 0)
                savedMainVolume = -1
            }
            if (current != null) recordFor(shownDisplay(), single = shownMode() != "dual")
        }, 900)
    }

    private fun shownDisplay(): Int {
        val mode = shownMode()
        return if (mode == "dual") DualScreen.topDisplayId(app) else mode.removePrefix("single:").toIntOrNull() ?: 0
    }

    /** Re-records audio focus from [displayId] now, even with the screens dark (the lid helper shows over the lock screen). */
    private fun recordFor(displayId: Int, single: Boolean, wake: Boolean = false) {
        pending = false
        // The helper asks for audio focus again once it has the focus ([recordFromHelper]).
        TopFocusActivity.open(app, displayId, single, wake)
    }

    /** Called by [TopFocusActivity] while it has focus. */
    internal fun recordFromHelper() {
        pending = false
        if (current != null) acquire(pointAtScreen = false)
    }

    private fun onChange(request: AudioFocusRequest, change: Int) {
        if (request !== current) return // an older request, replaced by acquire()
        val p = player ?: return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                current = null
                resumeOnGain = false
                audio.abandonAudioFocusRequest(request)
                p.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (p.playWhenReady) {
                resumeOnGain = true
                p.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> p.volume = 0.2f
            AudioManager.AUDIOFOCUS_GAIN -> {
                p.volume = 1f
                if (resumeOnGain) { resumeOnGain = false; p.play() }
            }
        }
    }
}
