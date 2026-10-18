package com.dualamp.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App settings. Each value is a StateFlow so both screens react to changes immediately. */
object Prefs {
    private lateinit var sp: SharedPreferences

    class Setting<T>(private val key: String, private val default: T) {
        private val flow = MutableStateFlow(default)
        val state: StateFlow<T> get() = flow.asStateFlow()
        var value: T
            get() = flow.value
            set(v) {
                flow.value = v
                sp.edit().apply {
                    when (v) {
                        is Boolean -> putBoolean(key, v)
                        is Int -> putInt(key, v)
                        is Float -> putFloat(key, v)
                        is String -> putString(key, v)
                        else -> error("Unsupported pref type for $key")
                    }
                }.apply()
            }

        @Suppress("UNCHECKED_CAST")
        internal fun load() {
            if (!sp.contains(key)) return
            flow.value = when (default) {
                is Boolean -> sp.getBoolean(key, default)
                is Int -> sp.getInt(key, default)
                is Float -> sp.getFloat(key, default)
                is String -> sp.getString(key, default)
                else -> default
            } as T
        }
    }

    /** Use both screens when a second display exists. */
    val dualScreen = Setting("dual_screen", true)
    /** Put the library on the default display and now-playing on the second one. */
    val swapScreens = Setting("swap_screens", false)
    /** Seconds before the song info fades out on the top screen. 0 = never. */
    val overlayTimeoutSec = Setting("overlay_timeout", 4)
    /** When the visualizer moves to a new preset: INTERVAL, SONG (on track change) or LOCKED. */
    val presetMode = Setting("preset_mode", "INTERVAL")
    /** Seconds per preset in INTERVAL mode (20-120). */
    val presetDurationSec = Setting("preset_duration_s", 30)
    /** How strongly the visualizer reacts to the music, 50-300 % (see AudioShaper). */
    val vizReactivity = Setting("viz_reactivity", 150)
    /** Id of the visualizer folder to cycle through; empty = all presets. */
    val activeVizFolder = Setting("active_viz_folder", "")
    val shufflePresets = Setting("shuffle_presets", true)
    /** L2 picks a random visualizer of the folder instead of the next one. */
    val l2Random = Setting("l2_random", false)
    /** Let projectM jump to a new preset on strong beats. */
    val hardCuts = Setting("hard_cuts", false)
    /** Visualizer render scale in percent of the screen resolution. */
    val vizQuality = Setting("viz_quality", 100)
    val vizFpsCap = Setting("viz_fps", 60)
    val showPresetName = Setting("show_preset_name", true)
    /** Darkness (0-100 %) of the background behind the song info on the top screen. */
    val infoBackdrop = Setting("info_backdrop", 50)
    /** Show the MilkDrop visualizer on the top screen (off: blurred album art instead). */
    val vizEnabled = Setting("viz_enabled", true)
    /** Watch the system key log so shoulder buttons work with the lid closed (Android asks for log access). */
    val lidButtons = Setting("lid_buttons", false)
    /** Screen orientation for both screens: AUTO (follows the device), LANDSCAPE or PORTRAIT. */
    val orientation = Setting("orientation", "AUTO")
    /** Show the controls guide when the app starts. */
    val showControlsAtLaunch = Setting("show_controls_at_launch", true)
    /** First-launch prompts (music folder, accessibility) have been answered. */
    val onboardingDone = Setting("onboarding_done", false)

    // Equalizer & DSP (see DspProcessor).
    val dspEnabled = Setting("dsp_enabled", true)
    /** 10 equalizer bands, -12..12 dB each. */
    val eqBands = List(10) { Setting("eq_band_$it", 0) }
    val dspPreamp = Setting("dsp_preamp", 0)
    val dspBass = Setting("dsp_bass", 0)
    val dspTreble = Setting("dsp_treble", 0)
    /** -100 (left only) .. 100 (right only). */
    val dspBalance = Setting("dsp_balance", 0)
    /** Stereo width in percent: 0 = mono, 100 = normal, 200 = extra wide. */
    val dspWidth = Setting("dsp_width", 100)
    val dspMono = Setting("dsp_mono", false)
    val dspLimiter = Setting("dsp_limiter", true)
    /** Folders that make up the music library, one absolute path per line. Empty until the user picks one. */
    val musicFolders = Setting("music_folders", "")
    /** DualAmp Server on a PC ("http://192.168.x.x:8337"); empty = none. */
    val serverUrl = Setting("server_url", "")
    /** Also list .m3u playlist files that Android finds inside the music folders. */
    val showPlaylistFiles = Setting("show_playlist_files", false)

    // Library browser state, remembered between launches.
    val libraryCategory = Setting("lib_category", "ALBUMS")
    val viewModes = Setting("lib_view_modes", "")
    val sortModes = Setting("lib_sort_modes", "")

    // Last queue, restored on the next launch.
    val queueIds = Setting("queue_ids", "")
    val queueIndex = Setting("queue_index", 0)
    val queuePositionMs = Setting("queue_pos", 0)

    private val all = listOf(
        musicFolders, serverUrl, showPlaylistFiles, infoBackdrop, vizEnabled, lidButtons, orientation, showControlsAtLaunch, onboardingDone,
        dspEnabled, dspPreamp, dspBass, dspTreble, dspBalance, dspWidth, dspMono, dspLimiter,
        dualScreen, swapScreens, overlayTimeoutSec, presetMode, presetDurationSec, vizReactivity, activeVizFolder, shufflePresets, l2Random, hardCuts,
        vizQuality, vizFpsCap, showPresetName, libraryCategory, viewModes, sortModes,
        queueIds, queueIndex, queuePositionMs,
    ) + eqBands

    fun init(context: Context) {
        sp = context.getSharedPreferences("dualamp", Context.MODE_PRIVATE)
        all.forEach { it.load() }
    }

    /** Small "key=value;key=value" map stored in one string pref. */
    fun mapGet(setting: Setting<String>, key: String): String? =
        setting.value.split(';').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    fun mapPut(setting: Setting<String>, key: String, value: String) {
        val entries = setting.value.split(';').filter { it.isNotBlank() && !it.startsWith("$key=") }
        setting.value = (entries + "$key=$value").joinToString(";")
    }
}
