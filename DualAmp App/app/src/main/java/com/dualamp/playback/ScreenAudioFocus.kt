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
        if (current == null) return
        // Moved between modes or screens since the record (or it was put off): record again for the screen now in use.
        if (pending || recordedMode != shown) main.postDelayed({ if (current != null) acquire(pointAtScreen = true) }, 600)
    }

    fun onScreenPaused() { shownResumed = false }

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
