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

    /** Which screens DualAmp uses when there are two: BOTH, TOP or BOTTOM (everything on that one screen). */
    val screens = Setting("screens", "BOTH")
    /** Put the library on the default display and now-playing on the second one. */
    val swapScreens = Setting("swap_screens", false)
    /** Seconds before the song info fades out on the top screen. 0 = never. */
    /** Seconds before the song info fades to the visualizer (3-30; 2 or less = never). */
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
    /** What the visualizer shows: PRESETS, ALBUM_ART (built from the song's cover) or BOTH ([com.dualamp.viz.AlbumArtViz.Source]). */
    val vizSource = Setting("viz_source", "PRESETS")
    /** Album art style showing now ([com.dualamp.viz.ART_STYLES]). */
    val artStyle = Setting("art_style", 0)
    /** Visualizer changes (arrows, timer, song change) step through the album art styles. */
    val artCycleStyles = Setting("art_cycle_styles", true)
    /** Album art reacts to 16 frequency bands instead of lows, mids and highs. */
    val artFineBands = Setting("art_fine_bands", false)
    /** Album art sliders, 0-100 % of each [com.dualamp.viz.ART_PARAMS] range. */
    val artParams = com.dualamp.viz.ART_PARAMS.map { Setting("art_${it.id}", it.defaultPercent) }
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
    // Theme (see AppTheme): names of the chosen colors.
    val themeBackground = Setting("theme_background", "Dark")
    val themeText = Setting("theme_text", "White")
    val themeSubText = Setting("theme_subtext", "Gray")
    val themeAccent = Setting("theme_accent", "Green")
    val themeIcon = Setting("theme_icon", "White")
    /** Cover Flow: the glow behind the cover the gamepad is on. */
    val coverGlowColor = Setting("cover_glow_color", "Auto")
    /** Circle, Oval, Diamond or "Cross / X". */
    val coverGlowShape = Setting("cover_glow_shape", "Circle")
    /** How the glow appears: Expand, Drop or None. */
    val coverGlowAnim = Setting("cover_glow_anim", "Expand")
    /** Library view on app launch for the categories / the pages inside them: Grid, List, Cover Flow or "Last used". */
    val launchViewRoot = Setting("launch_view_root", "Last used")
    val launchViewPage = Setting("launch_view_page", "Last used")
    val coverGlowOpacity = Setting("cover_glow_opacity", 50)
    /** Grid view: squares per row (3-6). */
    val gridColumns = Setting("grid_columns", 4)
    /** The same in single-screen mode (half a screen, so fewer per row). */
    val gridColumnsSingle = Setting("grid_columns_single", 3)
    /** Single-screen mode: library on the left (landscape) / top (portrait), player on the other side. */
    val singleSwap = Setting("single_swap", false)
    /** Single-screen layout: SPLIT (player and library side by side / stacked) or TABS (library with tabs, the player opens over it). */
    val singleLayout = Setting("single_layout", "SPLIT")
    /** Single-screen TABS layout: the tab bar at the TOP (in the header) or the BOTTOM. */
    val tabsPosition = Setting("tabs_position", "BOTTOM")
    /** One view for the library categories and the pages inside them (an artist, album, playlist...). */
    val sameViewEverywhere = Setting("same_view_everywhere", true)
    val sortModes = Setting("lib_sort_modes", "")

    /** Stream the visualizer to browsers on the same Wi-Fi ([com.dualamp.viz.VizStream]). */
    val vizStream = Setting("viz_stream", false)

    // Settings > Multiple artists: songs listed under each of their artists ([com.dualamp.library.ArtistNames]).
    val splitArtists = Setting("split_artists", true)
    val artistSeparators = Setting("artist_separators", com.dualamp.library.ArtistNames.DEFAULT_SEPARATORS.joinToString("\n"))
    val artistKeep = Setting("artist_keep", com.dualamp.library.ArtistNames.DEFAULT_KEEP.joinToString("\n"))
    val artistsFromTitles = Setting("artists_from_titles", true)

    // Lyrics on the player (the lyrics button, or double-click the right stick, R3).
    val lyricsOn = Setting("lyrics_on", false)
    /** Darkness of the box behind the lyrics over the visualizer, 0-100 %. */
    val lyricsBackdrop = Setting("lyrics_backdrop", 55)
    /** Timed lyrics: positive shows each line sooner, negative later (ms). */
    val lyricsOffsetMs = Setting("lyrics_offset_ms", 0)
    /** Look up lyrics on lrclib.net for songs without a .lrc file or lyrics in their tags. */
    val lyricsOnline = Setting("lyrics_online", true)

    // Last queue, restored on the next launch.
    val queueIds = Setting("queue_ids", "")
    val queueIndex = Setting("queue_index", 0)
    val queuePositionMs = Setting("queue_pos", 0)

    private val all = listOf(
        musicFolders, serverUrl, showPlaylistFiles, infoBackdrop, vizEnabled, lidButtons, orientation, showControlsAtLaunch, onboardingDone,
        dspEnabled, dspPreamp, dspBass, dspTreble, dspBalance, dspWidth, dspMono, dspLimiter,
        screens, swapScreens, overlayTimeoutSec, presetMode, presetDurationSec, vizReactivity, activeVizFolder, shufflePresets, l2Random, hardCuts,
        vizQuality, vizFpsCap, showPresetName, libraryCategory, viewModes, themeBackground, themeText, themeSubText, themeAccent, themeIcon, coverGlowColor, coverGlowShape, coverGlowAnim, launchViewRoot, launchViewPage, coverGlowOpacity, gridColumns, gridColumnsSingle, singleSwap, singleLayout, tabsPosition, sameViewEverywhere, sortModes,
        lyricsOn, lyricsBackdrop, lyricsOffsetMs, lyricsOnline, vizStream, splitArtists, artistSeparators, artistKeep, artistsFromTitles,
        queueIds, queueIndex, queuePositionMs, vizSource, artStyle, artCycleStyles, artFineBands,
    ) + eqBands + artParams

    fun init(context: Context) {
        sp = context.getSharedPreferences("dualamp", Context.MODE_PRIVATE)
        all.forEach { it.load() }
        // 1.0 had an on/off "Use both screens": off meant everything on the top screen.
        if (!sp.contains("screens") && !sp.getBoolean("dual_screen", true)) screens.value = "TOP"
    }

    /** Small "key=value;key=value" map stored in one string pref. */
    fun mapGet(setting: Setting<String>, key: String): String? =
        setting.value.split(';').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    fun mapPut(setting: Setting<String>, key: String, value: String) {
        val entries = setting.value.split(';').filter { it.isNotBlank() && !it.startsWith("$key=") }
        setting.value = (entries + "$key=$value").joinToString(";")
    }
}

/** The library's view settings: "root" = the categories (Playlists, Artists...), "page" = pages inside them. */
object LibraryView {
    fun key(root: Boolean): String = if (Prefs.sameViewEverywhere.value) "all" else if (root) "root" else "page"
    fun get(root: Boolean): String = Prefs.mapGet(Prefs.viewModes, key(root)) ?: "GRID"
    fun set(root: Boolean, view: String) = Prefs.mapPut(Prefs.viewModes, key(root), view)
}

/** At app launch: Settings > Launch view (unless "Last used") sets the library views. */
fun applyLaunchViews() {
    val names = mapOf("Grid" to "GRID", "List" to "LIST", "Cover Flow" to "COVERFLOW")
    if (Prefs.sameViewEverywhere.value) {
        names[Prefs.launchViewRoot.value]?.let { LibraryView.set(true, it) }
    } else {
        names[Prefs.launchViewRoot.value]?.let { LibraryView.set(true, it) }
        names[Prefs.launchViewPage.value]?.let { LibraryView.set(false, it) }
    }
}
